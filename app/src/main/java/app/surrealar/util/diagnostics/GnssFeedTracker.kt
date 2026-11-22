package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.Locale

/**
 * Turns the shared fix stream into a few GNSS log lines (no coordinates, ever):
 *  - RTK status transitions (from → to, sats used, HDOP, correction-age bucket), logged only once
 *    the new status has held for [RTK_HOLD_MS] so FIX↔FLOAT flapping doesn't flood the log
 *  - correction age crossing 5 s / 10 s / 30 s, and its recovery
 *  - source switches
 *  - a heartbeat line ([heartbeatIfDue]: only when source/RTK/sats/hAcc/corrAge buckets changed, or
 *    every [HEARTBEAT_EVERY_MS]; nothing while there is no source or no recent fix)
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
    /** RTK status in the last transition line, and a different status waiting out [RTK_HOLD_MS]. */
    private var loggedRtk: RtkStatus? = null
    private var pendingRtk: RtkStatus? = null
    private var pendingRtkSinceMs = 0L
    private var lastBeatKey: List<Any?>? = null
    private var lastBeatAtMs = 0L
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
        if (old != null) { sourceSwitches++; sink('I', "GNSS source $old → $name") }
        closeStatus(nowMs)
        lastRtk = null; corrBucket = null; lastFix = null; fixTimes.clear()
        loggedRtk = null; pendingRtk = null; lastBeatKey = null
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
            if (from == RtkStatus.FIX) rtkDrops++
        }
        // Log a transition only once the new status has held for RTK_HOLD_MS (a flap back is never logged).
        if (rtk == loggedRtk) {
            pendingRtk = null
        } else if (rtk != pendingRtk) {
            pendingRtk = rtk; pendingRtkSinceMs = nowMs
        } else if (nowMs - pendingRtkSinceMs >= RTK_HOLD_MS) {
            val from = loggedRtk
            loggedRtk = rtk; pendingRtk = null
            sink(if (from == RtkStatus.FIX) 'W' else 'I', "RTK ${from?.name ?: "start"} → ${rtk.name} sats=${fix.satsUsed ?: "unknown"}" +
                " hdop=${fmt1(fix.hDop)} corrAge=${bucket?.label ?: "none"}")
        }

        if (bucket != null && bucket != corrBucket) {
            val before = corrBucket
            corrBucket = bucket
            when {
                bucket == CorrectionBucket.OK && before != null ->
                    sink('I', "Corrections age recovered ageS=${fmt1(diffAge)}")
                bucket != CorrectionBucket.OK && (before == null || bucket.minS > before.minS) ->
                    sink('W', "Corrections age over threshold thresholdS=${bucket.minS.toInt()} ageS=${fmt1(diffAge)}")
            }
        }
    }

    fun onSky(used: Int, visible: Int) = synchronized(lock) { skyUsed = used; skyVisible = visible }

    fun onNmea(nowMs: Long) { lastNmeaAtMs = nowMs }   // one field write per line, nothing else

    /**
     * The heartbeat line when one is due, else null: only when the source, RTK status, sats bucket,
     * hAcc bucket or correction-age bucket changed since the last one, or [HEARTBEAT_EVERY_MS] passed.
     * Null while there is no source or no fix in the last 60 s (the link tracker covers a lost feed).
     */
    fun heartbeatIfDue(nowMs: Long): String? = synchronized(lock) {
        val f = lastFix?.takeIf { nowMs - lastFixAtMs <= 60_000L }
        if (source == null || f == null) { lastBeatKey = null; return@synchronized null }
        val used = skyUsed?.takeIf { it > 0 } ?: f.satsUsed
        // Sats bucket 0-4 / 5-9 / 10-14 / 15+: an RTK survey normally sits in the top one.
        val key = listOf(source, f.rtkStatus, used?.let { minOf(it / 5, 3) }, hAccBucket(f.hAccM), CorrectionBucket.of(f.diffAgeS))
        if (key == lastBeatKey && nowMs - lastBeatAtMs < HEARTBEAT_EVERY_MS) return@synchronized null
        lastBeatKey = key; lastBeatAtMs = nowMs
        heartbeat(nowMs)
    }

    /** The heartbeat line (no coordinates), always built; see [heartbeatIfDue]. */
    fun heartbeat(nowMs: Long): String = synchronized(lock) {
        val f = lastFix?.takeIf { nowMs - lastFixAtMs <= 60_000L }
        while (fixTimes.isNotEmpty() && nowMs - fixTimes.first > 60_000L) fixTimes.removeFirst()
        val used = skyUsed?.takeIf { it > 0 } ?: f?.satsUsed
        val vis = skyVisible?.takeIf { it > 0 } ?: f?.satsVisible ?: used
        val nmea = if (lastNmeaAtMs == 0L) "never" else fmt1((nowMs - lastNmeaAtMs) / 1000.0)
        "GNSS heartbeat source=${source ?: "none"} rtk=${f?.rtkStatus?.name ?: "none"} sats=${used ?: "unknown"}/${vis ?: "unknown"}" +
            " hdop=${fmt1(f?.hDop)} hAcc=${hAccBucket(f?.hAccM)} corrAgeS=${f?.diffAgeS?.let { fmt1(it) } ?: "none"}" +
            " fixesPerS=${String.format(Locale.US, "%.1f", fixTimes.size / 60.0)} lastNmeaAgeS=$nmea"
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
        /** A new RTK status must hold this long before its transition is logged. */
        const val RTK_HOLD_MS = 5_000L
        /** Heartbeat even when nothing changed, this often. */
        const val HEARTBEAT_EVERY_MS = 600_000L

        fun hAccBucket(m: Double?): String = when {
            m == null || !m.isFinite() -> "unknown"
            m < 0.02 -> "<2cm"; m < 0.05 -> "<5cm"; m < 0.10 -> "<10cm"; m < 0.30 -> "<30cm"
            m < 1.0 -> "<1m"; m < 3.0 -> "<3m"; m < 10.0 -> "<10m"; else -> ">=10m"
        }
        private fun fmt1(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"
    }
}