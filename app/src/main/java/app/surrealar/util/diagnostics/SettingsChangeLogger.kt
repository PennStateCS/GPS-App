package app.surrealar.util.diagnostics

import android.content.Context
import app.surrealar.data.settings.datastore.appDataStore
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * SETTINGS: one line per changed key (old → new), read from the settings store itself so every
 * writer is covered. Values go through [SettingsRedaction] like the snapshot.
 *
 * Fields that save while typing (pole height, camera distance) write the store per keystroke, so
 * changes are coalesced per key over [COALESCE_MS]: one line from the first old value to the final
 * new one ("1" → "1.85", not "1" → "1.8" → "1.85").
 */
object SettingsChangeLogger {

    const val COALESCE_MS = 2_000L

    private const val NOT_INCLUDED = "(not included)"

    /** One key's change: described values (redacted) and the raw ones (to spot real changes behind "(set)"). */
    data class Change(val key: String, val from: String, val to: String, val fromRaw: Any?, val toRaw: Any?)

    /** The keys that differ between two store snapshots (sorted), skipping keys not included on both sides. */
    fun changeList(old: Map<String, Any?>, new: Map<String, Any?>): List<Change> =
        (old.keys + new.keys).sorted().mapNotNull { k ->
            val a = old[k]; val b = new[k]
            if (k in old && k in new && a == b) return@mapNotNull null
            if (k !in old && k !in new) return@mapNotNull null
            val from = if (k in old) SettingsRedaction.describe(k, a) else "(default)"
            val to = if (k in new) SettingsRedaction.describe(k, b) else "(default)"
            // Values the log never shows: a line would say nothing.
            if (from == NOT_INCLUDED && to == NOT_INCLUDED) return@mapNotNull null
            Change(k, from, to, a, b)
        }

    /** A sensitive value that changed but still reads "(set)" → "(set) → (set) (changed)". */
    fun line(c: Change): String = "Setting ${c.key}: ${c.from} → ${if (c.from == c.to) "${c.to} (changed)" else c.to}"

    /** Lines for the keys that differ between two store snapshots. */
    fun changes(old: Map<String, Any?>, new: Map<String, Any?>): List<String> = changeList(old, new).map(::line)

    /**
     * Merges successive changes of a key until it has been quiet for [windowMs]. Thread-safe. A key
     * edited back to its first value within the window produces no line.
     */
    class Coalescer(private val windowMs: Long = COALESCE_MS) {
        private class Pending(val first: Change, var last: Change, var lastMs: Long)
        private val pending = LinkedHashMap<String, Pending>()

        @Synchronized fun add(changes: List<Change>, nowMs: Long) {
            for (c in changes) {
                val p = pending[c.key]
                if (p == null) pending[c.key] = Pending(c, c, nowMs) else { p.last = c; p.lastMs = nowMs }
            }
        }

        /** Changes whose key has been quiet for the window (all of them if [all]), removed from pending. */
        @Synchronized fun due(nowMs: Long, all: Boolean = false): List<Change> {
            val out = ArrayList<Change>()
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (key, p) = it.next()
                if (!all && nowMs - p.lastMs < windowMs) continue
                it.remove()
                val merged = Change(key, p.first.from, p.last.to, p.first.fromRaw, p.last.toRaw)
                if (merged.fromRaw == merged.toRaw && merged.from == merged.to) continue   // edited back
                out += merged
            }
            return out
        }

        @Synchronized fun isEmpty(): Boolean = pending.isEmpty()
    }

    private fun logChanges(changes: List<Change>) = changes.forEach { c ->
        // The active CRS / datum / units are COORD events too.
        DiagnosticsLogger.i(if (c.key.startsWith("coord_")) DiagTags.COORD else DiagTags.SETTINGS, line(c))
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L

    fun install(context: Context) {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coalescer = Coalescer()
        var flushJob: Job? = null
        scope.launch {
            var previous: Map<String, Any?>? = null
            app.appDataStore.data
                .map { prefs -> prefs.asMap().mapKeys { it.key.name } }
                .catch { DiagnosticsLogger.w(DiagTags.SETTINGS, "Settings change log stopped", it) }
                .collect { now ->
                    previous?.let { before ->
                        val changes = changeList(before, now)
                        if (changes.isNotEmpty()) {
                            coalescer.add(changes, nowMs())
                            // Restart the flusher on every change (it only suspends in delay, so a
                            // cancel never interrupts an emit); nothing can be left pending unflushed.
                            flushJob?.cancel()
                            flushJob = scope.launch {
                                while (!coalescer.isEmpty()) {
                                    delay(COALESCE_MS)
                                    logChanges(coalescer.due(nowMs()))
                                }
                            }
                        }
                    }
                    previous = now
                }
        }
    }
}
