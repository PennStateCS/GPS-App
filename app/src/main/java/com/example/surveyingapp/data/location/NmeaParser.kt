package com.example.surveyingapp.data.location

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * NMEA Parser (RS2+ friendly)
 *
 * What this file does:
 * - Parses common NMEA sentences (GGA, RMC, GSV, GSA, GST, ZDA)
 * - Combines them into a single "FixSnapshot" with useful fields (lat/lon/alt, fix type, DOP, sats, etc.)
 *
 * Why these sentences matter:
 * - GGA: Fix quality (RTK vs Single), satellites USED, HDOP, altitude (MSL), geoid separation
 * - RMC: Timestamp (UTC), position, speed over ground, course over ground
 * - GSV: Satellites IN VIEW + signal details (from per-constellation messages)
 * - GSA: DOPs (PDOP, HDOP, VDOP)
 * - GST: Error estimates (standard deviations)
 * - ZDA: Date + time (alternative timestamp source)
 *
 * Key RS2+ notes:
 * - RS2+ often outputs "GN..." sentences (multi-GNSS). We also handle "GP"/"GL"/"GA"/"GB"/"GQ".
 * - GGA altitude field (9) is **MSL height**, NOT ellipsoidal height. Ellipsoidal = MSL + geoid separation.
 * - RMC status must be 'A' (valid). We reject 'V' (void).
 */

class NmeaParser {

    /** The type of fix we think we have, based on GGA "fix quality". */
    enum class FixType { INVALID, SINGLE, DGPS, PPS, RTK_FIXED, RTK_FLOAT, DEAD_RECKONING, MANUAL, SIMULATION, PPP, UNKNOWN }

    /** Which GNSS constellation a satellite belongs to. */
    enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS, IRNSS, UNKNOWN }

    /** Details for one satellite (from GSV). */
    data class Satellite(
        val prn: Int,
        val constellation: Constellation,
        val elevationDeg: Int?,
        val azimuthDeg: Int?,
        val snrDb: Int?
    )

    /** Parsed GGA data (altitude here is **MSL** height). */
    data class Gga(
        val timeRaw: String?,            // HHMMSS[.sss]
        val lat: Double?,
        val lon: Double?,
        val fixQuality: Int?,
        val sats: Int?,
        val hdop: Double?,
        val altEllipsoidal: Double?,     // Field 9 originally parsed (actually MSL per NMEA) kept for backward name
        val geoidSeparation: Double?,    // Field 11
        val diffAgeSec: Double?,         // Field 13
        val stationId: String?
    ) {
        // Backwards compatibility: legacy code expects gga.alt (MSL height)
        val alt: Double? get() = altEllipsoidal
        // New clearer alias: altitude relative to mean sea level (orthometric)
        val altMsl: Double? get() = altEllipsoidal
        // Computed ellipsoidal height h = H(msl) + N(geoid separation)
        val ellipsoidalHeight: Double? get() = if (altEllipsoidal != null && geoidSeparation != null) altEllipsoidal + geoidSeparation else null
    }

    /** Parsed RMC data (basic nav + timestamp). */
    data class Rmc(
        val timeRaw: String?,          // HHMMSS(.sss)
        val date: String?,             // DDMMYY
        val lat: Double?,
        val lon: Double?,
        val speedKnots: Double?,       // speed over ground in knots
        val course: Double?            // course over ground in degrees
    )

    /** Parsed GST data (error estimates). */
    data class Gst(
        val timeRaw: String?,
        val rms: Double?,
        val semiMajor: Double?,
        val semiMinor: Double?,
        val orientation: Double?,
        val latStd: Double?,           // std dev of latitude error (meters)
        val lonStd: Double?,           // std dev of longitude error (meters)
        val altStd: Double?            // std dev of altitude error (meters)
    )

    /** Parsed ZDA data (date + time). */
    data class Zda(
        val timeRaw: String?,
        val day: Int?,
        val month: Int?,
        val year: Int?
    )

    /** One GSV message (1 of N). We aggregate these per epoch. */
    data class Gsv(
        val totalMsgs: Int?,           // how many GSV messages in this epoch
        val msgNum: Int?,              // which one is this (1..N)
        val satsInView: Int?,          // total satellites in view
        val satellites: List<Satellite> = emptyList()
    )

    /** DOP values (from GSA). */
    data class Gsa(
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?
    )

    /** Parse result with a reason when dropped, useful for debugging. */
    sealed class ParseResult {
        data class Success(val sentence: NmeaSentence): ParseResult()
        data class Dropped(val reason: Reason): ParseResult()
        enum class Reason { TOO_SHORT, NO_START, BAD_CHECKSUM, UNSUPPORTED, MALFORMED, INACTIVE_STATUS }
    }

    /** The different sentence types we can output. */
    sealed class NmeaSentence {
        data class GgaSentence(val gga: Gga): NmeaSentence()
        data class RmcSentence(val rmc: Rmc): NmeaSentence()
        data class GsvSentence(val gsv: Gsv): NmeaSentence()
        data class GsaSentence(val gsa: Gsa): NmeaSentence()
        data class GstSentence(val gst: Gst): NmeaSentence()
        data class ZdaSentence(val zda: Zda): NmeaSentence()
    }

    /**
     * A snapshot of the "current best" fix after feeding some sentences.
     * Think of this as what you'd want to display in a status bar or log.
     */
    data class FixSnapshot(
        val timestampMillis: Long?,
        val timestampSource: String?,               // "RMC", "ZDA", or "GGA"
        val lat: Double?,
        val lon: Double?,
        val altMsl: Double?,                        // height above MSL (meters)
        val geoidSeparation: Double?,               // N (meters)
        val altEllipsoidal: Double?,                // computed: MSL + geoid
        val speedMps: Double?,
        val courseDeg: Double?,
        val fixType: FixType,
        val satsUsed: Int?,                         // from GGA
        val satsInView: Int?,                       // from aggregated GSV
        val satsPerConstellation: Map<Constellation, Int>,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
        val hAccM: Double?,                         // estimated horizontal accuracy
        val vAccM: Double?,                         // estimated vertical accuracy
        val diffAgeSec: Double?,                    // GGA field 13
        val diffStale: Boolean,                     // true if age > threshold
        val satellites: List<Satellite>,            // per-satellite details (if needed)
        val stationId: String?                      // newly surfaced base station ID (GGA field 15)
    )

    /**
     * Accumulates sentences and builds snapshots.
     * Call feed(...) for each parsed sentence; when we have enough info, build() gives you a snapshot.
     */
    class FixAccumulator(private val staleDiffAgeThresholdSec: Double = 5.0) {
        private var lastGga: Gga? = null
        private var lastRmc: Rmc? = null
        private var lastGsa: Gsa? = null
        private var lastGst: Gst? = null
        private var lastZda: Zda? = null
        private var gsvBuilder = GsvAggregation()
        private var lastEpochMillis: Long? = null

        /**
         * Feed one sentence into the accumulator.
         * Returns a new FixSnapshot only when we just updated "core" fields (GGA/RMC/GST),
         * otherwise returns null to avoid spamming updates.
         */
        fun feed(sentence: NmeaSentence): FixSnapshot? {
            when (sentence) {
                is NmeaSentence.GgaSentence -> lastGga = sentence.gga
                is NmeaSentence.RmcSentence -> lastRmc = sentence.rmc
                is NmeaSentence.GsaSentence -> lastGsa = sentence.gsa
                is NmeaSentence.GstSentence -> lastGst = sentence.gst
                is NmeaSentence.ZdaSentence -> lastZda = sentence.zda
                is NmeaSentence.GsvSentence -> gsvBuilder.ingest(sentence.gsv)
            }
            return if (sentence is NmeaSentence.GgaSentence
                || sentence is NmeaSentence.RmcSentence
                || sentence is NmeaSentence.GstSentence) build() else null
        }

        /** Build a snapshot from the latest values. Safe to call anytime. */
        fun build(): FixSnapshot {
            val gga = lastGga
            val rmc = lastRmc
            val gsa = lastGsa
            val gst = lastGst
            val zda = lastZda
            val gsvData = gsvBuilder.current()
            val fixType = mapFixType(gga?.fixQuality)
            // Timestamp precedence: RMC > ZDA > GGA
            val ts: Pair<Long?, String?> = buildTimestamp(rmc, zda, gga)
            lastEpochMillis = ts.first ?: lastEpochMillis
            val geoid = gga?.geoidSeparation
            val altMsl = gga?.altMsl
            val altEllipsoidal = if (altMsl != null && geoid != null) altMsl + geoid else gga?.ellipsoidalHeight
            val orthometric = altMsl
            val diffAge = gga?.diffAgeSec
            val diffStale = diffAge != null && diffAge > staleDiffAgeThresholdSec
            val satsUsed = gga?.sats
            val satsInView = gsvData?.second?.size ?: gsvData?.first?.satsInView
            val satellites = gsvData?.second ?: emptyList()
            val satsPerConstellation = satellites.groupingBy { it.constellation }.eachCount()
            val pdop = gsa?.pdop
            val hdop = gsa?.hdop ?: gga?.hdop
            val vdop = gsa?.vdop
            val (hAcc, vAcc) = estimateAccuracy(gst, hdop, vdop)
            val speedMps = rmc?.speedKnots?.let { it * 0.514444 } // knots to m/s
            return FixSnapshot(
                timestampMillis = lastEpochMillis,
                timestampSource = ts.second,
                lat = gga?.lat ?: rmc?.lat,
                lon = gga?.lon ?: rmc?.lon,
                altMsl = orthometric,
                geoidSeparation = geoid,
                altEllipsoidal = altEllipsoidal,
                speedMps = speedMps,
                courseDeg = rmc?.course,
                fixType = fixType,
                satsUsed = satsUsed,
                satsInView = satsInView,
                satsPerConstellation = satsPerConstellation,
                pdop = pdop,
                hdop = hdop,
                vdop = vdop,
                hAccM = hAcc,
                vAccM = vAcc,
                diffAgeSec = diffAge,
                diffStale = diffStale,
                satellites = satellites,
                stationId = gga?.stationId
            )
        }

        /** Choose the best timestamp we can build right now. */
        private fun buildTimestamp(rmc: Rmc?, zda: Zda?, gga: Gga?): Pair<Long?, String?> {
            rmc?.let { buildEpochMillisPrecise(it.timeRaw, it.date)?.let { ts -> return ts to "RMC" } }
            zda?.let { buildEpochMillisFromZda(it)?.let { ts -> return ts to "ZDA" } }
            gga?.let { buildEpochMillisPrecise(it.timeRaw, rmc?.date)?.let { ts -> return ts to "GGA" } }
            return null to null
        }

        // Local copy of accuracy estimation (nested class cannot access outer private members)
        private fun estimateAccuracy(gst: Gst?, hdop: Double?, vdop: Double?): Pair<Double?, Double?> {
            gst?.let {
                val h = if (it.latStd != null && it.lonStd != null) sqrt(it.latStd * it.latStd + it.lonStd * it.lonStd) else null
                val v = it.altStd
                return h to v
            }
            val baseUere = 0.6
            val h = hdop?.let { it * baseUere }
            val v = vdop?.let { it * baseUere }
            return h to v
        }

        /** Map GGA "fix quality" number into a readable FixType enum. */
        private fun mapFixType(q: Int?): FixType = when (q) {
            null -> FixType.INVALID      // unknown
            0 -> FixType.INVALID         // no fix
            1 -> FixType.SINGLE          // GPS (SPS)
            2 -> FixType.DGPS            // differential
            3 -> FixType.PPS             // precise positioning service
            4 -> FixType.RTK_FIXED       // RTK fixed
            5 -> FixType.RTK_FLOAT       // RTK float
            6 -> FixType.DEAD_RECKONING  // DR (estimation)
            7 -> FixType.MANUAL          // manual input mode
            8 -> FixType.SIMULATION      // simulation mode
            9 -> FixType.PPP             // sometimes vendors use 9 for PPP
            else -> FixType.UNKNOWN
        }
    }

    /** Aggregates multiple GSV fragments for one epoch. */
    private class GsvAggregation {
        private var expected: Int = 0
        private var collected = mutableSetOf<Int>()
        private val satellites = mutableListOf<Satellite>()
        private var summary: Gsv? = null

        fun ingest(gsv: Gsv) {
            val total = gsv.totalMsgs ?: return
            // If this is the first fragment or a new total count, reset aggregation.
            if (gsv.msgNum == 1 || total != expected) {
                expected = total
                collected.clear()
                satellites.clear()
            }
            summary = gsv
            val msgNum = gsv.msgNum ?: return
            collected.add(msgNum)
            satellites.addAll(gsv.satellites)
            // When collected.size == expected, we have a complete set.
        }

        fun current(): Pair<Gsv, List<Satellite>>? = summary?.let { it to satellites.toList() }
    }

    // ---------------- Public entry points ----------------

    /** Simple parse: returns a typed sentence or null if dropped. */
    fun parse(lineRaw: String): NmeaSentence? =
        (parseDetailed(lineRaw) as? ParseResult.Success)?.sentence

    /** Detailed parse: returns Success or Dropped(reason). */
    fun parseDetailed(lineRaw: String): ParseResult {
        val line = lineRaw.trim()
        if (line.isEmpty()) return ParseResult.Dropped(ParseResult.Reason.TOO_SHORT)
        if (line[0] != '$') return ParseResult.Dropped(ParseResult.Reason.NO_START)
        if (line.length < 9) return ParseResult.Dropped(ParseResult.Reason.TOO_SHORT)

        // Split body and checksum: $BODY*CS
        val star = line.indexOf('*')
        if (star < 0 || star + 3 > line.length) return ParseResult.Dropped(ParseResult.Reason.BAD_CHECKSUM)
        val body = line.substring(1, star)
        val checksum = line.substring(star + 1, star + 3)

        // Verify XOR checksum
        val calc = body.fold(0) { acc, c -> acc xor c.code }
        val calcHex = calc.toString(16).uppercase().padStart(2, '0')
        if (calcHex != checksum.uppercase()) return ParseResult.Dropped(ParseResult.Reason.BAD_CHECKSUM)

        // body looks like: "GPGGA,...." or "GNGSV,...."
        val parts = body.split(',')
        val type = parts[0]

        val sentence: NmeaSentence? = when (type) {
            "GPGGA", "GNGGA", "GAGGA", "GLGGA", "GBGGA" ->
                parseGga(parts)?.let { NmeaSentence.GgaSentence(it) }

            "GPRMC", "GNRMC", "GARMC", "GLRMC", "GBRMC" ->
                parseRmc(parts)?.let { NmeaSentence.RmcSentence(it) }
                    ?: return ParseResult.Dropped(ParseResult.Reason.INACTIVE_STATUS)

            // For GSV, detect constellation from the talker/sentence type
            "GPGSV", "GLGSV", "GAGSV", "GBGSV", "GQGSV", "GNGSV" ->
                parseGsv(parts, constellationFromType(type))?.let { NmeaSentence.GsvSentence(it) }

            "GPGSA", "GNGSA", "GLGSA", "GAGSA", "GBGSA" ->
                parseGsa(parts)?.let { NmeaSentence.GsaSentence(it) }

            "GPGST", "GNGST", "GAGST", "GLGST", "GBGST" ->
                parseGst(parts)?.let { NmeaSentence.GstSentence(it) }

            "GPZDA", "GNZDA", "GAZDA", "GLZDA", "GBZDA" ->
                parseZda(parts)?.let { NmeaSentence.ZdaSentence(it) }

            else -> null
        }

        return sentence?.let { ParseResult.Success(it) } ?: ParseResult.Dropped(ParseResult.Reason.UNSUPPORTED)
    }

    // ---------------- Sentence parsers ----------------

    private fun parseGga(p: List<String>): Gga? {
        if (p.size < 15) return null
        val timeRaw = p[1].ifBlank { null }
        val lat = decodeLat(p[2], p.getOrNull(3))
        val lon = decodeLon(p[4], p.getOrNull(5))
        val fixQ = p[6].toIntOrNull()
        val sats = p[7].toIntOrNull()
        val hdop = p[8].toDoubleOrNull()
        val altMsl = p[9].toDoubleOrNull()                // MSL height (meters)
        // p[10] is the unit ("M"), ignored
        val geoidSep = p.getOrNull(11)?.toDoubleOrNull()  // geoid separation N (meters)
        // p[12] is the unit ("M"), ignored
        val diffAge = p.getOrNull(13)?.toDoubleOrNull()
        val stationId = p.getOrNull(14)?.ifBlank { null }
        return Gga(timeRaw, lat, lon, fixQ, sats, hdop, altMsl, geoidSep, diffAge, stationId)
    }

    private fun parseRmc(p: List<String>): Rmc? {
        if (p.size < 12) return null
        val status = p[2] // A = valid, V = void
        if (status != "A") return null
        val timeRaw = p[1].ifBlank { null }
        val lat = decodeLat(p[3], p.getOrNull(4))
        val lon = decodeLon(p[5], p.getOrNull(6))
        val speed = p[7].toDoubleOrNull()        // knots
        val course = p[8].toDoubleOrNull()       // degrees
        val date = p[9].ifBlank { null }         // DDMMYY
        return Rmc(timeRaw, date, lat, lon, speed, course)
    }

    private fun parseGsv(p: List<String>, constellation: Constellation): Gsv? {
        if (p.size < 4) return null
        val total = p[1].toIntOrNull()
        val num = p[2].toIntOrNull()
        val inView = p[3].toIntOrNull()

        // Each satellite block is 4 fields: PRN, elevation, azimuth, SNR
        val satList = mutableListOf<Satellite>()
        var idx = 4
        while (idx + 3 < p.size) {
            val prn = p[idx].toIntOrNull()
            val elev = p[idx + 1].toIntOrNull()
            val az = p[idx + 2].toIntOrNull()
            val snr = p[idx + 3].toIntOrNull()
            if (prn != null) {
                satList.add(Satellite(prn, constellation, elev, az, snr))
            }
            idx += 4
        }
        return Gsv(total, num, inView, satList)
    }

    private fun parseGsa(p: List<String>): Gsa? {
        if (p.size < 18) return null
        val pdop = p[15].toDoubleOrNull()
        val hdop = p[16].toDoubleOrNull()
        val vdop = p.getOrNull(17)?.toDoubleOrNull()
        return Gsa(pdop, hdop, vdop)
    }

    private fun parseGst(p: List<String>): Gst? {
        if (p.size < 9) return null
        val timeRaw = p[1].ifBlank { null }
        val rms = p[2].toDoubleOrNull()
        val semiMajor = p[3].toDoubleOrNull()
        val semiMinor = p[4].toDoubleOrNull()
        val orient = p[5].toDoubleOrNull()
        val latStd = p[6].toDoubleOrNull()
        val lonStd = p[7].toDoubleOrNull()
        val altStd = p[8].toDoubleOrNull()
        return Gst(timeRaw, rms, semiMajor, semiMinor, orient, latStd, lonStd, altStd)
    }

    private fun parseZda(p: List<String>): Zda? {
        if (p.size < 7) return null
        val timeRaw = p[1].ifBlank { null }
        val day = p[2].toIntOrNull()
        val month = p[3].toIntOrNull()
        val year = p[4].toIntOrNull()
        return Zda(timeRaw, day, month, year)
    }

    // ---------------- Small helpers ----------------

    /** Latitude decoder: input "ddmm.mmmm" + hemisphere ("N"/"S"). */
    private fun decodeLat(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = raw.indexOf('.')
        if (dot <= 0) return null
        // For latitude, degrees = first 2 chars (dd)
        val degPart = raw.substring(0, 2)
        val minPart = raw.substring(2)
        val deg = degPart.toDoubleOrNull() ?: return null
        val min = minPart.toDoubleOrNull() ?: return null
        var value = deg + min / 60.0
        if (hemi.equals("S", true)) value = -value
        if (value !in -90.0..90.0) return null
        return value
    }

    /** Longitude decoder: input "dddmm.mmmm" + hemisphere ("E"/"W"). */
    private fun decodeLon(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = raw.indexOf('.')
        if (dot <= 0) return null
        // For longitude, degrees = first 3 chars (ddd)
        val degPart = raw.substring(0, 3)
        val minPart = raw.substring(3)
        val deg = degPart.toDoubleOrNull() ?: return null
        val min = minPart.toDoubleOrNull() ?: return null
        var value = deg + min / 60.0
        if (hemi.equals("W", true)) value = -value
        if (value !in -180.0..180.0) return null
        return value
    }

    // Added back: map sentence talker prefix to a constellation (used by parseGsv)
    private fun constellationFromType(type: String): Constellation = when {
        type.startsWith("GP") -> Constellation.GPS
        type.startsWith("GL") -> Constellation.GLONASS
        type.startsWith("GA") -> Constellation.GALILEO
        type.startsWith("GB") -> Constellation.BEIDOU
        type.startsWith("GQ") -> Constellation.QZSS
        type.startsWith("GI") -> Constellation.IRNSS
        type.startsWith("GS") -> Constellation.SBAS
        type.startsWith("GN") -> Constellation.UNKNOWN // mixed multi-GNSS
        else -> Constellation.UNKNOWN
    }

    // ---------------- Legacy helper (kept for backward compatibility) ----------------
    companion object {
        /** Parses HHMMSS(.sss) into (hour, minute, secondsWithFraction). */
        private fun parseTime(timeRaw: String?): Triple<Int, Int, Double>? {
            if (timeRaw.isNullOrBlank()) return null
            if (timeRaw.length < 6) return null
            val hh = timeRaw.substring(0, 2).toIntOrNull() ?: return null
            val mm = timeRaw.substring(2, 4).toIntOrNull() ?: return null
            val secStr = timeRaw.substring(4)
            val ss = secStr.toDoubleOrNull() ?: return null
            if (hh !in 0..23 || mm !in 0..59) return null
            return Triple(hh, mm, ss)
        }
        /** Build epoch millis using (time + date) pair with fractional seconds. */
        fun buildEpochMillisPrecise(timeRaw: String?, dateRaw: String?): Long? {
            val t = parseTime(timeRaw) ?: return null
            val date = dateRaw ?: return null
            if (date.length != 6) return null
            val day = date.substring(0, 2).toIntOrNull() ?: return null
            val mon = date.substring(2, 4).toIntOrNull() ?: return null
            val yy = date.substring(4, 6).toIntOrNull() ?: return null
            val year = if (yy >= 80) 1900 + yy else 2000 + yy
            return buildCalendarUtc(year, mon, day, t.first, t.second, t.third)
        }
        /** Build epoch millis from ZDA components. */
        fun buildEpochMillisFromZda(zda: Zda): Long? {
            val t = parseTime(zda.timeRaw) ?: return null
            val year = zda.year ?: return null
            val mon = zda.month ?: return null
            val day = zda.day ?: return null
            return buildCalendarUtc(year, mon, day, t.first, t.second, t.third)
        }
        private fun buildCalendarUtc(year: Int, mon: Int, day: Int, hh: Int, mm: Int, secondsFrac: Double): Long? {
            if (mon !in 1..12 || day !in 1..31) return null
            val ss = secondsFrac.toInt()
            val ms = ((secondsFrac - ss) * 1000.0).toInt()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, mon - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, hh)
            cal.set(Calendar.MINUTE, mm)
            cal.set(Calendar.SECOND, ss)
            cal.set(Calendar.MILLISECOND, ms)
            return cal.timeInMillis
        }
        /** Old RMC epoch builder (seconds truncated). */
        fun buildEpochMillis(rmc: Rmc): Long? {
            val time = rmc.timeRaw?.toDoubleOrNull() ?: return null
            val dateStr = rmc.date ?: return null
            if (dateStr.length != 6) return null
            val hh = (time / 10000).toInt()
            val mm = ((time / 100) % 100).toInt()
            val ss = (time % 100).toInt()
            val day = dateStr.substring(0, 2).toIntOrNull() ?: return null
            val mon = dateStr.substring(2, 4).toIntOrNull() ?: return null
            val yy = dateStr.substring(4, 6).toIntOrNull() ?: return null
            val year = 2000 + yy
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, mon - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, hh)
            cal.set(Calendar.MINUTE, mm)
            cal.set(Calendar.SECOND, ss)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
