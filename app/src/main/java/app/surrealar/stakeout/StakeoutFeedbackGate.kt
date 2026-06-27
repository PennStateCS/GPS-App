package app.surrealar.stakeout

/** The feedback to emit this frame. The fragment maps these to haptics/audio. */
enum class StakeoutFeedback { NONE, NAVIGATING_PULSE, ENTERED_TOLERANCE }

/**
 * Pure, clock-injected gate deciding *when* stakeout feedback should fire, independent of how it is
 * delivered (haptics/audio). It guarantees:
 *
 *  • Entering tolerance fires [StakeoutFeedback.ENTERED_TOLERANCE] exactly ONCE — no arrival spam.
 *  • Leaving tolerance and re-entering fires it again.
 *  • While navigating, [StakeoutFeedback.NAVIGATING_PULSE] is throttled to at most one per
 *    [navIntervalMs]; it can be disabled by passing a non-positive interval.
 *  • No feedback when there is no target / no position (WAITING/NO_TARGET).
 *
 * Not thread-safe; call from a single (UI) thread. Call [reset] when guidance starts/stops.
 */
class StakeoutFeedbackGate(
    private val navIntervalMs: Long = 4000L,
) {
    private var wasWithinTolerance = false
    private var lastNavPulseMs = Long.MIN_VALUE

    fun reset() {
        wasWithinTolerance = false
        lastNavPulseMs = Long.MIN_VALUE
    }

    /**
     * @param status current navigation status
     * @param nowMs  monotonic clock (e.g. SystemClock.elapsedRealtime())
     */
    fun onUpdate(status: StakeoutStatus, nowMs: Long): StakeoutFeedback {
        val withinTolerance = status == StakeoutStatus.WITHIN_TOLERANCE || status == StakeoutStatus.AT_TARGET

        if (withinTolerance) {
            return if (!wasWithinTolerance) {
                wasWithinTolerance = true
                StakeoutFeedback.ENTERED_TOLERANCE
            } else {
                StakeoutFeedback.NONE
            }
        }

        // Not within tolerance: clear the latch so a later re-entry fires again.
        wasWithinTolerance = false

        if (status == StakeoutStatus.NAVIGATING && navIntervalMs > 0L) {
            if (lastNavPulseMs == Long.MIN_VALUE || nowMs - lastNavPulseMs >= navIntervalMs) {
                lastNavPulseMs = nowMs
                return StakeoutFeedback.NAVIGATING_PULSE
            }
        }
        return StakeoutFeedback.NONE
    }
}
