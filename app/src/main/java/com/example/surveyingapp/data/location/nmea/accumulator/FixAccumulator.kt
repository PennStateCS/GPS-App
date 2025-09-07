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
    /** Ellipsoidal height if provided by receiver; else derived as MSL + geoid. */
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
    /** Horizontal σ and Vertical σ (meters). Prefer GST; else DOP×UERE. */
    val hAccM: Double?,
    val vAccM: Double?,
    /** Age of corrections in seconds, if provided. */
    val diffAgeSec: Double?,
    /** Whether diff age is beyond staleness threshold. */
    val diffStale: Boolean,
    /** Latest inventory of satellites (deduped by (constellation, PRN)). */
    val satellites: List<NmeaParser.Satellite>,
    /** Base station ID, when present (GGA). */
    val stationId: String?,
    /** PRNs used in solution (from GSA). */
    val usedSvids: List<Int>? = null,
    // Per-axis standard deviations from GST (meters)
    val stdLatM: Double? = null,
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,
)

/** Aggregates parsed NMEA sentences to build higher-level snapshots at sensible cadence. */
class FixAccumulator(
    private val staleDiffAgeThresholdSec: Double = 5.0,
    /**
     * If true, GSV completion means “all expected constellations completed this cycle”.
     * If false, GSV completion means “any constellation completed” (more lively UI).
     */
    private val requireFullCycle: Boolean = false,
    /** Minimum ms between sky-only emissions (GSV-driven). */
    private val skyEmitIntervalMs: Long = 1200L,
) {
    private var lastGga: NmeaParser.Gga? = null
    private var lastRmc: NmeaParser.Rmc? = null
    private var lastGsa: NmeaParser.Gsa? = null
    private var lastGst: NmeaParser.Gst? = null
    private var lastZda: NmeaParser.Zda? = null
    private var gsvBuilder = GsvAggregation(requireFullCycle)
    private var lastEpochMillis: Long? = null

    // Emission throttling + duplicate suppression
    private var lastEmitAtMs: Long = 0L
    private var lastEmitHash: Int? = null

    private val lock = Any()

    /**
     * Return a snapshot when:
     *  - GGA / RMC / GST arrives (has positioning/quality info), OR
     *  - We just completed a GSV cycle (per requireFullCycle), OR
     *  - Throttle interval elapsed AND sky changed (no-change guard).
     */
    fun feed(sentence: NmeaParser.NmeaSentence): FixSnapshot? = synchronized(lock) {
        when (sentence) {
            is NmeaParser.NmeaSentence.GgaSentence -> lastGga = sentence.gga
            is NmeaParser.NmeaSentence.RmcSentence -> lastRmc = sentence.rmc
            is NmeaParser.NmeaSentence.GsaSentence -> lastGsa = sentence.gsa
            is NmeaParser.NmeaSentence.GstSentence -> lastGst = sentence.gst
            is NmeaParser.NmeaSentence.ZdaSentence -> lastZda = sentence.zda
            is NmeaParser.NmeaSentence.GsvSentence -> {
                val completed = gsvBuilder.ingest(sentence.gsv)
                val now = System.currentTimeMillis()

                val timeReady = now - lastEmitAtMs >= skyEmitIntervalMs
                if (completed || timeReady) {
                    val snap = build()
                    val sig = signatureOf(snap.satellites)
                    val changed = lastEmitHash != sig
                    val shouldEmit = completed || changed
                    if (shouldEmit) {
                        lastEmitHash = sig
                        lastEmitAtMs = now
                        return@synchronized snap
                    } else {
                        // android.util.Log.d("GSV_DEBUG", "Emission suppressed: no change (hash=$sig)")
                    }
                }
                return@synchronized null
            }
        }
        // For GGA / RMC / GST, emit immediately (these materially change the solution)
        return@synchronized if (
            sentence is NmeaParser.NmeaSentence.GgaSentence ||
            sentence is NmeaParser.NmeaSentence.RmcSentence ||
            sentence is NmeaParser.NmeaSentence.GstSentence
        ) build() else null
    }

    private fun build(): FixSnapshot {
        val gga = lastGga
        val rmc = lastRmc
        val gsa = lastGsa
        val gst = lastGst
        val zda = lastZda
        val gsvData = gsvBuilder.current()

        val fixType = mapFixType(gga?.fixQuality)
        val (epoch, sourceTag) = buildTimestamp(rmc, zda, gga)
        // Persist last known good epoch when we don't get time on this cycle
        lastEpochMillis = epoch ?: lastEpochMillis

        val geoid = gga?.geoidSeparation
        val altMsl = gga?.altMsl
        // Prefer direct ellipsoidal height; else derive from MSL + geoid (never mix MSL into ellipsoidal)
        val altEllipsoidal = gga?.ellipsoidalHeight ?: altMsl?.let { msl ->
            geoid?.let { msl + it }
        }

        val diffAge = gga?.diffAgeSec
        val diffStale = diffAge != null && diffAge > staleDiffAgeThresholdSec

        val satellites = gsvData?.second ?: emptyList()
        val satsPerConstellation = satellites.groupingBy { it.constellation }.eachCount()
        val satsInView = satellites.size.takeIf { it > 0 } ?: gsvData?.first?.satsInView
        val satsUsed = gga?.sats

        val pdop = lastGsa?.pdop
        val hdop = lastGsa?.hdop ?: gga?.hdop
        val vdop = lastGsa?.vdop
        val (hAcc, vAcc) = estimateAccuracy(gst, hdop, vdop)

        // RMC speed is in knots
        val speedMps = rmc?.speedKnots?.let { it * 0.514444 }

        return FixSnapshot(
            timestampMillis = lastEpochMillis,
            timestampSource = sourceTag,
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
            stationId = gga?.stationId,
            usedSvids = gsa?.usedSvids,
            stdLatM = gst?.latStd,
            stdLonM = gst?.lonStd,
            stdAltM = gst?.altStd,
        )
    }

    private fun buildTimestamp(
        rmc: NmeaParser.Rmc?,
        zda: NmeaParser.Zda?,
        gga: NmeaParser.Gga?
    ): Pair<Long?, String?> {
        // Prefer RMC (has date+time)
        rmc?.let { NmeaParser.buildEpochMillisPrecise(it.timeRaw, it.date)?.let { ts -> return ts to "RMC" } }
        // ZDA has date+time too
        zda?.let { NmeaParser.buildEpochMillisFromZda(it)?.let { ts -> return ts to "ZDA" } }
        // GGA time + RMC date (if available)
        gga?.let { NmeaParser.buildEpochMillisPrecise(it.timeRaw, rmc?.date)?.let { ts -> return ts to "GGA" } }
        return null to null
    }

    private fun estimateAccuracy(
        gst: NmeaParser.Gst?,
        hdop: Double?,
        vdop: Double?
    ): Pair<Double?, Double?> {
        // Prefer GST per-axis σ (already meters). Combine lat/lon σ to horizontal σ.
        gst?.let {
            val h = if (it.latStd != null && it.lonStd != null)
                sqrt(it.latStd * it.latStd + it.lonStd * it.lonStd) else null
            val v = it.altStd
            return h to v
        }
        // Fallback: DOP × UERE (assume 0.6 m nominal UERE for quality receivers)
        val baseUere = 0.6
        val h = hdop?.let { it * baseUere }
        val v = vdop?.let { it * baseUere }
        return h to v
    }

    private fun mapFixType(q: Int?): NmeaParser.FixType = when (q) {
        null, 0 -> NmeaParser.FixType.INVALID
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

    /** Build a stable signature of the current sky to suppress duplicate UI emissions. */
    private fun signatureOf(sats: List<NmeaParser.Satellite>): Int {
        fun roundSnr(s: Int?): Int = s ?: -1
        val key = buildString {
            sats.sortedWith(
                compareBy<NmeaParser.Satellite>({ it.constellation.ordinal }, { it.prn })
            ).forEach { sat ->
                append(sat.constellation.ordinal)
                append(':')
                append(sat.prn)
                append(':')
                append(roundSnr(sat.snrDb))
                append('|')
            }
        }
        return key.hashCode()
    }

    /**
     * Aggregates multi-constellation, multi-message GSV blocks.
     * Detects completion of a "cycle" per talker/constellation by observing msgNum/totalMsgs
     * and synthesizes a combined satellite list across constellations.
     */
    private class GsvAggregation(
        private val requireFullCycle: Boolean
    ) {
        // Track per-constellation sets and message progress (double-buffered)
        private val constellationData = mutableMapOf<NmeaParser.Constellation, ConstellationGsvSet>()
        private var lastSummary: NmeaParser.Gsv? = null
        private var lastCycleCompletedAtMs: Long = 0L

        // Constellations we expect; will auto-prune if never seen for a while
        private val expectedTalkers = linkedSetOf(
            NmeaParser.Constellation.GPS,
            NmeaParser.Constellation.GLONASS,
            NmeaParser.Constellation.GALILEO,
            NmeaParser.Constellation.BEIDOU
        )

        private data class ConstellationGsvSet(
            // Stable set shown to UI (from last completed cycle)
            val currentSatellites: MutableSet<NmeaParser.Satellite> = mutableSetOf(),
            // Building set for the in-progress cycle
            val buildingSatellites: MutableSet<NmeaParser.Satellite> = mutableSetOf(),
            var buildingLastMsgNum: Int = 0,
            var buildingTotalMsgs: Int = 0,
            var lastUpdated: Long = System.currentTimeMillis()
        )

        /**
         * @return true when a GSV set completes (definition controlled by [requireFullCycle]).
         * Handles nullable msgNum/totalMsgs from the parser.
         */
        @Synchronized
        fun ingest(gsv: NmeaParser.Gsv): Boolean {
            lastSummary = gsv

            val msgNum = gsv.msgNum ?: 0
            val total = gsv.totalMsgs ?: 0

            val primaryConstellation: NmeaParser.Constellation = run {
                // prefer explicit talkerConstellation if your model supports it
                inferConstellationFromSatList(gsv.satellites)
            }

            if (primaryConstellation != NmeaParser.Constellation.UNKNOWN) {
                expectedTalkers.add(primaryConstellation)
                val set = constellationData.getOrPut(primaryConstellation) { ConstellationGsvSet() }

                // --- progress update EVEN IF there are zero satellites in this sentence ---
                if (msgNum == 1) {
                    set.buildingLastMsgNum = 1
                    set.buildingTotalMsgs = total
                    set.buildingSatellites.clear()
                } else {
                    if (msgNum > 0) set.buildingLastMsgNum = maxOf(set.buildingLastMsgNum, msgNum)
                    if (total > 0) set.buildingTotalMsgs = maxOf(set.buildingTotalMsgs, total)
                }
                set.lastUpdated = System.currentTimeMillis()

                // Add satellites (dedupe by PRN within constellation)
                gsv.satellites.forEach { satellite ->
                    val satConst = normalizeConstellation(satellite)
                    if (satConst == primaryConstellation) {
                        set.buildingSatellites.removeIf { it.prn == satellite.prn }
                        set.buildingSatellites.add(satellite.copy(constellation = satConst))
                    }
                }

                // If cycle completed for this constellation, swap building -> current
                if (set.buildingTotalMsgs > 0 && set.buildingLastMsgNum >= set.buildingTotalMsgs) {
                    set.currentSatellites.clear()
                    set.currentSatellites.addAll(set.buildingSatellites)
                }
            }

            // Defensive harvest for sats with known constellation even if talker unknown
            gsv.satellites.forEach { satellite ->
                val satConst = normalizeConstellation(satellite)
                if (satConst != NmeaParser.Constellation.UNKNOWN) {
                    val set = constellationData.getOrPut(satConst) { ConstellationGsvSet() }
                    set.buildingSatellites.removeIf { it.prn == satellite.prn }
                    set.buildingSatellites.add(satellite.copy(constellation = satConst))
                    set.lastUpdated = System.currentTimeMillis()
                }
            }

            // Decide if a “cycle completion” has occurred
            val completedNow = isCycleComplete(requireFullCycle)

            // Clean up stale constellations (older than 5 minutes)
            val now = System.currentTimeMillis()
            val staleThreshold = 300_000L
            constellationData.values.forEach { set ->
                if (now - set.lastUpdated > staleThreshold) {
                    set.currentSatellites.clear()
                    set.buildingSatellites.clear()
                    set.buildingLastMsgNum = 0
                    set.buildingTotalMsgs = 0
                }
            }
            expectedTalkers.retainAll(constellationData.keys)

            return completedNow
        }

        @Synchronized
        private fun isCycleComplete(requireFull: Boolean): Boolean {
            val anyComplete = constellationData.values.any { s ->
                s.buildingTotalMsgs > 0 && s.buildingLastMsgNum >= s.buildingTotalMsgs
            }
            val fullCycle = expectedTalkers.isNotEmpty() && expectedTalkers.all { c ->
                constellationData[c]?.let { s ->
                    s.buildingTotalMsgs > 0 && s.buildingLastMsgNum >= s.buildingTotalMsgs
                } == true
            }

            val trigger = if (requireFull) fullCycle else anyComplete
            if (!trigger) return false

            val now = System.currentTimeMillis()
            val ok = now - lastCycleCompletedAtMs > 300 // debounce rapid repeats
            if (ok) lastCycleCompletedAtMs = now
            return ok
        }

        @Synchronized
        fun current(): Pair<NmeaParser.Gsv, List<NmeaParser.Satellite>>? {
            val last = lastSummary ?: return null

            // Pair-keyed map prevents cross-constellation PRN collisions
            val allSatellites = mutableMapOf<Pair<NmeaParser.Constellation, Int>, NmeaParser.Satellite>()

            constellationData.values.forEach { set ->
                // Add satellites from current (completed) set first
                set.currentSatellites.forEach { sat ->
                    val key = sat.constellation to sat.prn
                    allSatellites[key] = sat
                }
                // Prefer most recent (building) over current
                set.buildingSatellites.forEach { sat ->
                    val key = sat.constellation to sat.prn
                    allSatellites[key] = sat
                }
            }

            val satelliteList = allSatellites.values.toList()

            if (satelliteList.isNotEmpty()) {
                android.util.Log.d("GSV_DEBUG", "Total satellites collected: ${satelliteList.size}")
                satelliteList.groupBy { it.constellation }.forEach { (constellation, sats) ->
                    android.util.Log.d("GSV_DEBUG", "$constellation: ${sats.size} satellites")
                }
            }

            val synthetic = NmeaParser.Gsv(
                totalMsgs = last.totalMsgs ?: 0,
                msgNum = last.msgNum ?: 0,
                satsInView = satelliteList.size,
                satellites = emptyList()
            )
            return synthetic to satelliteList
        }

        private fun normalizeConstellation(satellite: NmeaParser.Satellite): NmeaParser.Constellation {
            if (satellite.constellation != NmeaParser.Constellation.UNKNOWN) return satellite.constellation
            // Fallback to PRN range inference
            return when (satellite.prn) {
                in 1..32    -> NmeaParser.Constellation.GPS
                in 33..64   -> NmeaParser.Constellation.SBAS
                in 65..96   -> NmeaParser.Constellation.GLONASS
                in 193..200 -> NmeaParser.Constellation.QZSS
                in 201..237 -> NmeaParser.Constellation.BEIDOU
                in 301..336 -> NmeaParser.Constellation.GALILEO
                in 401..437 -> NmeaParser.Constellation.BEIDOU
                else        -> NmeaParser.Constellation.UNKNOWN
            }
        }

        private fun inferConstellationFromSatList(sats: List<NmeaParser.Satellite>): NmeaParser.Constellation {
            if (sats.isEmpty()) return NmeaParser.Constellation.UNKNOWN
            val counts = sats.groupingBy { normalizeConstellation(it) }.eachCount()
            val best = counts
                .filterKeys { it != NmeaParser.Constellation.UNKNOWN }
                .maxByOrNull { it.value }
                ?.key
            return best ?: NmeaParser.Constellation.UNKNOWN
        }
    }
}
