package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat

/**
 * manifest.txt: every file in the ZIP with its size (uncompressed), the report time and ID. It is the
 * last entry and lists itself as "(this file)" without a size; every other line matches the ZIP entry
 * exactly (ReportAssembler.validate checks the written ZIP against the same list).
 */
object ManifestCollector {
    fun render(entries: List<Pair<String, Long>>, reportId: String, nowMs: Long, logsTruncatedTo24h: Boolean): String = buildString {
        appendLine("=== Manifest ===")
        appendLine("Report ID   : $reportId")
        appendLine("Report time : ${LogLineFormat.timestamp(nowMs)}")
        if (logsTruncatedTo24h) appendLine("Logs        : truncated to the last 24 hours (the full logs would make the ZIP larger than 20 MB)")
        appendLine()
        val w = (entries.maxOfOrNull { it.first.length } ?: 0).coerceAtLeast("manifest.txt".length)
        entries.forEach { (name, size) -> appendLine("${name.padEnd(w)}  ${size.toString().padStart(10)} bytes") }
        appendLine("${"manifest.txt".padEnd(w)}  ${"(this file)".padStart(10)}")
        appendLine()
        appendLine("${entries.size + 1} files, ${entries.sumOf { it.second }} bytes before compression (plus this file)")
    }
}