package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.Locale

/**
 * Turns the shared fix stream into a few GNSS log lines (no coordinates, ever):
 *  - RTK status transitions (from → to, sats used, HDOP, correction-age bucket)
 *  - correction age crossing 5 s / 10 s / 30 s, and its recovery
 *  - source switches
 *  - a heartbeat line (built on request, the caller logs it every 60 s)
 * and keeps per-session stats for gnss-stats.txt. Per fix it does a few comparisons; it writes only
 * on a change. Thread-safe (the fix collector and the heartbeat timer run on different threads).
 */
class GnssFeedTracker(private val sink: (level: Char, message: String) -> Unit) {

    enum class CorrectionBucket(val label: String, val minS: Double) {
        OK("<5s", 0.0), OVER_5(">5s", 5.0), OVER_10(">10s", 10.0), OVER_30(">30s", 30.0);
        companion object {
            fun of(ageS: Double?): CorrectionBucket? = when {
                ageS == null || !ageS.isFinite() -> null
                ageS >= 30.0 -> OVER_30; ageS >= 10.0 -> OVER_10; ageS >= 5.0 -> OVER_5; else -> OK
            }
        }
    }

    data class Stats(
        val timeInStatusMs: Map<RtkStatus, Long>, val rtkDrops: Int, val longestCorrectionAgeS: Double?,
        val sourceSwitches: Int, val fixes: Long,
    )

    private val lock = Any()
    private var source: String? = null
    private var sourceSwitches = 0
    private var lastRtk: RtkStatus? = null
    private var rtkSinceMs = 0L
    private val timeInStatus = EnumMap<RtkStatus, Long>(RtkStatus::class.java)
    private var rtkDrops = 0
    private var corrBucket: CorrectionBucket? = null
    private var longestCorrAgeS: Double? = null
    private var lastFix: Fix? = null
    private var lastFixAtMs = 0L
    private val fixTimes = ArrayDeque<Long>()
    private var fixCount = 0L
    private var lastNmeaAtMs = 0L
    private var skyUsed: Int? = null
    private var skyVisible: Int? = null

    fun onSource(name: String, nowMs: Long) = synchronized(lock) {
        val old = source
        if (old == name) return@synchronized
        source = name
        if (old != null) { sourceSwitches++; sink('I', "Source $old → $name") }
        closeStatus(nowMs)
        lastRtk = null; corrBucket = null; lastFix = null; fixTimes.clear()
        skyUsed = null; skyVisible = null
    }

    fun onFix(fix: Fix, nowMs: Long) = synchronized(lock) {
        fixCount++
        lastFix = fix; lastFixAtMs = nowMs
        fixTimes.addLast(nowMs)
        while (fixTimes.isNotEmpty() && nowMs - fixTimes.first > 60_000L) fixTimes.removeFirst()

        val diffAge = fix.diffAgeS
        val bucket = CorrectionBucket.of(diffAge)
        if (diffAge != null && diffAge.isFinite()) longestCorrAgeS = maxOf(longestCorrAgeS ?: 0.0, diffAge)

        val rtk = fix.rtkStatus
        if (rtk != lastRtk) {
            val from = lastRtk
            closeStatus(nowMs)
            lastRtk = rtk; rtkSinceMs = nowMs
            val drop = from == RtkStatus.FIX
            if (drop) rtkDrops++
            sink(if (drop) 'W' else 'I', "RTK ${from?.name ?: "start"} → ${rtk.name} sats=${fix.satsUsed ?: "?"}" +
                " hdop=${fmt1(fix.hDop)} corrAge=${bucket?.label ?: "none"}")
        }

        if (bucket != null && bucket != corrBucket) {
            val before = corrBucket
            corrBucket = bucket
            when {
                bucket == CorrectionBucket.OK && before != null ->
                    sink('I', "Correction age recovered (${fmt1(diffAge)} s)")
                bucket != CorrectionBucket.OK && (before == null || bucket.minS > before.minS) ->
                    sink('W', "Correction age over ${bucket.minS.toInt()} s (${fmt1(diffAge)} s)")
            }
        }
    }

    fun onSky(used: Int, visible: Int) = synchronized(lock) { skyUsed = used; skyVisible = visible }

    fun onNmea(nowMs: Long) { lastNmeaAtMs = nowMs }   // one field write per line, nothing else

    /** The 60 s heartbeat line (no coordinates). */
    fun heartbeat(nowMs: Long): String = synchronized(lock) {
        val f = lastFix?.takeIf { nowMs - lastFixAtMs <= 60_000L }
        while (fixTimes.isNotEmpty() && nowMs - fixTimes.first > 60_000L) fixTimes.removeFirst()
        val used = skyUsed?.takeIf { it > 0 } ?: f?.satsUsed
        val vis = skyVisible?.takeIf { it > 0 } ?: f?.satsVisible ?: used
        val nmea = if (lastNmeaAtMs == 0L) "never" else "${fmt1((nowMs - lastNmeaAtMs) / 1000.0)}s"
        "Heartbeat source=${source ?: "none"} rtk=${f?.rtkStatus?.name ?: "none"} sats=${used ?: "?"}/${vis ?: "?"}" +
            " hdop=${fmt1(f?.hDop)} hAcc=${hAccBucket(f?.hAccM)} corrAge=${f?.diffAgeS?.let { "${fmt1(it)}s" } ?: "none"}" +
            " fixes/s=${String.format(Locale.US, "%.1f", fixTimes.size / 60.0)} lastNmea=$nmea"
    }

    fun stats(nowMs: Long): Stats = synchronized(lock) {
        val times = EnumMap(timeInStatus)
        lastRtk?.let { times[it] = (times[it] ?: 0L) + (nowMs - rtkSinceMs) }
        Stats(times, rtkDrops, longestCorrAgeS, sourceSwitches, fixCount)
    }

    fun currentRtk(): RtkStatus? = synchronized(lock) { lastRtk }
    fun currentCorrectionAgeS(): Double? = synchronized(lock) { lastFix?.diffAgeS }
    fun currentSource(): String? = synchronized(lock) { source }

    private fun closeStatus(nowMs: Long) {
        val r = lastRtk ?: return
        timeInStatus[r] = (timeInStatus[r] ?: 0L) + (nowMs - rtkSinceMs)
    }

    companion object {
        fun hAccBucket(m: Double?): String = when {
            m == null || !m.isFinite() -> "?"
            m < 0.02 -> "<2cm"; m < 0.05 -> "<5cm"; m < 0.10 -> "<10cm"; m < 0.30 -> "<30cm"
            m < 1.0 -> "<1m"; m < 3.0 -> "<3m"; m < 10.0 -> "<10m"; else -> ">=10m"
        }
        private fun fmt1(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "?"
    }
}