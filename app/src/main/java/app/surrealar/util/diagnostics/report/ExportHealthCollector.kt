package app.surrealar.util.diagnostics.report

/**
 * export-health.txt: how the export itself went. Each section's status and time, the log flush, files
 * that were left out or cut short, and the overall verdict (COMPLETE / PARTIAL). Written after the
 * logs and before manifest.txt, so it cannot list those two.
 */
object ExportHealthCollector {

    fun render(reportId: String, health: ExportHealth): String = buildString {
        appendLine("=== Export health ===")
        appendLine("Report ID : $reportId")
        val problems = health.problems
        appendLine("Verdict   : ${health.verdict}" + if (problems.isEmpty()) " (every section and file was written)"
            else " (${problems.size} problem${if (problems.size == 1) "" else "s"}; the files below say which)")
        appendLine("Log flush : " + when (health.flush.state) {
            FlushStatus.State.FLUSHED -> "FLUSHED"
            FlushStatus.State.NOT_CONFIRMED -> "NOT_CONFIRMED (the log writer did not finish in time; the newest lines may be missing)"
            FlushStatus.State.FAILED -> "FAILED" + (health.flush.detail?.let { ": $it" } ?: "")
        })
        appendLine()
        appendLine("Sections (state, time; FAILED shows the exception type)")
        val w = (health.sections.maxOfOrNull { it.name.length } ?: 0).coerceAtLeast(10)
        health.sections.forEach { s ->
            append("  ${s.name.padEnd(w)}  ${s.state.name.padEnd(11)} ${s.elapsedMs.toString().padStart(6)} ms")
            s.detail?.let { append("  $it") }
            appendLine()
        }
        appendLine()
        appendLine("Files omitted")
        if (health.omitted.isEmpty()) appendLine("  (none)")
        health.omitted.forEach { appendLine("  ${it.name}  ${it.reason}") }
        appendLine()
        appendLine("Files truncated (original → included bytes)")
        if (health.truncated.isEmpty()) appendLine("  (none)")
        health.truncated.forEach {
            appendLine("  ${it.name}  ${it.originalBytes} → ${it.includedBytes} bytes  ${it.reason}" + if (it.expected) "" else "  [unexpected]")
            it.originalSpan?.let { s -> appendLine("      original span : $s") }
            it.includedSpan?.let { s -> appendLine("      included span : $s") }
            if (it.omittedSpans > 0) appendLine("      omitted       : ${it.omittedSpans} span(s), ${it.omittedLines} lines (marked \"… [N lines omitted …] …\" in the file)")
        }
        appendLine()
        appendLine("export-health.txt and manifest.txt are written after this file and are not listed here.")
    }
}
