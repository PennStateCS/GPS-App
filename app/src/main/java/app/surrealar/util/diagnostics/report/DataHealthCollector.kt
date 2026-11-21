package app.surrealar.util.diagnostics.report

/** data-health.txt: database version, rows per table, last migration and the quick integrity check. */
object DataHealthCollector {
    fun render(d: DataHealth, lastMigration: String?): String = buildString {
        appendLine("=== Data health ===")
        appendLine("Database version : ${d.version ?: "unknown"}")
        appendLine("Last migration   : ${lastMigration ?: "none recorded in the logs"}")
        appendLine("Integrity check  : ${d.integrity}")
        appendLine("--- Rows per table ---")
        if (d.rowCounts.isEmpty()) appendLine("(unknown)")
        d.rowCounts.toSortedMap().forEach { (t, n) -> appendLine("${t.padEnd(20)}: $n") }
    }
}