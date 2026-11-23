package app.surrealar.ui.openinar

/**
 * Back-off for recovering the AR session when ARCore reports the camera unavailable
 * (CameraNotAvailableException, e.g. another app took the camera). Each report gets the next delay
 * before the session is paused and resumed again; after the last delay the policy gives up until
 * [reset] (the next onResume). A report after [quietResetMs] without one counts as a new episode, so
 * a camera loss hours later starts from the shortest delay again.
 *
 * Pure (no Android types) so it is JVM-testable. Main thread only.
 */
internal class CameraRecoveryPolicy(
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val quietResetMs: Long = DEFAULT_QUIET_RESET_MS,
) {
    init {
        require(backoffMs.isNotEmpty()) { "backoffMs must not be empty" }
    }

    sealed interface Decision {
        /** Pause now and try resuming after [delayMs]; [attempt] of [maxAttempts] (1-based). */
        data class Retry(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : Decision
        /** Out of attempts: stay paused and tell the user; a later onResume starts over. */
        data object GiveUp : Decision
    }

    private var attempts = 0
    private var lastReportMs: Long? = null

    val maxAttempts: Int get() = backoffMs.size

    /** The camera was reported unavailable at [nowMs] (monotonic clock). */
    fun onCameraUnavailable(nowMs: Long): Decision {
        val last = lastReportMs
        if (last != null && nowMs - last >= quietResetMs) attempts = 0
        lastReportMs = nowMs
        if (attempts >= backoffMs.size) return Decision.GiveUp
        val delay = backoffMs[attempts]
        attempts++
        return Decision.Retry(attempts, backoffMs.size, delay)
    }

    /** Fresh budget (onResume / screen reopened). */
    fun reset() {
        attempts = 0
        lastReportMs = null
    }

    companion object {
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
        const val DEFAULT_QUIET_RESET_MS = 60_000L
    }
}
