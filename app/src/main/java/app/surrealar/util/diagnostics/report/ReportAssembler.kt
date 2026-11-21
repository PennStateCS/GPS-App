package app.surrealar.util.diagnostics.report

import app.surrealar.util.DiagnosticsLogger
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/** Builds the diagnostic ZIP from [ReportSources]: flush the logs, then write every entry. */
object ReportAssembler {

    /** Above this many bytes of logs, only the last 24 h of each log file goes in (keeps the ZIP emailable). */
    const val MAX_LOG_BYTES = 20L * 1024 * 1024

    /** Keeps lines from the last 24 h; stack-trace lines follow the line they belong to. */
    fun last24hFilter(nowMs: Long): (String) -> Boolean {
        var keep = false
        return { line ->
            val p = app.surrealar.util.diagnostics.LogLineFormat.parse(line)
            if (p != null) keep = nowMs - p.epochMs <= LogIndex.DAY_MS
            keep
        }
    }

    suspend fun write(
        zipFile: File,
        src: ReportSources,
        flush: () -> Unit = { DiagnosticsLogger.flush() },
        maxLogBytes: Long = MAX_LOG_BYTES,
    ): List<Pair<String, Long>> {
        runCatching(flush)
        val logs = runCatching { src.logFiles() }.getOrDefault(emptyList())
        val errors = runCatching { src.errorFiles() }.getOrDefault(emptyList())
        val truncated = (logs + errors).sumOf { it.length() } > maxLogBytes
        return ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val z = ZipBuilder(zos)
            val idx = runCatching { LogIndex.build(logs + errors, src.nowMs) }.getOrElse { LogIndex(src.nowMs) }
            z.textSafe("README.txt") { ReadmeCollector.render(src.reportId, logsTruncatedTo24h = truncated) }
            // ── Compact summaries (current state at export time) ──
            z.textSafe("diagnostic-summary.txt") {
                val first = runCatching { FirstLookCollector.render(src.reportId, idx, src.liveState()) }
                    .getOrElse { "=== First look ===\n(failed: ${it.message})\n\n" }
                first + src.summary()
            }
            z.textSafe("timeline.txt") { TimelineCollector.render(idx) }
            z.textSafe("sessions.txt") { SessionsCollector.render(idx) }
            val traces = runCatching { src.exitTraceFiles() }.getOrDefault(emptyList())
            z.textSafe("exits.txt") { ExitsCollector.render(idx, traces.map { it.name }) }
            traces.forEach { z.file("exits/${it.name}", it) }
            z.textSafe("device-health.txt") { DeviceHealthCollector.render(src.deviceHealth()) }
            z.textSafe("captures.txt") { CapturesCollector.render(CapturesCollector.read(src.capturesLog())) }
            z.textSafe("gnss-stats.txt") { GnssStatsCollector.render(src.gnssStats()) }
            z.textSafe("data-health.txt") { DataHealthCollector.render(src.dataHealth(), idx.lastDbEvent?.raw) }
            z.textSafe("settings-snapshot.txt") { src.settingsSnapshot() }
            z.textSafe("permissions-status.txt") { src.permissions() }
            z.textSafe("app-state-summary.txt") { src.appState() }
            z.textSafe("coordinates.txt") { src.coordinates() }
            z.textSafe("model-integrity.txt") { src.models() }
            z.textSafe("ar-session-summary.txt") { src.arSession() }
            z.textSafe("map-diagnostics.txt") { src.mapDiagnostics() }
            z.textSafe("nmea-stream-diagnostics.txt") { src.nmeaStream() }
            // ── Rolling logs (event history + crashes) ──
            logs.forEach { z.file(it.name, it, if (truncated) last24hFilter(src.nowMs) else { _ -> true }) }
            errors.forEach { z.file(it.name, it, if (truncated) last24hFilter(src.nowMs) else { _ -> true }) }
            // Last: lists everything above.
            val listed = z.entries.toList()
            z.textSafe("manifest.txt") { ManifestCollector.render(listed, src.reportId, src.nowMs, logsTruncatedTo24h = truncated) }
            z.entries
        }
    }
}