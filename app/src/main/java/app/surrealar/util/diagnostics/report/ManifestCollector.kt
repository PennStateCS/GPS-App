package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat

/**
 * manifest.txt: every file in the ZIP with its size (uncompressed), the report time and ID. It is the
 * last entry and lists itself as "(this file)" without a size; every other line matches the ZIP entry
 * exactly (ReportAssembler.validate checks the written ZIP against the same list).
 */
object ManifestCollector {
    fun render(entries: List<Pair<String, Long>>, reportId: String, nowMs: Long, logsReduced: Boolean): String = buildString {
        appendLine("=== Manifest ===")
        appendLine("Report ID   : $reportId")
        appendLine("Report time : ${LogLineFormat.timestamp(nowMs)}")
        if (logsReduced) appendLine("Logs        : reduced to fit the log size budget (omitted spans are marked in each log; see export-health.txt)")
        appendLine()
        val w = (entries.maxOfOrNull { it.first.length } ?: 0).coerceAtLeast("manifest.txt".length)
        entries.forEach { (name, size) -> appendLine("${name.padEnd(w)}  ${size.toString().padStart(10)} bytes") }
        appendLine("${"manifest.txt".padEnd(w)}  ${"(this file)".padStart(10)}")
        appendLine()
        appendLine("${entries.size + 1} files, ${entries.sumOf { it.second }} bytes before compression (plus this file)")
    }
}