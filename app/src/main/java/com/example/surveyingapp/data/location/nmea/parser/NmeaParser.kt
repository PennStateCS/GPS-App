package com.example.surveyingapp.data.location.nmea.parser

import java.util.Calendar
import java.util.TimeZone

class NmeaParser {
    enum class FixType { INVALID, SINGLE, DGPS, PPS, RTK_FIXED, RTK_FLOAT, DEAD_RECKONING, MANUAL, SIMULATION, PPP, UNKNOWN }
    enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS, IRNSS, UNKNOWN }

    data class Satellite(val prn: Int, val constellation: Constellation, val elevationDeg: Int?, val azimuthDeg: Int?, val snrDb: Int?)
    data class Gga(
        val timeRaw: String?, val lat: Double?, val lon: Double?, val fixQuality: Int?, val sats: Int?, val hdop: Double?,
        val altEllipsoidal: Double?, val geoidSeparation: Double?, val diffAgeSec: Double?, val stationId: String?
    ) {
        val alt: Double? get() = altEllipsoidal
        val altMsl: Double? get() = altEllipsoidal
        val ellipsoidalHeight: Double? get() = if (altEllipsoidal != null && geoidSeparation != null) altEllipsoidal + geoidSeparation else null
    }
    data class Rmc(val timeRaw: String?, val date: String?, val lat: Double?, val lon: Double?, val speedKnots: Double?, val course: Double?)
    data class Gst(val timeRaw: String?, val rms: Double?, val semiMajor: Double?, val semiMinor: Double?, val orientation: Double?, val latStd: Double?, val lonStd: Double?, val altStd: Double?)
    data class Zda(val timeRaw: String?, val day: Int?, val month: Int?, val year: Int?)
    data class Gsv(val totalMsgs: Int?, val msgNum: Int?, val satsInView: Int?, val satellites: List<Satellite> = emptyList())
    data class Gsa(val pdop: Double?, val hdop: Double?, val vdop: Double?)

    sealed class ParseResult { data class Success(val sentence: NmeaSentence): ParseResult(); data class Dropped(val reason: Reason): ParseResult() { enum class Reason { TOO_SHORT, NO_START, BAD_CHECKSUM, UNSUPPORTED, MALFORMED, INACTIVE_STATUS } } }
    sealed class NmeaSentence {
        data class GgaSentence(val gga: Gga): NmeaSentence()
        data class RmcSentence(val rmc: Rmc): NmeaSentence()
        data class GsvSentence(val gsv: Gsv): NmeaSentence()
        data class GsaSentence(val gsa: Gsa): NmeaSentence()
        data class GstSentence(val gst: Gst): NmeaSentence()
        data class ZdaSentence(val zda: Zda): NmeaSentence()
    }

    fun parse(lineRaw: String): NmeaSentence? = (parseDetailed(lineRaw) as? ParseResult.Success)?.sentence

    fun parseDetailed(lineRaw: String): ParseResult {
        val line = lineRaw.trim()
        if (line.isEmpty()) return ParseResult.Dropped(ParseResult.Dropped.Reason.TOO_SHORT)
        if (line[0] != '$') return ParseResult.Dropped(ParseResult.Dropped.Reason.NO_START)
        if (line.length < 9) return ParseResult.Dropped(ParseResult.Dropped.Reason.TOO_SHORT)
        val star = line.indexOf('*')
        if (star < 0 || star + 3 > line.length) return ParseResult.Dropped(ParseResult.Dropped.Reason.BAD_CHECKSUM)
        val body = line.substring(1, star)
        val checksum = line.substring(star + 1, star + 3)
        val calc = body.fold(0) { acc, c -> acc xor c.code }
        val calcHex = calc.toString(16).uppercase().padStart(2,'0')
        if (calcHex != checksum.uppercase()) return ParseResult.Dropped(ParseResult.Dropped.Reason.BAD_CHECKSUM)
        val parts = body.split(',')
        val type = parts[0]
        val sentence: NmeaSentence? = when (type) {
            "GPGGA","GNGGA","GAGGA","GLGGA","GBGGA" -> parseGga(parts)?.let { NmeaSentence.GgaSentence(it) }
            "GPRMC","GNRMC","GARMC","GLRMC","GBRMC" -> parseRmc(parts)?.let { NmeaSentence.RmcSentence(it) } ?: return ParseResult.Dropped(ParseResult.Dropped.Reason.INACTIVE_STATUS)
            "GPGSV","GLGSV","GAGSV","GBGSV","GQGSV","GNGSV" -> parseGsv(parts, constellationFromType(type))?.let { NmeaSentence.GsvSentence(it) }
            "GPGSA","GNGSA","GLGSA","GAGSA","GBGSA" -> parseGsa(parts)?.let { NmeaSentence.GsaSentence(it) }
            "GPGST","GNGST","GAGST","GLGST","GBGST" -> parseGst(parts)?.let { NmeaSentence.GstSentence(it) }
            "GPZDA","GNZDA","GAZDA","GLZDA","GBZDA" -> parseZda(parts)?.let { NmeaSentence.ZdaSentence(it) }
            else -> null
        }
        return sentence?.let { ParseResult.Success(it) } ?: ParseResult.Dropped(ParseResult.Dropped.Reason.UNSUPPORTED)
    }

    private fun parseGga(p: List<String>): Gga? {
        if (p.size < 15) return null
        val timeRaw = p[1].ifBlank { null }
        val lat = decodeLat(p[2], p.getOrNull(3))
        val lon = decodeLon(p[4], p.getOrNull(5))
        val fixQ = p[6].toIntOrNull()
        val sats = p[7].toIntOrNull()
        val hdop = p[8].toDoubleOrNull()
        val altMsl = p[9].toDoubleOrNull()
        val geoidSep = p.getOrNull(11)?.toDoubleOrNull()
        val diffAge = p.getOrNull(13)?.toDoubleOrNull()
        val stationId = p.getOrNull(14)?.ifBlank { null }
        return Gga(timeRaw, lat, lon, fixQ, sats, hdop, altMsl, geoidSep, diffAge, stationId)
    }
    private fun parseRmc(p: List<String>): Rmc? {
        if (p.size < 12) return null
        if (p[2] != "A") return null
        val timeRaw = p[1].ifBlank { null }
        val lat = decodeLat(p[3], p.getOrNull(4))
        val lon = decodeLon(p[5], p.getOrNull(6))
        val speed = p[7].toDoubleOrNull()
        val course = p[8].toDoubleOrNull()
        val date = p[9].ifBlank { null }
        return Rmc(timeRaw, date, lat, lon, speed, course)
    }
    private fun parseGsv(p: List<String>, constellation: Constellation): Gsv? {
        if (p.size < 4) return null
        val total = p[1].toIntOrNull()
        val num = p[2].toIntOrNull()
        val inView = p[3].toIntOrNull()
        val satList = mutableListOf<Satellite>()
        var idx = 4
        while (idx + 3 < p.size) {
            val prn = p[idx].toIntOrNull()
            val elev = p[idx+1].toIntOrNull()
            val az = p[idx+2].toIntOrNull()
            val snr = p[idx+3].toIntOrNull()
            if (prn != null) satList.add(Satellite(prn,constellation,elev,az,snr))
            idx += 4
        }
        return Gsv(total,num,inView,satList)
    }
    private fun parseGsa(p: List<String>): Gsa? { if (p.size < 18) return null; return Gsa(p[15].toDoubleOrNull(), p[16].toDoubleOrNull(), p.getOrNull(17)?.toDoubleOrNull()) }
    private fun parseGst(p: List<String>): Gst? { if (p.size < 9) return null; return Gst(p[1].ifBlank{null}, p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(), p[5].toDoubleOrNull(), p[6].toDoubleOrNull(), p[7].toDoubleOrNull(), p[8].toDoubleOrNull()) }
    private fun parseZda(p: List<String>): Zda? { if (p.size < 7) return null; return Zda(p[1].ifBlank{null}, p[2].toIntOrNull(), p[3].toIntOrNull(), p[4].toIntOrNull()) }

    private fun decodeLat(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val deg = raw.substring(0,2).toDoubleOrNull() ?: return null
        val min = raw.substring(2).toDoubleOrNull() ?: return null
        var v = deg + min/60.0
        if (hemi.equals("S", true)) v = -v
        if (v !in -90.0..90.0) return null
        return v
    }
    private fun decodeLon(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val deg = raw.substring(0,3).toDoubleOrNull() ?: return null
        val min = raw.substring(3).toDoubleOrNull() ?: return null
        var v = deg + min/60.0
        if (hemi.equals("W", true)) v = -v
        if (v !in -180.0..180.0) return null
        return v
    }
    private fun constellationFromType(type: String): Constellation = when {
        type.startsWith("GP") -> Constellation.GPS
        type.startsWith("GL") -> Constellation.GLONASS
        type.startsWith("GA") -> Constellation.GALILEO
        type.startsWith("GB") -> Constellation.BEIDOU
        type.startsWith("GQ") -> Constellation.QZSS
        type.startsWith("GI") -> Constellation.IRNSS
        type.startsWith("GS") -> Constellation.SBAS
        type.startsWith("GN") -> Constellation.UNKNOWN
        else -> Constellation.UNKNOWN
    }

    companion object TimeUtil {
        private fun parseTime(timeRaw: String?): Triple<Int, Int, Double>? {
            if (timeRaw.isNullOrBlank() || timeRaw.length < 6) return null
            val hh = timeRaw.substring(0,2).toIntOrNull() ?: return null
            val mm = timeRaw.substring(2,4).toIntOrNull() ?: return null
            val ss = timeRaw.substring(4).toDoubleOrNull() ?: return null
            if (hh !in 0..23 || mm !in 0..59) return null
            return Triple(hh,mm,ss)
        }
        fun buildEpochMillisPrecise(timeRaw: String?, dateRaw: String?): Long? {
            val t = parseTime(timeRaw) ?: return null
            val date = dateRaw ?: return null
            if (date.length != 6) return null
            val day = date.substring(0,2).toIntOrNull() ?: return null
            val mon = date.substring(2,4).toIntOrNull() ?: return null
            val yy = date.substring(4,6).toIntOrNull() ?: return null
            val year = if (yy >= 80) 1900 + yy else 2000 + yy
            return buildCalendarUtc(year, mon, day, t.first, t.second, t.third)
        }
        fun buildEpochMillisFromZda(z: Zda): Long? {
            val t = parseTime(z.timeRaw) ?: return null
            val year = z.year ?: return null; val mon = z.month ?: return null; val day = z.day ?: return null
            return buildCalendarUtc(year, mon, day, t.first, t.second, t.third)
        }
        private fun buildCalendarUtc(year: Int, mon: Int, day: Int, hh: Int, mm: Int, secondsFrac: Double): Long? {
            if (mon !in 1..12 || day !in 1..31) return null
            val ss = secondsFrac.toInt(); val ms = ((secondsFrac - ss) * 1000.0).toInt()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.YEAR, year); cal.set(Calendar.MONTH, mon - 1); cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm); cal.set(Calendar.SECOND, ss); cal.set(Calendar.MILLISECOND, ms)
            return cal.timeInMillis
        }
    }
}

