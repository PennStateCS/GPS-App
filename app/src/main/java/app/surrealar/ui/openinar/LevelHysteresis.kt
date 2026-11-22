package app.surrealar.ui.openinar

/**
 * Debounces a level that may hover at a threshold: a new value is only *committed* after it has been
 * seen continuously for [holdMs]. Used so "AR_QUALITY a → b" is logged once per real change instead
 * of on every frame the value flips back and forth.
 */
class LevelHysteresis<T : Any>(private val holdMs: Long) {
    /** A committed change from [from] (null = nothing committed yet) to [to]. */
    data class Transition<T>(val from: T?, val to: T)

    var committed: T? = null
        private set
    private var candidate: T? = null
    private var candidateSinceMs: Long = 0L

    /** Feeds the current value; returns a [Transition] when a new level has held for [holdMs]. */
    fun update(value: T, nowMs: Long): Transition<T>? {
        if (value == committed) {
            candidate = null
            return null
        }
        if (value != candidate) {
            candidate = value
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs < holdMs) return null
        val t = Transition(committed, value)
        committed = value
        candidate = null
        return t
    }

    fun reset() {
        committed = null
        candidate = null
        candidateSinceMs = 0L
    }
}
