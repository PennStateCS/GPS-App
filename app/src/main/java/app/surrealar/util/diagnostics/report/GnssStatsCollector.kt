package app.surrealar.util.diagnostics.report

import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.util.Locale

/** gnss-stats.txt: this session's RTK time, drops, correction gaps, reconnects and NMEA counts. */
object GnssStatsCollector {

    private fun dur(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%dm %02ds", s / 60, s % 60)
    }

    /** The "--- Sentence counts" block of nmea-stream-diagnostics.txt, or null. */
    fun sentenceCounts(nmeaStream: String): String? {
        val lines = nmeaStream.lines()
        val start = lines.indexOfFirst { it.startsWith("--- Sentence counts") }
        if (start < 0) return null
        return lines.drop(start + 1).takeWhile { it.isNotBlank() && !it.startsWith("---") }.joinToString("\n").ifBlank { null }
    }

    fun render(g: GnssStatsInput): String = buildString {
        appendLine("=== GNSS stats (this app session) ===")
        val s = g.stats
        if (s == null) appendLine("(GNSS feed not started)")
        else {
            appendLine("--- Time in each RTK status ---")
            val total = s.timeInStatusMs.values.sum().coerceAtLeast(1)
            RtkStatus.entries.forEach { st ->
                val ms = s.timeInStatusMs[st] ?: 0L
                if (ms > 0) appendLine("${st.name.padEnd(15)}: ${dur(ms).padStart(12)}  ${String.format(Locale.US, "%5.1f", ms * 100.0 / total)}%")
            }
            if (s.timeInStatusMs.isEmpty()) appendLine("(no fixes yet)")
            appendLine("--- Events ---")
            appendLine("RTK drops (left FIX)       : ${s.rtkDrops}")
            appendLine("Longest correction age     : ${s.longestCorrectionAgeS?.let { String.format(Locale.US, "%.1f s", it) } ?: "none seen"}")
            appendLine("Source switches            : ${s.sourceSwitches}")
            appendLine("Fixes received             : ${s.fixes}")
        }
        appendLine("Receiver reconnects        : ${g.reconnects ?: "unknown"}")
        appendLine("--- NMEA ---")
        appendLine("Lines processed            : ${g.nmeaLines ?: "unknown"}")
        appendLine("Checksum/parse failures    : ${g.nmeaParseErrors ?: "unknown"}")
        appendLine("Sentence counts (external receiver):")
        appendLine(g.sentenceCounts?.prependIndent("  ") ?: "  (none)")
    }
}