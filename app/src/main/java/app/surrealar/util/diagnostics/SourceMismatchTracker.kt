package app.surrealar.util.diagnostics

/**
 * SOURCE_MISMATCH: the selected location source (Settings) differs from the live provider, e.g.
 * External selected but Device GNSS active. Fed only with combinations that have already held for
 * a while (the caller debounces), it returns a line when the mismatch starts or changes, and a plain
 * "resolved" sentence when it ends. Thread-safe.
 *
 * [reason] is a SourceRoutingDecisions.classifySelectedActiveMismatch reason (null = aligned).
 */
class SourceMismatchTracker {
    private val lock = Any()
    private var logged: Triple<String, String, String>? = null

    fun onStable(selected: String, active: String, reason: String?, connId: String? = null): Pair<Char, String>? = synchronized(lock) {
        val before = logged
        if (reason == null) {
            if (before == null) return@synchronized null
            logged = null
            return@synchronized 'I' to "GNSS source mismatch resolved selected=$selected active=$active"
        }
        val now = Triple(selected, active, reason)
        if (now == before) return@synchronized null
        logged = now
        'W' to DiagLine.of(DiagEvents.SOURCE_MISMATCH, "selected" to selected, "active" to active, "reason" to reason,
            DiagEvents.K_CONN to connId, text = "GNSS selected source differs from the active source")
    }
}
