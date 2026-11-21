package app.surrealar.util.diagnostics

import android.content.Context
import app.surrealar.data.settings.datastore.appDataStore
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * SETTINGS: one line per changed key (old → new), read from the settings store itself so every
 * writer is covered. Values go through [SettingsRedaction] like the snapshot.
 */
object SettingsChangeLogger {

    /** Lines for the keys that differ between two store snapshots. */
    fun changes(old: Map<String, Any?>, new: Map<String, Any?>): List<String> =
        (old.keys + new.keys).sorted().mapNotNull { k ->
            val a = old[k]; val b = new[k]
            if (k in old && k in new && a == b) return@mapNotNull null
            if (k !in old && k !in new) return@mapNotNull null
            val from = if (k in old) SettingsRedaction.describe(k, a) else "(default)"
            val to = if (k in new) SettingsRedaction.describe(k, b) else "(default)"
            // A sensitive value that changed but still reads "(set)" → "(set) → (set, changed)".
            "Setting $k: $from → ${if (from == to) "$to (changed)" else to}"
        }

    fun install(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var previous: Map<String, Any?>? = null
            app.appDataStore.data
                .map { prefs -> prefs.asMap().mapKeys { it.key.name } }
                .catch { DiagnosticsLogger.w(DiagTags.SETTINGS, "Settings change log stopped: ${it.message}") }
                .collect { now ->
                    previous?.let { before -> changes(before, now).forEach { DiagnosticsLogger.i(DiagTags.SETTINGS, it) } }
                    previous = now
                }
        }
    }
}