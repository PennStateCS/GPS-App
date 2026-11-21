package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import java.util.Locale

/** The "First look" block at the top of diagnostic-summary.txt: the answers a support person wants first. */
object FirstLookCollector {

    fun render(reportId: String, idx: LogIndex, live: LiveState): String = buildString {
        val now = idx.nowMs
        appendLine("=== First look ===")
        appendLine("Report ID          : $reportId")
        appendLine("Time span covered  : " + if (idx.firstMs == null) "no log lines" else
            "${LogLineFormat.timestamp(idx.firstMs!!)} → ${LogLineFormat.timestamp(idx.lastMs!!)} (${hours(idx.lastMs!! - idx.firstMs!!)})")
        appendLine("Sessions           : ${idx.sessions.size}")
        val last = idx.sessions.lastOrNull()?.message
        val prevClean = last?.let { Regex("lastSessionClean=(\\S+)").find(it)?.groupValues?.get(1) }
        appendLine("Last session clean : " + when (prevClean) {
            "true" -> "yes (the session before this one ended normally)"
            "false" -> "NO — the session before this one ended in a crash, ANR or kill while in use"
            null -> "unknown"
            else -> prevClean
        })
        val recent = idx.crashes.filter { now - it.epochMs <= LogIndex.DAY_MS }
        appendLine("Crashes/ANRs (24h) : ${recent.size}" + (recent.firstOrNull()?.let { " — newest: ${it.message.take(160)}" } ?: ""))
        appendLine("Source / RTK       : ${live.source ?: "unknown"} / ${live.rtk ?: "no fix"}")
        appendLine("Receiver link      : ${live.receiverLink ?: "unknown"}")
        appendLine("Correction age     : ${live.correctionAgeS?.let { String.format(Locale.US, "%.1f s", it) } ?: "none"}" +
            (live.corrections?.let { " ($it)" } ?: ""))
        appendLine("Free storage       : ${live.freeStorageBytes?.let { mb(it) } ?: "unknown"}")
        appendLine("Top warnings/errors (24h):")
        val top = idx.warnCounts24h.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(5)
        if (top.isEmpty()) appendLine("  (none)")
        top.forEach { appendLine("  ${it.value.toString().padStart(5)} × ${idx.warnFirstText[it.key]?.take(160)}") }
        appendLine()
    }

    private fun hours(ms: Long) = String.format(Locale.US, "%.1f h", ms / 3_600_000.0)
    private fun mb(b: Long) = if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024)) else "${b / (1024 * 1024)} MB"
}