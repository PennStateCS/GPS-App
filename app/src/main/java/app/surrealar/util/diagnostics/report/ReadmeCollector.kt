package app.surrealar.util.diagnostics.report

/** README.txt: one line per file in the report and how to read the timestamps. */
object ReadmeCollector {

    /** File (or pattern) → what it is. Order = reading order. */
    val FILES: List<Pair<String, String>> = listOf(
        "README.txt" to "this file",
        "diagnostic-summary.txt" to "start here: report info, the reported problem, rule-based findings with evidence, subsystem status, where to look, export completeness; then the First look block and build/device details",
        "incidents.txt" to "each marked problem (Mark Problem): note, app state, and the log lines from 5 min before to 2 min after it",
        "timeline.txt" to "last 2 hours of warnings/errors, state changes and lifecycle, navigation, receiver, corrections, GNSS, AR, model, data, capture and session lines, in time order (>> = near a marked problem)",
        "export-health.txt" to "how the export went: each section's status (OK, FAILED, TIMED_OUT, UNAVAILABLE) and time, log flush, files left out or reduced (with the time spans kept), verdict COMPLETE / PARTIAL",
        "sessions.txt" to "one row per app start (SESSION_START) and how that session ended",
        "exits.txt" to "why earlier app processes ended (Android exit reasons: crash, ANR, low memory, ...)",
        "exits/" to "saved ANR and native-crash traces (newest 5)",
        "app-state-summary.txt" to "live GNSS, receiver, corrections, clock, heading, screen and network state at export",
        "gnss-stats.txt" to "this session: time in each RTK status, RTK drops, longest correction gap, reconnects, NMEA counts",
        "device-health.txt" to "battery, thermal, storage, memory, network, location and permission state",
        "data-health.txt" to "database version, rows per table, last migration and integrity check",
        "captures.txt" to "last 50 captures (outcome, samples, accuracy bucket; no coordinates)",
        "settings-snapshot.txt" to "every setting: value, (default) or (not included); secrets show only (set)/(empty)",
        "permissions-status.txt" to "runtime permissions and location services",
        "coordinates.txt" to "coordinate counts and model links (no coordinates)",
        "model-integrity.txt" to "imported models: file present, size, links, last AR status",
        "ar-session-summary.txt" to "the most recent AR session's tallies",
        "map-diagnostics.txt" to "map renderer, key and tile status",
        "nmea-stream-diagnostics.txt" to "NMEA timing and sentence counts (no raw NMEA)",
        "app-log-*.txt" to "the event log (current, then rotated 1..4)",
        "app-errors-*.txt" to "errors and crashes only (kept longer than the event log)",
        "ar-track*.csv" to "debug builds: ARCore camera pose next to each GNSS fix while AR is open (metres from each session's first fix; no coordinates)",
        "manifest.txt" to "every file in this ZIP (itself included) with its size, the report time and report ID",
    )

    fun render(reportId: String, logsReduced: Boolean): String = buildString {
        appendLine("SurReal AR diagnostic report  (Report ID: $reportId)")
        appendLine()
        appendLine("Reading order: diagnostic-summary.txt → incidents.txt → timeline.txt → export-health.txt → the rest.")
        appendLine("Export completeness: the end of diagnostic-summary.txt, details in export-health.txt (COMPLETE, or PARTIAL with what is missing).")
        appendLine()
        appendLine("Files")
        val w = FILES.maxOf { it.first.length }
        FILES.forEach { (f, what) -> appendLine("  ${f.padEnd(w)}  $what") }
        appendLine()
        appendLine("Reading log lines")
        appendLine("  2026-11-21T14:03:07.412-05:00 W RECEIVER [main] TCP: no data for 10000ms")
        appendLine("  = device local time with its UTC offset and milliseconds, level (D debug, I info, W warning,")
        appendLine("    E error), tag, [thread], message. Lines without a timestamp belong to the line above (stack traces).")
        appendLine("  Times are the tablet's clock; gnss lines log its offset from GNSS time (\"Clock offset\").")
        appendLine("  The report never contains raw NMEA, coordinates, passwords or keys.")
        if (logsReduced) {
            appendLine()
            appendLine("NOTE: the logs were larger than the size budget, so parts were left out. Crash and ANR evidence, the")
            appendLine("  minutes around each marked problem and recent errors were kept first, then the newest lines. Each gap is")
            appendLine("  marked in the log as \"… [N lines omitted <from> → <to>] …\"; export-health.txt lists the spans kept.")
        }
    }
}