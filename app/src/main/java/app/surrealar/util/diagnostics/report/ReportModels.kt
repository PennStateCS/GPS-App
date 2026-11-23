package app.surrealar.util.diagnostics.report

/** Live GNSS / receiver state at export time (no coordinates). */
data class LiveState(
    val source: String?,
    val rtk: String?,
    val receiverLink: String?,
    val correctionAgeS: Double?,
    val corrections: String?,
    val freeStorageBytes: Long?,
)
/** device-health.txt inputs (null = unknown on this device). */
data class DeviceHealth(
    val batteryPercent: Int? = null, val charging: Boolean? = null, val powerSave: Boolean? = null, val thermal: String? = null,
    val storageFreeBytes: Long? = null, val storageTotalBytes: Long? = null, val appDataBytes: Long? = null,
    val ramAvailBytes: Long? = null, val ramTotalBytes: Long? = null, val lowMemory: Boolean? = null,
    val network: String? = null, val gpsProvider: Boolean? = null, val networkProvider: Boolean? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val batteryOptimizationIgnored: Boolean? = null, val mockLocationApp: String? = null,
)

/** gnss-stats.txt inputs. */
data class GnssStatsInput(
    val stats: app.surrealar.util.diagnostics.GnssFeedTracker.Stats?,
    val reconnects: Int?, val nmeaLines: Long?, val nmeaParseErrors: Long?, val sentenceCounts: String?,
)

/** data-health.txt inputs. */
data class DataHealth(val version: Int?, val rowCounts: Map<String, Long>, val integrity: String)

// ── Export reliability (export-health.txt) ──

/** How one report section (a text file or a file-list source) came out, and how long it took. */
data class SectionStatus(
    val name: String,
    val state: State,
    val elapsedMs: Long,
    /** FAILED: exception type + redacted, length-capped message. TIMED_OUT: the limit. UNAVAILABLE: why. */
    val detail: String? = null,
) {
    enum class State {
        OK,
        /** The source threw; the section holds a "Section failed…" line instead. */
        FAILED,
        /** The source did not answer within the section timeout. */
        TIMED_OUT,
        /** Nothing to report (no files, not applicable); not a problem in itself. */
        UNAVAILABLE,
    }
    val isProblem: Boolean get() = state == State.FAILED || state == State.TIMED_OUT
}

/** Result of flushing the log writers before the logs are copied. */
data class FlushStatus(val state: State, val detail: String? = null) {
    enum class State {
        FLUSHED,
        /** flush() returned false: the writer did not confirm within its timeout; the newest lines may be missing. */
        NOT_CONFIRMED,
        /** flush() threw. */
        FAILED,
    }
}

/** A file that should have been copied into the ZIP but was not. */
data class OmittedFile(val name: String, val reason: String)

/**
 * A file copied only in part. [expected] = by policy (logs over the size limit keep the last 24 h);
 * otherwise the copy was cut short (read error) and the export counts as partial.
 */
data class TruncatedFile(val name: String, val originalBytes: Long, val includedBytes: Long, val reason: String, val expected: Boolean)

/** Everything export-health.txt reports. */
data class ExportHealth(
    val sections: List<SectionStatus>,
    val flush: FlushStatus,
    val omitted: List<OmittedFile> = emptyList(),
    val truncated: List<TruncatedFile> = emptyList(),
) {
    enum class Verdict { COMPLETE, PARTIAL }

    /** The problems that make the export [Verdict.PARTIAL], one short phrase each (empty = complete). */
    val problems: List<String> get() = buildList {
        sections.filter { it.isProblem }.forEach { s ->
            add("${s.name} ${s.state}" + (s.detail?.let { " (${it.substringBefore(':')})" } ?: ""))
        }
        when (flush.state) {
            FlushStatus.State.FLUSHED -> Unit
            FlushStatus.State.NOT_CONFIRMED -> add("log flush not confirmed")
            FlushStatus.State.FAILED -> add("log flush failed" + (flush.detail?.let { " (${it.substringBefore(':')})" } ?: ""))
        }
        omitted.forEach { add("${it.name} omitted (${it.reason})") }
        truncated.filterNot { it.expected }.forEach { add("${it.name} cut short (${it.reason})") }
    }

    val verdict: Verdict get() = if (problems.isEmpty()) Verdict.COMPLETE else Verdict.PARTIAL

    /**
     * Short "Export completeness" block meant for diagnostic-summary.txt. The summary is still written
     * before the logs are copied, so for now README.txt points at export-health.txt; a summary written
     * after the collectors run can append this block directly.
     */
    fun completenessSummary(): String = buildString {
        appendLine("=== Export completeness ===")
        appendLine("Verdict  : $verdict")
        val p = problems
        if (p.isEmpty()) appendLine("Problems : (none)")
        else {
            appendLine("Problems : ${p.size}")
            p.take(MAX_SUMMARY_PROBLEMS).forEach { appendLine("  - $it") }
            if (p.size > MAX_SUMMARY_PROBLEMS) appendLine("  - … ${p.size - MAX_SUMMARY_PROBLEMS} more")
        }
        appendLine("Details  : export-health.txt")
    }

    private companion object { const val MAX_SUMMARY_PROBLEMS = 8 }
}

/** What [ReportAssembler.write] produced: every ZIP entry (name, uncompressed bytes) in order, plus its health. */
data class ReportResult(val entries: List<Pair<String, Long>>, val health: ExportHealth) {
    val partial: Boolean get() = health.verdict == ExportHealth.Verdict.PARTIAL
    fun completenessSummary(): String = health.completenessSummary()
}

/** Why a diagnostic export produced no ZIP. */
enum class ExportFailure {
    /** The device ran out of storage while writing. */
    NO_SPACE,
    /** The ZIP could not be created, written or renamed (I/O error). */
    WRITE_FAILED,
    /** The written ZIP did not reopen or did not match the entries that were written. */
    VALIDATION_FAILED,
    /** A cancellation that did not come from the caller's own cancellation (the caller's own is rethrown). */
    CANCELLED,
    UNKNOWN,
}

/** Result of [ReportAssembler.export]: a validated ZIP (possibly with failed sections) or no ZIP at all. */
sealed class ExportOutcome {
    /** A structurally valid ZIP at its final name. [partial] = some sections failed or files are missing. */
    data class Success(val file: java.io.File, val reportId: String, val partial: Boolean, val result: ReportResult) : ExportOutcome()
    /** No ZIP was kept (the temp file is deleted). [detail] = exception type + redacted short message. */
    data class Failure(val reason: ExportFailure, val detail: String? = null) : ExportOutcome()
}