package app.surrealar.gnss.mock

/**
 * Throttles a failure that repeats on every fix (e.g. "not selected as mock location app" while AR
 * keeps publishing at up to 10 Hz). The first failure of a kind is logged in full (with the stack
 * trace); after that the same kind is logged briefly at most once per [intervalMs]. A new kind, or a
 * success in between ([reset]), starts over with a full log.
 */
internal class FailureLogThrottle(
    private val intervalMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Mode { FULL, BRIEF, NONE }

    private var lastKind: String? = null
    private var lastLogMs = 0L

    @Synchronized
    fun decide(kind: String): Mode {
        val now = nowMs()
        if (kind != lastKind) {
            lastKind = kind
            lastLogMs = now
            return Mode.FULL
        }
        if (now - lastLogMs >= intervalMs) {
            lastLogMs = now
            return Mode.BRIEF
        }
        return Mode.NONE
    }

    /** The operation succeeded: the next failure is logged in full again. */
    @Synchronized
    fun reset() { lastKind = null }
}
