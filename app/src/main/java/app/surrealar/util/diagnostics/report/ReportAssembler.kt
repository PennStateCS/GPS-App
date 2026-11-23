package app.surrealar.util.diagnostics.report

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds the diagnostic ZIP from [ReportSources].
 *
 * [write]: flush the logs, collect every text section into memory (each under a timeout, each with a
 * [SectionStatus]), then stream the ZIP: the texts, the logs (copied line by line, never held in
 * memory), export-health.txt and manifest.txt last. Because the texts exist before anything is written,
 * a later summary can be rendered after the collectors (see [ReportResult.completenessSummary]).
 *
 * [export]: [write] into `<name>.zip.part`, reopen and check it against the entries written, then
 * rename it to the final name. A ZIP whose structure is broken is never kept.
 *
 * Callers run this off the main thread (the exporter uses Dispatchers.IO): it does blocking file I/O.
 */
object ReportAssembler {

    /** Above this many bytes of logs, only the last 24 h of each log file goes in (keeps the ZIP emailable). */
    const val MAX_LOG_BYTES = 20L * 1024 * 1024

    /** Longest a single section may take. Only suspending sources can be stopped; blocking ones run to the end. */
    const val DEFAULT_SECTION_TIMEOUT_MS = 10_000L

    /** Temp files older than this (left by a killed export) are deleted by the next export. */
    const val STALE_PART_MS = 60L * 60 * 1000

    const val PART_SUFFIX = ".part"
    const val HEALTH_FILE = "export-health.txt"
    const val MANIFEST_FILE = "manifest.txt"

    private const val LOGS_24H_REASON = "last 24 hours only (logs over the size limit)"

    /** Keeps lines from the last 24 h; stack-trace lines follow the line they belong to. */
    fun last24hFilter(nowMs: Long): (String) -> Boolean {
        var keep = false
        return { line ->
            val p = app.surrealar.util.diagnostics.LogLineFormat.parse(line)
            if (p != null) keep = nowMs - p.epochMs <= LogIndex.DAY_MS
            keep
        }
    }

    /**
     * Writes the report into [zipFile]. Source failures are recorded (the section says so, the export
     * goes on); ZIP write failures and cancellation propagate. [flush] returns false when the writers did
     * not confirm in time. [openOutput] lets tests simulate a failing disk.
     */
    suspend fun write(
        zipFile: File,
        src: ReportSources,
        flush: () -> Boolean = { DiagnosticsLogger.flush() },
        maxLogBytes: Long = MAX_LOG_BYTES,
        sectionTimeoutMs: Long = DEFAULT_SECTION_TIMEOUT_MS,
        openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    ): ReportResult {
        val run = SectionRunner(sectionTimeoutMs)
        ReportFailures.catchingBlocking { src.preparationStatuses() }.getOrNull()?.forEach(run::record)
        val flushStatus = runFlush(flush)

        // ── File sources (names only; contents are streamed below) ──
        val logs = run.files("source: log files", "no event log files") { src.logFiles() }
        val errors = run.files("source: error files", "no error log files") { src.errorFiles() }
        val traces = run.files("source: exit traces", "no saved ANR or crash traces") { src.exitTraceFiles() }
        val tracks = run.files("source: AR track files", "no AR track recordings (debug builds only)") { src.arTrackFiles() }
        val truncated = (logs + errors).sumOf { f -> ReportFailures.catchingBlocking { f.length() }.getOrDefault(0L) } > maxLogBytes
        val idx = run.logIndex(logs + errors, src.nowMs)

        // ── Text sections, into memory first (small) ──
        // Written in this order; the exit traces follow exits.txt.
        val head = listOf(
            run.textEntry("README.txt") { ReadmeCollector.render(src.reportId, logsTruncatedTo24h = truncated) },
            run.textEntry("diagnostic-summary.txt") {
                val first = ReportFailures.catching { FirstLookCollector.render(src.reportId, idx, src.liveState()) }.getOrElse {
                    val why = ReportFailures.describe(it)
                    run.record(SectionStatus("diagnostic-summary.txt: first look", SectionStatus.State.FAILED, 0, why))
                    "=== First look ===\n(failed: $why)\n\n"
                }
                first + src.summary()
            },
            run.textEntry("timeline.txt") { TimelineCollector.render(idx) },
            run.textEntry("sessions.txt") { SessionsCollector.render(idx) },
            run.textEntry("exits.txt") { ExitsCollector.render(idx, traces.map { it.name }) },
        )
        val sections = listOf(
            run.textEntry("device-health.txt") { DeviceHealthCollector.render(src.deviceHealth()) },
            run.textEntry("captures.txt") {
                val log = src.capturesLog()
                if (log == null) unavailable = "no captures log yet"
                CapturesCollector.render(CapturesCollector.read(log))
            },
            run.textEntry("gnss-stats.txt") { GnssStatsCollector.render(src.gnssStats()) },
            run.textEntry("data-health.txt") { DataHealthCollector.render(src.dataHealth(), idx.lastDbEvent?.raw) },
            run.textEntry("settings-snapshot.txt") { src.settingsSnapshot() },
            run.textEntry("permissions-status.txt") { src.permissions() },
            run.textEntry("app-state-summary.txt") { src.appState() },
            run.textEntry("coordinates.txt") { src.coordinates() },
            run.textEntry("model-integrity.txt") { src.models() },
            run.textEntry("ar-session-summary.txt") { src.arSession() },
            run.textEntry("map-diagnostics.txt") { src.mapDiagnostics() },
            run.textEntry("nmea-stream-diagnostics.txt") { src.nmeaStream() },
        )

        // ── Stream the ZIP ──
        // ZipOutputStream.close() skips closing its target when finishing fails, so the file is closed
        // here too (otherwise the temp file stays open and cannot be deleted).
        val raw = openOutput(zipFile)
        try {
            return ZipOutputStream(BufferedOutputStream(raw)).use { zos ->
                val z = ZipBuilder(zos, ReportRedactor::scrub)
                head.forEach { (name, text) -> z.text(name, text) }
                traces.forEach { z.file("exits/${it.name}", it) }
                sections.forEach { (name, text) -> z.text(name, text) }
                // Rolling logs (event history + crashes)
                val reason = if (truncated) LOGS_24H_REASON else null
                (logs + errors).forEach { z.file(it.name, it, if (truncated) last24hFilter(src.nowMs) else { _ -> true }, reason) }
                // AR alignment recordings (debug builds only; no coordinates)
                tracks.forEach { z.file(it.name, it) }

                val health = ExportHealth(run.statuses.toList(), flushStatus, z.omitted.toList(), z.truncated.toList())
                z.text(HEALTH_FILE, ExportHealthCollector.render(src.reportId, health))
                // Last: lists every entry above, and itself.
                val listed = z.entries.toList()
                z.text(MANIFEST_FILE, ReportFailures.catchingBlocking {
                    ManifestCollector.render(listed, src.reportId, src.nowMs, logsTruncatedTo24h = truncated)
                }.getOrElse { "Section failed to generate: ${ReportFailures.describe(it)}\n" })
                ReportResult(z.entries.toList(), health)
            }
        } finally {
            runCatching { raw.close() }                   // best effort; a real write error has already propagated
        }
    }

    /**
     * Writes the report to `dir/fileName.part`, validates it and renames it to `dir/fileName`.
     * Any failure deletes the temp file and returns [ExportOutcome.Failure]; the caller's own
     * cancellation deletes it and is rethrown. Stale `.part` files from earlier exports are removed first.
     */
    suspend fun export(
        dir: File,
        fileName: String,
        src: ReportSources,
        flush: () -> Boolean = { DiagnosticsLogger.flush() },
        maxLogBytes: Long = MAX_LOG_BYTES,
        sectionTimeoutMs: Long = DEFAULT_SECTION_TIMEOUT_MS,
        openOutput: (File) -> OutputStream = { FileOutputStream(it) },
        nowMs: Long = System.currentTimeMillis(),
        beforeValidate: (File) -> Unit = {},
    ): ExportOutcome {
        cleanStaleParts(dir, nowMs)
        val final = File(dir, fileName)
        val part = File(dir, fileName + PART_SUFFIX)
        try {
            val result = write(part, src, flush, maxLogBytes, sectionTimeoutMs, openOutput)
            beforeValidate(part)
            validate(part, result.entries)?.let { problem ->
                part.delete()
                DiagnosticsLogger.e(DiagTags.EXPORT, "Diagnostic report build failed reason=VALIDATION_FAILED: $problem")
                return ExportOutcome.Failure(ExportFailure.VALIDATION_FAILED, problem)
            }
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) {
                part.delete()
                DiagnosticsLogger.e(DiagTags.EXPORT, "Diagnostic report build failed reason=WRITE_FAILED: rename failed")
                return ExportOutcome.Failure(ExportFailure.WRITE_FAILED, "rename to the final name failed")
            }
            return ExportOutcome.Success(final, src.reportId, result.partial, result)
        } catch (e: CancellationException) {
            part.delete()
            if (!currentCoroutineContext().isActive) throw e
            DiagnosticsLogger.e(DiagTags.EXPORT, "Diagnostic report build failed reason=CANCELLED", e)
            return ExportOutcome.Failure(ExportFailure.CANCELLED, ReportFailures.describe(e))
        } catch (e: Exception) {
            part.delete()
            val reason = classify(e)
            DiagnosticsLogger.e(DiagTags.EXPORT, "Diagnostic report build failed reason=$reason", e)
            return ExportOutcome.Failure(reason, ReportFailures.describe(e))
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /** Reopens [zip]: null when its entries are exactly [expected] (names and uncompressed sizes), else why not. */
    fun validate(zip: File, expected: List<Pair<String, Long>>): String? {
        try {
            ZipFile(zip).use { z ->
                val actual = z.entries().toList()
                if (actual.size != expected.size) return "entry count ${actual.size}, expected ${expected.size}"
                val byName = actual.associateBy { it.name }
                for ((name, size) in expected) {
                    val e = byName[name] ?: return "missing entry $name"
                    if (e.size != size) return "entry $name has ${e.size} bytes, expected $size"
                }
                if (expected.none { it.first == MANIFEST_FILE }) return "no $MANIFEST_FILE"
                return null
            }
        } catch (e: Exception) {
            return "ZIP does not reopen: ${ReportFailures.describe(e)}"
        }
    }

    /** NO_SPACE when any cause says ENOSPC / "No space left", WRITE_FAILED for other I/O errors. */
    fun classify(e: Throwable): ExportFailure {
        var t: Throwable? = e
        var io = false
        while (t != null) {
            val m = t.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return ExportFailure.NO_SPACE
            if (t is IOException) io = true
            t = t.cause.takeIf { it !== t }
        }
        return if (io) ExportFailure.WRITE_FAILED else ExportFailure.UNKNOWN
    }

    /** Deletes `*.part` files in [dir] older than [STALE_PART_MS] (an export killed mid-write). */
    fun cleanStaleParts(dir: File, nowMs: Long) {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) && nowMs - it.lastModified() > STALE_PART_MS }
            ?.forEach { if (!it.delete()) DiagnosticsLogger.w(DiagTags.EXPORT, "Stale report temp file not deleted") }
    }

    private fun runFlush(flush: () -> Boolean): FlushStatus =
        ReportFailures.catchingBlocking { flush() }.fold(
            onSuccess = { if (it) FlushStatus(FlushStatus.State.FLUSHED) else FlushStatus(FlushStatus.State.NOT_CONFIRMED) },
            onFailure = {
                DiagnosticsLogger.w(DiagTags.EXPORT, "Report log flush failed", it)
                FlushStatus(FlushStatus.State.FAILED, ReportFailures.describe(it))
            },
        )

    /** Lets a section builder mark itself UNAVAILABLE (it still writes its text). */
    class SectionScope { var unavailable: String? = null }

    /** Runs sections under a timeout and keeps one [SectionStatus] per section, in order. */
    private class SectionRunner(private val timeoutMs: Long) {
        val statuses = mutableListOf<SectionStatus>()

        fun record(s: SectionStatus) { statuses += s }

        private fun elapsedSince(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

        /** The section's text, or a one-line "Section failed…" / "Section timed out…" in its place. */
        suspend fun text(name: String, build: suspend SectionScope.() -> String): String {
            val start = System.nanoTime()
            val scope = SectionScope()
            val result = ReportFailures.catching { withTimeoutOrNull(timeoutMs) { scope.build() } }
            val ms = elapsedSince(start)
            return result.fold(
                onSuccess = { text ->
                    if (text == null) {
                        DiagnosticsLogger.w(DiagTags.EXPORT, "Report section timed out section=$name timeoutMs=$timeoutMs")
                        record(SectionStatus(name, SectionStatus.State.TIMED_OUT, ms, "no answer within $timeoutMs ms"))
                        "Section timed out after $timeoutMs ms\n"
                    } else {
                        record(scope.unavailable?.let { SectionStatus(name, SectionStatus.State.UNAVAILABLE, ms, it) }
                            ?: SectionStatus(name, SectionStatus.State.OK, ms))
                        text
                    }
                },
                onFailure = {
                    DiagnosticsLogger.w(DiagTags.EXPORT, "Report section failed section=$name", it)
                    val why = ReportFailures.describe(it)
                    record(SectionStatus(name, SectionStatus.State.FAILED, ms, why))
                    "Section failed to generate: $why\n"
                },
            )
        }

        suspend fun textEntry(name: String, build: suspend SectionScope.() -> String): Pair<String, String> = name to text(name, build)

        /** A file-list source: FAILED when it throws, UNAVAILABLE ([emptyReason]) when it lists nothing. */
        fun files(name: String, emptyReason: String, list: () -> List<File>): List<File> {
            val start = System.nanoTime()
            val result = ReportFailures.catchingBlocking(list)
            val ms = elapsedSince(start)
            return result.fold(
                onSuccess = { files ->
                    record(if (files.isEmpty()) SectionStatus(name, SectionStatus.State.UNAVAILABLE, ms, emptyReason)
                        else SectionStatus(name, SectionStatus.State.OK, ms, "${files.size} file${if (files.size == 1) "" else "s"}"))
                    files
                },
                onFailure = {
                    DiagnosticsLogger.w(DiagTags.EXPORT, "Report file source failed source=\"$name\"", it)
                    record(SectionStatus(name, SectionStatus.State.FAILED, ms, ReportFailures.describe(it)))
                    emptyList()
                },
            )
        }

        /** One pass over the logs for the summaries; unreadable files are noted in the status. */
        fun logIndex(files: List<File>, nowMs: Long): LogIndex {
            val start = System.nanoTime()
            val unreadable = mutableListOf<String>()
            val idx = ReportFailures.catchingBlocking {
                LogIndex.build(files, nowMs) { f, t -> unreadable += "${f.name} (${t.javaClass.simpleName})" }
            }
            val ms = elapsedSince(start)
            return idx.fold(
                onSuccess = {
                    record(when {
                        unreadable.isNotEmpty() -> SectionStatus("log index", SectionStatus.State.FAILED, ms,
                            "unreadable: ${unreadable.joinToString(", ")}")
                        files.isEmpty() -> SectionStatus("log index", SectionStatus.State.UNAVAILABLE, ms, "no log files")
                        else -> SectionStatus("log index", SectionStatus.State.OK, ms)
                    })
                    it
                },
                onFailure = {
                    DiagnosticsLogger.w(DiagTags.EXPORT, "Report log index failed", it)
                    record(SectionStatus("log index", SectionStatus.State.FAILED, ms, ReportFailures.describe(it)))
                    LogIndex(nowMs)
                },
            )
        }
    }
}
