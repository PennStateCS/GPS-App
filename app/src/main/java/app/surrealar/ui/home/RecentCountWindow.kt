package app.surrealar.ui.home

/**
 * Turns a cumulative counter (e.g. `DiagnosticsService.diagnosticData.totalParseErrors`, which only ever grows until
 * the Developer page resets it) into "how many in the last [windowMs]". The cumulative source is left unchanged, so
 * the Developer page keeps its totals; Home shows only recent problems.
 *
 * Feed it `(nowMs, total)` samples on a monotonic clock. The count is `total - baseline`, where the baseline is the
 * newest sample at least [windowMs] old (or the first sample seen, so errors from before Home was opened do not count).
 * A total that goes down (counters reset) restarts the window. Not thread-safe: use it from one coroutine.
 */
class RecentCountWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Records [total] at [nowMs] and returns the increase over the last [windowMs] (never negative). */
    fun update(nowMs: Long, total: Long): Long {
        val last = samples.lastOrNull()
        if (last != null && (total < last.second || nowMs < last.first)) samples.clear()
        // Only changes are stored: an unchanged total adds nothing new to count from.
        if (samples.isEmpty() || samples.last().second != total) samples.addLast(nowMs to total)
        // Keep exactly one sample at or before the window start (the baseline); drop older ones.
        val start = nowMs - windowMs
        while (samples.size >= 2 && samples[1].first <= start) samples.removeFirst()
        return (total - samples.first().second).coerceAtLeast(0L)
    }

    companion object {
        /** Home's "recent" window for NMEA parse errors. */
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}
