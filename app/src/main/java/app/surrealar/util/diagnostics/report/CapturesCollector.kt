package app.surrealar.util.diagnostics.report

import java.io.File

/** captures.txt: the last 50 lines of diagnostics/captures.log. */
object CapturesCollector {
    const val LAST = 50

    fun render(lines: List<String>): String = buildString {
        appendLine("=== Captures (last $LAST) ===")
        appendLine("One line per capture outcome: point id (when saved), mode, samples, duration, RTK, accuracy bucket, rejected fixes.")
        appendLine()
        val tail = lines.filter { it.isNotBlank() }.takeLast(LAST)
        if (tail.isEmpty()) appendLine("(no captures recorded)")
        tail.forEach { appendLine(it) }
    }

    fun read(file: File?): List<String> = file?.takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty()
}