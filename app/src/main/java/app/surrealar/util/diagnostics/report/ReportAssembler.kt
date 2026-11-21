package app.surrealar.util.diagnostics.report

import app.surrealar.util.DiagnosticsLogger
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/** Builds the diagnostic ZIP from [ReportSources]: flush the logs, then write every entry. */
object ReportAssembler {

    suspend fun write(
        zipFile: File,
        src: ReportSources,
        flush: () -> Unit = { DiagnosticsLogger.flush() },
    ): List<Pair<String, Long>> {
        runCatching(flush)
        return ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val z = ZipBuilder(zos)
            val idx = runCatching { LogIndex.build(src.logFiles() + src.errorFiles(), src.nowMs) }.getOrElse { LogIndex(src.nowMs) }
            z.textSafe("README.txt") { ReadmeCollector.render(src.reportId, logsTruncatedTo24h = false) }
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
            src.logFiles().forEach { z.file(it.name, it) }
            src.errorFiles().forEach { z.file(it.name, it) }
            z.entries
        }
    }
}