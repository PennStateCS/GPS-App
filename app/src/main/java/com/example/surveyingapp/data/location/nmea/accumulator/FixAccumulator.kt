package com.example.surveyingapp.data.location.nmea.accumulator

import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import kotlin.math.sqrt

/** Snapshot of aggregated NMEA-derived fix state. */
data class FixSnapshot(
    val timestampMillis: Long?,
    val timestampSource: String?,
    val lat: Double?,
    val lon: Double?,
    val altMsl: Double?,
    val geoidSeparation: Double?,
    val altEllipsoidal: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val fixType: NmeaParser.FixType,
    val satsUsed: Int?,
    val satsInView: Int?,
    val satsPerConstellation: Map<NmeaParser.Constellation, Int>,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val diffAgeSec: Double?,
    val diffStale: Boolean,
    val satellites: List<NmeaParser.Satellite>,
    val stationId: String?
)

/** Aggregates parsed NMEA sentences to build higher-level snapshots at sensible cadence. */
class FixAccumulator(private val staleDiffAgeThresholdSec: Double = 5.0) {
    private var lastGga: NmeaParser.Gga? = null
    private var lastRmc: NmeaParser.Rmc? = null
    private var lastGsa: NmeaParser.Gsa? = null
    private var lastGst: NmeaParser.Gst? = null
    private var lastZda: NmeaParser.Zda? = null
    private var gsvBuilder = GsvAggregation()
    private var lastEpochMillis: Long? = null

    fun feed(sentence: NmeaParser.NmeaSentence): FixSnapshot? {
        when (sentence) {
            is NmeaParser.NmeaSentence.GgaSentence -> lastGga = sentence.gga
            is NmeaParser.NmeaSentence.RmcSentence -> lastRmc = sentence.rmc
            is NmeaParser.NmeaSentence.GsaSentence -> lastGsa = sentence.gsa
            is NmeaParser.NmeaSentence.GstSentence -> lastGst = sentence.gst
            is NmeaParser.NmeaSentence.ZdaSentence -> lastZda = sentence.zda
            is NmeaParser.NmeaSentence.GsvSentence -> gsvBuilder.ingest(sentence.gsv)
        }
        return if (sentence is NmeaParser.NmeaSentence.GgaSentence ||
            sentence is NmeaParser.NmeaSentence.RmcSentence ||
            sentence is NmeaParser.NmeaSentence.GstSentence) build() else null
    }

    fun build(): FixSnapshot {
        val gga = lastGga
        val rmc = lastRmc
        val gsa = lastGsa
        val gst = lastGst
        val zda = lastZda
        val gsvData = gsvBuilder.current()
        val fixType = mapFixType(gga?.fixQuality)
        val ts = buildTimestamp(rmc, zda, gga)
        lastEpochMillis = ts.first ?: lastEpochMillis
        val geoid = gga?.geoidSeparation
        val altMsl = gga?.altMsl
        val altEllipsoidal = if (altMsl != null && geoid != null) altMsl + geoid else gga?.ellipsoidalHeight
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
        val speedMps = rmc?.speedKnots?.let { it * 0.514444 }
        return FixSnapshot(
            timestampMillis = lastEpochMillis,
            timestampSource = ts.second,
            lat = gga?.lat ?: rmc?.lat,
            lon = gga?.lon ?: rmc?.lon,
            altMsl = altMsl,
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

    private fun buildTimestamp(rmc: NmeaParser.Rmc?, zda: NmeaParser.Zda?, gga: NmeaParser.Gga?): Pair<Long?, String?> {
        rmc?.let { NmeaParser.buildEpochMillisPrecise(it.timeRaw, it.date)?.let { ts -> return ts to "RMC" } }
        zda?.let { NmeaParser.buildEpochMillisFromZda(it)?.let { ts -> return ts to "ZDA" } }
        gga?.let { NmeaParser.buildEpochMillisPrecise(it.timeRaw, rmc?.date)?.let { ts -> return ts to "GGA" } }
        return null to null
    }

    private fun estimateAccuracy(gst: NmeaParser.Gst?, hdop: Double?, vdop: Double?): Pair<Double?, Double?> {
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

    private fun mapFixType(q: Int?): NmeaParser.FixType = when (q) {
        null -> NmeaParser.FixType.INVALID
        0 -> NmeaParser.FixType.INVALID
        1 -> NmeaParser.FixType.SINGLE
        2 -> NmeaParser.FixType.DGPS
        3 -> NmeaParser.FixType.PPS
        4 -> NmeaParser.FixType.RTK_FIXED
        5 -> NmeaParser.FixType.RTK_FLOAT
        6 -> NmeaParser.FixType.DEAD_RECKONING
        7 -> NmeaParser.FixType.MANUAL
        8 -> NmeaParser.FixType.SIMULATION
        9 -> NmeaParser.FixType.PPP
        else -> NmeaParser.FixType.UNKNOWN
    }

    private class GsvAggregation {
        private var expected: Int = 0
        private var collected = mutableSetOf<Int>()
        private val satellites = mutableListOf<NmeaParser.Satellite>()
        private var summary: NmeaParser.Gsv? = null
        fun ingest(gsv: NmeaParser.Gsv) {
            val total = gsv.totalMsgs ?: return
            if (gsv.msgNum == 1 || total != expected) {
                expected = total
                collected.clear()
                satellites.clear()
            }
            summary = gsv
            val msgNum = gsv.msgNum ?: return
            collected.add(msgNum)
            satellites.addAll(gsv.satellites)
        }
        fun current(): Pair<NmeaParser.Gsv, List<NmeaParser.Satellite>>? = summary?.let { it to satellites.toList() }
    }
}

