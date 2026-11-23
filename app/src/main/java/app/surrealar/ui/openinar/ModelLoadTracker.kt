package app.surrealar.ui.openinar

/**
 * Which GLB each AR model key (coordinate id) should show, and which load is in flight for it, so a
 * load that finishes after its coordinate was deleted, unlinked or relinked is discarded instead of
 * publishing a stale model.
 *
 *  - [request] records the wanted file. A different file (or a key not wanted until now) gets a new
 *    generation; the same file keeps its generation, so a duplicate request never invalidates the load
 *    already running for it. At most one load per key runs; a request while one runs is [Request.Deferred]
 *    (the renderer re-issues it when that load ends).
 *  - [forget] / [retain] stop wanting a key: a load still running for it becomes stale.
 *  - Before creating the Filament asset the renderer asks [isCurrent]: publish only if the load's
 *    generation and file are still the wanted ones.
 *  - [ended] clears the in-flight slot on every outcome (success, stale, failure, cancel), so a failed
 *    load never blocks a later request.
 *
 * Main thread only (as the renderer's maps are). Pure Kotlin → unit-tested.
 */
internal class ModelLoadTracker {

    /** One load request: the file wanted for [key] at [generation]. Compared by identity. */
    class Ticket internal constructor(val key: String, val filePath: String, val generation: Long)

    sealed interface Request {
        /** Start loading [ticket] now. */
        class Start(val ticket: Ticket) : Request
        /** A load for this key is already running; re-issue the request when it ends. */
        data object Deferred : Request
    }

    private val wanted = HashMap<String, Ticket>()
    private val inFlight = HashMap<String, Ticket>()
    private var nextGeneration = 1L

    fun request(key: String, filePath: String): Request {
        val cur = wanted[key]
        val ticket = if (cur != null && cur.filePath == filePath) cur
                     else Ticket(key, filePath, nextGeneration++).also { wanted[key] = it }
        if (key in inFlight) return Request.Deferred
        inFlight[key] = ticket
        return Request.Start(ticket)
    }

    /** [key] should no longer show any model (deleted, unlinked, relinked or its file is missing). */
    fun forget(key: String) {
        wanted.remove(key)
    }

    /** Forgets every key not in [keys]. */
    fun retain(keys: Set<String>) {
        wanted.keys.retainAll(keys)
    }

    /** Whether a finished load for [ticket] may publish its asset. */
    fun isCurrent(ticket: Ticket): Boolean = wanted[ticket.key] === ticket

    fun isLoading(key: String): Boolean = key in inFlight

    /**
     * The load for [ticket] ended (published, discarded, failed or cancelled). Returns the newer
     * request still waiting for this key (to be re-issued), or null.
     */
    fun ended(ticket: Ticket): Ticket? {
        if (inFlight[ticket.key] === ticket) inFlight.remove(ticket.key)
        return wanted[ticket.key]?.takeIf { it !== ticket }
    }
}
