package app.surrealar.gnss.status

import io.github.pennstatecs.geokit.gnss.model.RtkStatus

/**
 * Stabilises [CorrectionHealth] for the compact toolbar so a warning does not flicker when the correction age hovers
 * around a threshold. Pure and single-threaded (the toolbar pipeline calls it from one coroutine); time is a
 * monotonic clock passed in.
 *
 * Raw classification of one live external fix (thresholds come from existing rules, see docs/gnss-toolbar.md):
 * - age > [staleS] (Home's [GnssStatusInterpreter.STALE_CORRECTION_S], 15 s) → [CorrectionHealth.STALE]
 * - age > [agingS] (the capture policy's `maxDiffAgeSec`: capture stops accepting fixes above it) → [CorrectionHealth.AGING]
 * - no correction age while the solution has no differential corrections (Single / No fix / DR), after corrections
 *   were seen from this receiver → [CorrectionHealth.UNAVAILABLE] (a receiver never used with corrections is not
 *   nagged; its "Single" solution already says so)
 * - otherwise [CorrectionHealth.OK].
 *
 * Hysteresis:
 * - a MORE severe state is entered immediately (fail-closed: a problem is never hidden or delayed);
 * - a LESS severe state is entered only when the age is at least [marginS] below the threshold it is leaving (or
 *   corrections are reported again, for UNAVAILABLE) and that has held continuously for [holdMs].
 *
 * [reset] (or an update with `applicable = false`) forgets everything: call it when the source or receiver changes.
 */
class CorrectionHealthTracker(
    agingS: Double,
    val staleS: Double = GnssStatusInterpreter.STALE_CORRECTION_S,
    val marginS: Double = DEFAULT_MARGIN_S,
    val holdMs: Long = DEFAULT_HOLD_MS,
) {
    /** Aging threshold; never above [staleS] (aging then never shows separately). */
    val agingS: Double = agingS.coerceAtMost(staleS)

    var current: CorrectionHealth = CorrectionHealth.OK
        private set
    private var seenCorrections = false
    private var relaxSinceMs: Long? = null

    fun reset() {
        current = CorrectionHealth.OK
        seenCorrections = false
        relaxSinceMs = null
    }

    /**
     * @param applicable a live fix from an external receiver (not device GNSS, not replay, not a stale/lost display)
     * @param ageS the fix's correction age (s); null / negative / NaN = not reported
     */
    fun update(applicable: Boolean, ageS: Double?, rtkStatus: RtkStatus, nowMs: Long): CorrectionHealth {
        if (!applicable) { reset(); return current }
        val age = ageS?.takeIf { it.isFinite() && it >= 0.0 }
        if (age != null) seenCorrections = true
        val raw = classify(age, rtkStatus, agingS, staleS)
        if (raw.rank >= current.rank) {
            current = raw
            relaxSinceMs = null
            return current
        }
        // Less severe: only once clear of the threshold by the margin, continuously for holdMs.
        val relaxed = classify(age, rtkStatus, agingS - marginS, staleS - marginS)
        if (relaxed.rank >= current.rank) { relaxSinceMs = null; return current }
        val since = relaxSinceMs ?: nowMs.also { relaxSinceMs = it }
        if (nowMs - since >= holdMs) {
            current = relaxed
            relaxSinceMs = null
        }
        return current
    }

    private fun classify(age: Double?, rtk: RtkStatus, aging: Double, stale: Double): CorrectionHealth = when {
        age != null && age > stale -> CorrectionHealth.STALE
        age != null && age > aging -> CorrectionHealth.AGING
        age == null && seenCorrections && !rtk.hasCorrections() -> CorrectionHealth.UNAVAILABLE
        else -> CorrectionHealth.OK
    }

    private fun RtkStatus.hasCorrections() = this == RtkStatus.FIX || this == RtkStatus.FLOAT || this == RtkStatus.DGPS

    companion object {
        /** A warning clears only once the age is this far (s) below the threshold. */
        const val DEFAULT_MARGIN_S = 2.0
        /** …continuously for this long. */
        const val DEFAULT_HOLD_MS = 3_000L
    }
}
