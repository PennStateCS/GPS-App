package app.surrealar.util.diagnostics.report

/** timeline.txt: the last 2 h of warnings/errors and the story lines, merged in time order. */
object TimelineCollector {
    fun render(idx: LogIndex): String = buildString {
        appendLine("=== Timeline (last 2 hours) ===")
        appendLine("Warnings, errors and LIFECYCLE / NAV / RECEIVER / CORRECTIONS / CAPTURE / SESSION lines from all log files.")
        appendLine()
        if (idx.timeline.isEmpty()) appendLine("(nothing in the last 2 hours)")
        idx.timeline.forEach { e ->
            appendLine(e.raw)
            e.continuation.take(8).forEach { appendLine(it) }
            if (e.continuation.size > 8) appendLine("\t... ${e.continuation.size - 8} more lines in app-errors-*.txt")
        }
    }
}