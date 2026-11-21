package io.github.pennstatecs.geokit.gnss.capture

import io.github.pennstatecs.geokit.gnss.model.Fix

/**
 * Clock-skew-tolerant "is this fix fresh?" test for averaging.
 *
 * The old test compared the fix's GNSS timestamp with the tablet's wall clock. A tablet clock that
 * is more than [maxAgeMs] off GNSS time (common in the field without network time) then rejected
 * EVERY fix and capture could never complete.
 *
 * Instead, for each fix we measure `offset = receivedAt − fixTime`, which is clock skew + delivery
 * latency. Skew is constant over a session, so the smallest offset seen is the baseline; a fix is
 * fresh when its offset exceeds that baseline by no more than [maxAgeMs] (i.e. it was not delivered
 * late/buffered). Fixes whose GNSS time goes backwards by more than [maxAgeMs] are also rejected.
 *
 * Not thread-safe; use from one collector.
 */
public class FixFreshnessGate(
    private val maxAgeMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var baselineOffsetMs: Long? = null
    private var newestFixMs: Long? = null

    /**
     * True when [fix] was delivered within [maxAgeMs] of the fastest delivery seen (and its GNSS time
     * has not gone backwards by more than [maxAgeMs]). Updates the gate's baseline.
     */
    public fun isFresh(fix: Fix): Boolean {
        val fixMs = fix.timeUtc.toEpochMilli()
        val newest = newestFixMs
        if (newest != null && fixMs < newest - maxAgeMs) return false
        newestFixMs = if (newest == null) fixMs else maxOf(newest, fixMs)

        val offset = nowMs() - fixMs
        val baseline = baselineOffsetMs?.let { minOf(it, offset) } ?: offset
        baselineOffsetMs = baseline
        return offset - baseline <= maxAgeMs
    }
}
