package app.surrealar.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import app.surrealar.BuildConfig
import app.surrealar.R
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import app.surrealar.util.diagnostics.ReportNaming
import app.surrealar.util.diagnostics.incident.AndroidIncidentContextInputs
import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentContextCollector
import app.surrealar.util.diagnostics.incident.IncidentMarker
import app.surrealar.util.diagnostics.incident.IncidentStore
import app.surrealar.util.diagnostics.report.ExportOutcome
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → About diagnostics UI: the "Mark Problem" flow, the report share intent, and friendly
 * export-failure messages (never exception text). Kept out of SettingsFragment so the fragment only
 * wires buttons to it.
 */
object DiagnosticExportUi {

    /** What went wrong with an export, as the tester sees it. */
    enum class ExportFailure(@StringRes val messageRes: Int) {
        STORAGE_FULL(R.string.diag_export_failed_storage_full),
        WRITE_FAILED(R.string.diag_export_failed_write),
        VALIDATION_FAILED(R.string.diag_export_failed_validation),
        CANCELLED(R.string.diag_export_failed_cancelled),
        NO_SHARE_APP(R.string.diag_export_failed_no_share_app),
        UNKNOWN(R.string.diag_export_failed_unknown),
    }

    /** Below this much free space a failed export is reported as "storage full". */
    const val LOW_STORAGE_BYTES = 20L * 1024 * 1024

    /** An incident older than this is not mentioned in the share text. */
    const val RECENT_INCIDENT_MS = 24L * 60 * 60 * 1000

    /** Maps an export failure ([t] null when the exporter only reported "no report") to a message. */
    fun classify(t: Throwable?, freeBytes: Long?): ExportFailure {
        val lowStorage = freeBytes != null && freeBytes in 0 until LOW_STORAGE_BYTES
        return when {
            t is CancellationException -> ExportFailure.CANCELLED
            t is ActivityNotFoundException -> ExportFailure.NO_SHARE_APP
            isNoSpace(t) || lowStorage -> ExportFailure.STORAGE_FULL
            t is IOException || t is SecurityException -> ExportFailure.WRITE_FAILED
            else -> ExportFailure.UNKNOWN
        }
    }

    private fun isNoSpace(t: Throwable?): Boolean {
        var c = t
        repeat(5) {
            val m = c?.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            c = c?.cause ?: return false
        }
        return false
    }

    /** The tester-facing failure for an export that produced no ZIP (never the exception text). */
    fun failureFor(reason: app.surrealar.util.diagnostics.report.ExportFailure): ExportFailure = when (reason) {
        app.surrealar.util.diagnostics.report.ExportFailure.NO_SPACE -> ExportFailure.STORAGE_FULL
        app.surrealar.util.diagnostics.report.ExportFailure.WRITE_FAILED -> ExportFailure.WRITE_FAILED
        app.surrealar.util.diagnostics.report.ExportFailure.VALIDATION_FAILED -> ExportFailure.VALIDATION_FAILED
        app.surrealar.util.diagnostics.report.ExportFailure.CANCELLED -> ExportFailure.CANCELLED
        app.surrealar.util.diagnostics.report.ExportFailure.UNKNOWN -> ExportFailure.UNKNOWN
    }

    /**
     * The message shown after an export: the share failure if sharing failed, the export failure if no
     * ZIP was made, "ready (some sections missing)" for a partial report, else "ready".
     */
    @StringRes
    fun resultMessage(outcome: ExportOutcome, shareFailure: ExportFailure?): Int = when {
        outcome is ExportOutcome.Failure -> failureFor(outcome.reason).messageRes
        shareFailure != null -> shareFailure.messageRes
        outcome is ExportOutcome.Success && outcome.partial -> R.string.diag_export_ready_partial
        else -> R.string.diag_export_ready
    }

    /** Added to the share text of a partial report. */
    const val PARTIAL_NOTE = "Note: some sections could not be collected; details in export-health.txt."

    /** "HH:mm" in the device's zone, as shown in the confirmation. */
    fun markTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * Share-intent body: the three prompts and report ID, [PARTIAL_NOTE] for a [partial] report, then the
     * latest marked problem (if marked in the last [RECENT_INCIDENT_MS]) with the tester's note when there
     * is one. The note is also in the ZIP (incidents.txt, diagnostic-summary.txt), so it is not lost if
     * the ZIP is shared another way.
     */
    fun shareText(
        reportId: String, latest: IncidentMarker?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), partial: Boolean = false,
    ): String {
        val base = ReportNaming.body(reportId) + if (partial) "\n$PARTIAL_NOTE\n" else ""
        val m = latest?.takeIf { nowMs - it.epochMs in 0..RECENT_INCIDENT_MS } ?: return base
        return buildString {
            append(base)
            append("\nProblem marked at ").append(markTime(m.epochMs, zone))
            append(" (incident ").append(m.id).append(")\n")
            m.description?.let { append("Note: ").append(it).append('\n') }
        }
    }

    /**
     * Shares [file] through the FileProvider. Returns null when the chooser opened, else the failure.
     * A failure is logged (with the exception) here; the caller only shows the message.
     */
    suspend fun share(context: Context, file: File, reportId: String, partial: Boolean = false): ExportFailure? = try {
        val latest = withContext(Dispatchers.IO) { runCatching { IncidentStore.shared.latest() }.getOrNull() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            ReportNaming.recipients(BuildConfig.DIAG_REPORT_EMAIL)
                .takeIf { it.isNotEmpty() }?.let { putExtra(Intent.EXTRA_EMAIL, it) }
            putExtra(Intent.EXTRA_SUBJECT, ReportNaming.subject(android.os.Build.MODEL ?: "device",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis()))
            putExtra(Intent.EXTRA_TEXT, shareText(reportId, latest, System.currentTimeMillis(), partial = partial))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.diag_export_share_chooser)))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLogger.e(DiagTags.EXPORT, "Diagnostic report share failed", e)
        classify(e, context.cacheDir?.usableSpace)
    }

    /** Message for an export that produced no report (the exporter logs its own failure). */
    fun noReportFailure(context: Context): ExportFailure = classify(null, context.cacheDir?.usableSpace)

    /**
     * Runs the export and shares the ZIP. Returns the message to show (see [resultMessage]); the
     * exporter and [share] log their own failures.
     */
    @StringRes
    suspend fun exportAndShare(context: Context): Int {
        val outcome = app.surrealar.util.DiagnosticReportExporter.export(context)
        val shareFailure = (outcome as? ExportOutcome.Success)?.let { share(context, it.file, it.reportId, it.partial) }
        return resultMessage(outcome, shareFailure)
    }

    /**
     * The "Mark Problem" dialog: an optional note, "Mark" saves it, "Skip note" marks without one,
     * back / tapping outside cancels. [onMark] gets the note (null when skipped or blank).
     */
    fun showMarkProblemDialog(context: Context, onMark: (String?) -> Unit) {
        val body = LayoutInflater.from(context).inflate(R.layout.dialog_mark_problem, null)
        val edit = body.findViewById<TextInputEditText>(R.id.edit_mark_problem_note)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.diag_mark_dialog_title)
            .setMessage(R.string.diag_mark_dialog_message)
            .setView(body)
            .setPositiveButton(R.string.diag_mark_dialog_mark) { _, _ -> onMark(edit.text?.toString()) }
            .setNegativeButton(R.string.diag_mark_dialog_skip) { _, _ -> onMark(null) }
            .show()
    }

    /**
     * Snapshots the app state and saves an incident. Returns null (and logs a warning, without the
     * note) if it could not be saved. Not cancellable: leaving Settings right after "Mark" still saves it.
     */
    suspend fun markProblem(context: Context, description: String?): IncidentMarker? = try {
        withContext(NonCancellable) {
            val snapshot: IncidentContext = IncidentContextCollector.collect(AndroidIncidentContextInputs(context))
            IncidentStore.shared.mark(description, snapshot)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLogger.w(DiagTags.DIAG, "Incident mark failed", e)
        null
    }
}
