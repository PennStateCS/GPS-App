package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import app.surrealar.util.diagnostics.incident.IncidentMarker

/**
 * timeline.txt: the last 2 h of warnings/errors, state changes and the story lines, merged in time
 * order. Lines inside a marked problem's window (5 min before to 2 min after) start with `>> `, and the
 * moment of each mark gets its own line; nothing is reordered or removed for it.
 */
object TimelineCollector {

    const val IN_WINDOW = ">> "

    fun render(idx: LogIndex, incidents: List<IncidentMarker> = emptyList()): String = buildString {
        appendLine("=== Timeline (last 2 hours) ===")
        appendLine("Warnings, errors, state-change codes and LIFECYCLE / NAV / RECEIVER / CORRECTIONS / CAPTURE / GNSS / AR / MODEL / DATA / ERROR / DIAG / SESSION lines from all log files.")
        if (incidents.isNotEmpty()) appendLine("Lines starting with \"$IN_WINDOW\" are within 5 min before to 2 min after a marked problem (incidents.txt).")
        if (idx.timelineDropped > 0) appendLine("${idx.timelineDropped} older ordinary lines left out to keep this file short (the full logs are in app-log-*.txt).")
        appendLine()
        val marks = incidents.filter { idx.nowMs - it.epochMs <= LogIndex.TIMELINE_MS && it.epochMs <= idx.nowMs }.sortedBy { it.epochMs }
        if (idx.timeline.isEmpty() && marks.isEmpty()) appendLine("(nothing in the last 2 hours)")
        var m = 0
        fun windowed(t: Long) = incidents.any { t >= it.epochMs - LogIndex.INCIDENT_BEFORE_MS && t <= it.epochMs + LogIndex.INCIDENT_AFTER_MS }
        fun mark(i: IncidentMarker) =
            appendLine("$IN_WINDOW${LogLineFormat.timestamp(i.epochMs)} ===== PROBLEM MARKED here: incident ${i.id} screen=${i.screen ?: "unknown"} =====")
        idx.timeline.forEach { e ->
            while (m < marks.size && marks[m].epochMs < e.epochMs) mark(marks[m++])
            val prefix = if (incidents.isNotEmpty() && windowed(e.epochMs)) IN_WINDOW else ""
            appendLine(prefix + e.raw)
            e.continuation.take(8).forEach { appendLine(prefix + it) }
            if (e.continuation.size > 8) appendLine("$prefix\t... ${e.continuation.size - 8} more lines in app-errors-*.txt")
        }
        while (m < marks.size) mark(marks[m++])
    }
}
