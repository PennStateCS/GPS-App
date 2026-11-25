package app.surrealar.util.diagnostics

/**
 * Captures stopped by the app this process (source / datum / profile / receiver / tilt reference changes), by
 * reason code, for the diagnostic report's "Survey context" section. Codes and counts only: never a position,
 * point name or setting value. Thread-safe.
 */
object CaptureInterruptions {
    data class Snapshot(val counts: Map<String, Int>, val lastReason: String?, val lastAtMs: Long?) {
        val total: Int get() = counts.values.sum()
    }

    private val lock = Any()
    private val counts = LinkedHashMap<String, Int>()
    private var lastReason: String? = null
    private var lastAtMs: Long? = null

    fun record(code: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        counts[code] = (counts[code] ?: 0) + 1
        lastReason = code
        lastAtMs = nowMs
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(LinkedHashMap(counts), lastReason, lastAtMs) }

    /** Tests only. */
    internal fun reset() = synchronized(lock) { counts.clear(); lastReason = null; lastAtMs = null }
}
