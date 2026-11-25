package app.surrealar.receiver.tilt

import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch

/**
 * Position-reference rules for one RS4 / RS4 Pro occupation (Phase 4 WP C). Pure and single-threaded (the
 * capture's fix collector calls it in order); it never changes a position, it only admits, excludes or stops.
 *
 * The occupation is bound to one reference group: **antenna** ([EpochReference.ANTENNA] and
 * [EpochReference.ANTENNA_UNCERTAIN], both reduced with today's maths) or **pole tip**
 * ([EpochReference.POLE_TIP], never reduced). Rules, per epoch:
 *
 * - [EpochReference.NOT_APPLICABLE] (RS2+, generic NMEA): admitted, no binding (behaviour unchanged).
 * - Antenna / pole tip before the occupation has averaged anything: (re)binds to that group and is admitted
 *   (a pole-tip epoch carrying a receiver warning is excluded: degraded compensation is never averaged).
 * - Antenna / pole tip of the *other* group once epochs were averaged: [Decision.Stop] ([StopReason.REFERENCE_CHANGED]).
 *   Mixed references are never averaged.
 * - [EpochReference.MAY_BE_COMPENSATED]: never admitted.
 *   - In a pole-tip occupation, tilt state lost (the matched ETC is no longer compensating) or the ETC stream
 *     gone stale → [Decision.Stop] ([StopReason.TILT_LOST]); firmware no longer known ≥ 34.7 → stop
 *     ([StopReason.FIRMWARE_UNKNOWN], not reported as a tilt loss); a single unmatched epoch (one ETC late/dropped)
 *     is only excluded.
 *   - In an antenna occupation, a matched ETC that now makes the position possibly compensated (tilt switched on)
 *     → stop ([StopReason.REFERENCE_CHANGED]); unmatched → excluded.
 *   - Before anything was averaged: excluded; when no admissible epoch arrived for [noValidEpochsMs] while such
 *     epochs keep arriving → stop ([StopReason.MAY_BE_COMPENSATED]: set "Compensated NMEA output").
 *
 * @param noValidEpochsMs how long possibly-compensated epochs may arrive, with nothing admitted, before stopping.
 */
class CaptureReferenceGate(private val noValidEpochsMs: Long = DEFAULT_NO_VALID_EPOCHS_MS) {

    /** Why the occupation was stopped. Codes are logged and counted in diagnostics. */
    enum class StopReason(val code: String) {
        REFERENCE_CHANGED("position_reference_changed"),
        TILT_LOST("tilt_state_lost"),
        MAY_BE_COMPENSATED("may_be_compensated"),
        /**
         * Pole-tip occupation, but the receiver firmware is no longer known to be ≥ 34.7 (device info lost / changed):
         * the epochs can't be confirmed as pole tip. Tilt itself was not necessarily lost — the message says so.
         */
        FIRMWARE_UNKNOWN("firmware_unknown"),
    }

    sealed class Decision {
        object Accept : Decision()
        data class Exclude(val why: String) : Decision()
        data class Stop(val reason: StopReason, val detail: String) : Decision()
    }

    /** The group the occupation is bound to: [EpochReference.ANTENNA] or [EpochReference.POLE_TIP]; null = none yet. */
    var bound: EpochReference? = null
        private set

    /** True when an [EpochReference.ANTENNA_UNCERTAIN] epoch was admitted (shown as "uncertain"). */
    var uncertainAdmitted: Boolean = false
        private set

    /** Epochs excluded so far (diagnostics). */
    var excluded: Int = 0
        private set

    private var firstUnadmittedMs: Long? = null

    /**
     * @param averaged epochs the occupation has already averaged (the session's accepted count).
     * @param nowMs monotonic time, for the no-valid-epochs timeout.
     */
    fun decide(c: EpochClassification, averaged: Int, nowMs: Long): Decision {
        val group = when (c.reference) {
            EpochReference.NOT_APPLICABLE -> return Decision.Accept
            EpochReference.ANTENNA, EpochReference.ANTENNA_UNCERTAIN -> EpochReference.ANTENNA
            EpochReference.POLE_TIP -> EpochReference.POLE_TIP
            EpochReference.MAY_BE_COMPENSATED -> null
        }
        val occupied = averaged > 0
        if (group == null) return mayBeCompensated(c, occupied, nowMs)
        if (occupied && bound != null && bound != group) {
            return Decision.Stop(StopReason.REFERENCE_CHANGED, "${label(bound)} → ${label(group)}")
        }
        bound = group
        if (group == EpochReference.POLE_TIP && c.hasWarning) {
            return exclude("receiver tilt warning", nowMs, countsAsUnadmitted = false)
        }
        firstUnadmittedMs = null
        if (c.reference == EpochReference.ANTENNA_UNCERTAIN) uncertainAdmitted = true
        return Decision.Accept
    }

    private fun mayBeCompensated(c: EpochClassification, occupied: Boolean, nowMs: Long): Decision {
        val match = c.tilt?.match
        val matched = match == TiltMatch.MATCHED
        if (occupied) {
            when (bound) {
                EpochReference.POLE_TIP -> when {
                    c.result?.reason == EmlidPositionReference.Reason.FIRMWARE_UNKNOWN_OR_TOO_OLD ->
                        return Decision.Stop(StopReason.FIRMWARE_UNKNOWN, "firmware unknown or < 34.7 match=${match ?: "none"}")
                    matched || match == TiltMatch.ETC_STREAM_STALE ->
                        return Decision.Stop(StopReason.TILT_LOST, "${c.result?.reason ?: "unknown"} match=${match ?: "none"}")
                }
                EpochReference.ANTENNA -> if (matched) {
                    return Decision.Stop(StopReason.REFERENCE_CHANGED, "antenna → may be compensated (${c.result?.reason})")
                }
                else -> Unit
            }
            return exclude("may be compensated (${match ?: "none"})", nowMs, countsAsUnadmitted = false)
        }
        return exclude("may be compensated (${match ?: "none"})", nowMs, countsAsUnadmitted = true)
            .let { d ->
                val since = firstUnadmittedMs
                if (since != null && nowMs - since >= noValidEpochsMs) {
                    Decision.Stop(StopReason.MAY_BE_COMPENSATED, "${c.result?.reason} for ${nowMs - since} ms")
                } else d
            }
    }

    private fun exclude(why: String, nowMs: Long, countsAsUnadmitted: Boolean): Decision {
        excluded++
        if (countsAsUnadmitted && firstUnadmittedMs == null) firstUnadmittedMs = nowMs
        return Decision.Exclude(why)
    }

    private fun label(r: EpochReference?) = when (r) {
        EpochReference.POLE_TIP -> "pole tip"
        EpochReference.ANTENNA -> "antenna"
        else -> "none"
    }

    companion object {
        /** 10 s of possibly-compensated epochs (50 at 5 Hz) with nothing admissible. */
        const val DEFAULT_NO_VALID_EPOCHS_MS = 10_000L
    }
}
