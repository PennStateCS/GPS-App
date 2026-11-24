package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentMarker
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything diagnostic-summary.txt is rendered from (computed after every other section ran). */
data class SummaryInput(
    val reportId: String,
    val nowMs: Long,
    val info: ReportInfo,
    /** Newest first. */
    val incidents: List<IncidentMarker>,
    val currentSession: String?,
    val findings: List<Finding>,
    val status: List<Pair<String, String>>,
    val health: ExportHealth,
    /** The First look block and the app's own summary (build, device, warnings), kept below the new sections. */
    val details: String,
)

/**
 * diagnostic-summary.txt, the first file a developer reads: report information, the reported problem,
 * important findings, one status line per subsystem, where to look, how complete the export is — then
 * the older detail blocks, so nothing is lost.
 */
object SummaryCollector {

    val HEADINGS = listOf(
        "Report information", "Reported problem", "Important findings", "Subsystem status",
        "Where to investigate", "Export completeness",
    )

    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun utc(epochMs: Long): String = UTC.format(Instant.ofEpochMilli(epochMs))

    fun render(s: SummaryInput, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("=== Diagnostic summary ===")
        appendLine("Read this first. Findings are rule-based: OBSERVED = the logs or state show it; POSSIBLE_CAUSE = timing")
        appendLine("or circumstance fits, not proven; UNKNOWN = the information is missing. Detail blocks follow at the end.")
        appendLine()

        heading(0)
        val i = s.info
        appendLine("Report ID     : ${s.reportId}")
        appendLine("App version   : ${i.appVersion ?: "unknown"}")
        appendLine("Git commit    : ${i.gitCommit ?: "unknown"}")
        appendLine("Device        : ${i.device ?: "unknown"}")
        appendLine("Android       : ${i.android ?: "unknown"}")
        appendLine("Generated     : ${utc(s.nowMs)} UTC / ${LogLineFormat.timestamp(s.nowMs, zone)} local (${zone.id})")
        appendLine("Session       : ${s.currentSession ?: "unknown"}")
        appendLine()

        heading(1)
        val latest = s.incidents.firstOrNull()
        if (latest == null) {
            appendLine("No problem was marked. (Testers can use Settings → About → Mark Problem when something goes wrong.)")
            appendLine("Findings below cover the current session.")
        } else {
            appendLine("Incident      : ${latest.id} (${IncidentsCollector.sessionLabel(latest, s.currentSession)})")
            appendLine("Marked at     : ${utc(latest.epochMs)} UTC / ${LogLineFormat.timestamp(latest.epochMs, zone)} local")
            appendLine("Screen        : ${IncidentContext.safeText(latest.screen) ?: "unknown"}")
            appendLine("Description   : " + (latest.description?.let { "\"${IncidentContext.redactCoordinates(it).replace("\n", " / ")}\"" } ?: "none given"))
            if (s.incidents.size > 1) appendLine("Also marked   : ${s.incidents.size - 1} earlier problem(s), see incidents.txt")
            appendLine("Findings below focus on ${ReportFindings.FOCUS_BEFORE_MS / 60_000} min before to ${ReportFindings.FOCUS_AFTER_MS / 60_000} min after the latest mark.")
        }
        appendLine()

        heading(2)
        if (s.findings.isEmpty()) appendLine("(no rule matched — nothing abnormal was recognised; check timeline.txt)")
        s.findings.forEachIndexed { n, f ->
            appendLine("${n + 1}. [${f.severity}] ${f.title}  — ${f.certainty}")
            f.incidentRelation?.let { appendLine("   $it") }
            if (f.evidence.isNotEmpty()) {
                appendLine("   Evidence:")
                f.evidence.forEach { e -> appendLine("     ${e.epochMs?.let { LogLineFormat.timestamp(it, zone) } ?: "(no time)"}  ${e.file}  ${e.text}") }
            }
            appendLine("   Next step: ${f.nextStep}")
        }
        appendLine()

        heading(3)
        val w = (s.status.maxOfOrNull { it.first.length } ?: 0)
        s.status.forEach { (k, v) -> appendLine("${k.padEnd(w)} : $v") }
        appendLine()

        heading(4)
        val seen = LinkedHashSet<String>()
        s.findings.forEach { f ->
            f.evidence.filter { it.epochMs != null }.minByOrNull { it.epochMs!! }?.let { e ->
                seen += "${e.file} around ${LogLineFormat.timestamp(e.epochMs!!, zone)} — ${f.title}"
            } ?: f.evidence.firstOrNull()?.let { seen += "${it.file} — ${f.title}" }
        }
        if (latest != null) seen += "incidents.txt — incident ${latest.id}: context and the log lines around it"
        seen += "timeline.txt — last 2 hours in order" + if (s.incidents.isNotEmpty()) "; \">>\" lines are near a marked problem" else ""
        seen += "export-health.txt — what the export could and could not collect"
        seen.forEach { appendLine("- $it") }
        appendLine()

        heading(5)
        append(completeness(s.health))
        appendLine()

        append(s.details)
    }

    private fun StringBuilder.heading(n: Int) = appendLine("--- ${HEADINGS[n]} ---")

    /** Verdict, problems, unavailable sources and logs reduced by the budget. */
    fun completeness(h: ExportHealth): String = buildString {
        appendLine("Verdict          : ${h.verdict}")
        val p = h.problems
        appendLine("Failed or missing: " + if (p.isEmpty()) "(none)" else "${p.size}")
        p.take(10).forEach { appendLine("  - $it") }
        if (p.size > 10) appendLine("  - … ${p.size - 10} more")
        val unavailable = h.sections.filter { it.state == SectionStatus.State.UNAVAILABLE }
        if (unavailable.isNotEmpty()) {
            appendLine("Not available    : ${unavailable.size} (not a fault)")
            unavailable.take(10).forEach { appendLine("  - ${it.name}: ${it.detail ?: "unavailable"}") }
        }
        val reduced = h.truncated.filter { it.expected }
        if (reduced.isEmpty()) appendLine("Logs             : complete (nothing left out)")
        else {
            appendLine("Logs             : ${reduced.size} file(s) reduced to fit the size budget")
            reduced.forEach { t ->
                appendLine("  - ${t.name}: ${kb(t.originalBytes)} → ${kb(t.includedBytes)}" +
                    (if (t.omittedSpans > 0) ", ${t.omittedSpans} omitted span(s), ${t.omittedLines} lines" else "") +
                    (t.includedSpan?.let { "; kept $it" } ?: ""))
            }
        }
        appendLine("Details          : export-health.txt")
    }

    private fun kb(b: Long) = if (b >= 1024 * 1024) String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024)) else "${(b + 1023) / 1024} KB"
}
