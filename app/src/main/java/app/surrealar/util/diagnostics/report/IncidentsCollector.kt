package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import app.surrealar.util.diagnostics.incident.IncidentMarker

/**
 * incidents.txt: every marked problem (newest first, at most [MAX_INCIDENTS]) with its rendered context
 * (the tester's note included; the ZIP redactor scrubs it) and the log lines from 5 min before to 2 min
 * after it, collected by [LogIndex] in its single pass (capped per incident: warnings, errors, state
 * changes and key tags first). The mark itself is a `>>` line in time order.
 */
object IncidentsCollector {

    const val MAX_INCIDENTS = 10

    /** "current session" / "earlier session (session=…)" / "session unknown". */
    fun sessionLabel(incident: IncidentMarker, currentSession: String?): String = when {
        incident.sessionId == null -> "session unknown"
        incident.sessionId == currentSession -> "current session"
        else -> "earlier session (session=${incident.sessionId})"
    }

    fun render(incidents: List<IncidentMarker>, idx: LogIndex, currentSession: String?): String = buildString {
        appendLine("=== Marked problems (incidents) ===")
        if (incidents.isEmpty()) {
            appendLine("No problem was marked. Testers can use Settings → About → Mark Problem at the moment something goes wrong.")
            return@buildString
        }
        val shown = incidents.take(MAX_INCIDENTS)
        appendLine("${incidents.size} marked problem${if (incidents.size == 1) "" else "s"}, newest first" +
            (if (incidents.size > shown.size) " (the newest ${shown.size} are shown)" else "") + ".")
        appendLine("Each timeline covers ${LogIndex.INCIDENT_BEFORE_MS / 60_000} min before to ${LogIndex.INCIDENT_AFTER_MS / 60_000} min after the mark;")
        appendLine("\">>\" marks the moment the problem was marked. Device local times; the incident block also gives UTC.")
        shown.forEachIndexed { n, inc ->
            appendLine()
            appendLine("--- ${if (n == 0) "Latest" else "#${n + 1}"}: incident ${inc.id} (${sessionLabel(inc, currentSession)}) ---")
            append(inc.render(includeDescription = true))
            appendLine()
            val w = idx.incidentTimes.indexOf(inc.epochMs).takeIf { it >= 0 }?.let { idx.incidentWindows.getOrNull(it) }
            val from = LogLineFormat.timestamp(inc.epochMs - LogIndex.INCIDENT_BEFORE_MS)
            val to = LogLineFormat.timestamp(inc.epochMs + LogIndex.INCIDENT_AFTER_MS)
            if (w == null) {
                appendLine("Timeline $from → $to: not available (the log index did not run)")
                return@forEachIndexed
            }
            appendLine("Timeline $from → $to: ${w.entries.size} line${if (w.entries.size == 1) "" else "s"}" +
                if (w.omitted > 0) " (${w.omitted} more left out; warnings, errors, state changes and receiver/GNSS/AR/model lines kept first)" else "")
            if (w.entries.isEmpty()) appendLine("  (no log lines in this window — the logs may have rotated past it)")
            var marked = false
            val markLine = ">> ${LogLineFormat.timestamp(inc.epochMs)} ===== PROBLEM MARKED here: incident ${inc.id} ====="
            w.entries.forEach { e ->
                if (!marked && e.epochMs > inc.epochMs) { appendLine(markLine); marked = true }
                appendLine("   " + e.raw)
                e.continuation.take(5).forEach { appendLine("   $it") }
                if (e.continuation.size > 5) appendLine("   \t... ${e.continuation.size - 5} more lines in app-errors-*.txt")
            }
            if (!marked) appendLine(markLine)
        }
    }
}
