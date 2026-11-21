package app.surrealar.util.diagnostics.report

/** exits.txt: Android's recorded reasons for earlier process exits, plus the saved trace files. */
object ExitsCollector {
    fun render(idx: LogIndex, traceFiles: List<String>): String = buildString {
        appendLine("=== Process exits ===")
        appendLine("Why earlier app processes ended, as recorded by Android (API 30+) at the next start.")
        appendLine()
        if (idx.exits.isEmpty()) appendLine("(no exit reasons recorded)")
        idx.exits.forEach { appendLine(it.raw) }
        appendLine()
        appendLine("--- Saved traces (exits/) ---")
        if (traceFiles.isEmpty()) appendLine("(none — traces are saved for ANRs and native crashes)")
        traceFiles.forEach { appendLine("exits/$it") }
    }
}