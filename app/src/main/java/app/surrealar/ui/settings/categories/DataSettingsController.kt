package app.surrealar.ui.settings.categories

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.usecase.ExportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase.PartialReplaceDecision
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.settingsWarn
import app.surrealar.ui.settings.common.showSettingsMessage
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Settings → Data: JSON backup export, points CSV export and backup import (merge / replace, partial
 * replace confirmation, progress, cancel).
 *
 * The three document launchers are registered by the fragment at construction (as Activity Result
 * APIs require) and their results are routed here ([onExportBackupResult], [onExportPointsCsvResult],
 * [onImportResult]). Exports and the import run on [saveScope], so leaving the category or Settings
 * does not cancel a write the user started; the import job is kept here so Cancel works from a later
 * visit to this category.
 *
 * Lifecycle expectations:
 * - Long-running file work uses the application context captured when it starts, never the fragment.
 * - UI from that work goes only to the CURRENT Data view ([root]; progress is re-shown when the
 *   category is opened again during an import) or to the fragment's view for snackbars
 *   ([showSettingsMessage] skips them when the fragment has no view).
 * - A launcher result is delivered to the fragment instance that is current at that moment (the
 *   Activity Result registry re-delivers to a recreated fragment's launcher), so the new fragment's
 *   controller handles it; a null uri (picker cancelled) does nothing.
 * - Partial-replace confirmation (a Replace whose backup has invalid rows) is asked whenever the
 *   fragment is still added, even if the user switched to another category (the dialog belongs to the
 *   activity). If the fragment is gone when it is needed, or is destroyed while the dialog is open
 *   (e.g. rotation), the answer is CANCEL: nothing is deleted and the existing points are kept.
 */
internal class DataSettingsController(
    private val fragment: Fragment,
    private val repository: CoordinateRepository,
    private val settingsRepo: CoordinateDisplaySettingsRepository,
    private val coordinateSystemService: CoordinateSystemService,
    private val exportBackup: ExportCoordinateBackupUseCase,
    private val importBackup: ImportCoordinateBackupUseCase,
    /** Outlives the screen: an export or import the user started completes after they leave. */
    private val saveScope: CoroutineScope,
    private val exportBackupLauncher: ActivityResultLauncher<String>,
    private val exportPointsCsvLauncher: ActivityResultLauncher<String>,
    private val importBackupLauncher: ActivityResultLauncher<Array<String>>,
    /** The import use case call; a parameter so tests can drive the partial-replace confirmation. */
    private val runImport: suspend (raw: String, replace: Boolean,
                                    confirm: suspend (app.surrealar.data.backup.BackupImportPlan, Int) -> PartialReplaceDecision,
    ) -> app.surrealar.data.backup.BackupImportPlan = { raw, replace, confirm -> importBackup(raw, replace, confirm) },
) : SettingsCategoryController {

    // Coordinate backup import state (survives category switches, like the fragment fields it replaces)
    private var pendingImportUri: Uri? = null
    private var coordinatesImportJob: Job? = null
    /** Last progress shown for the running import, re-shown when the Data view is built again. */
    private var importProgressPercent: Int = 0
    private var importProgressStatus: String = ""

    /** This category's view while it is shown (progress updates go to it); null otherwise. */
    private var root: View? = null

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_data, container, false)
        try {
            view.findViewById<Button>(R.id.btn_export_coordinates)?.setOnClickListener {
                try {
                    exportBackupLauncher.launch("coordinates_backup_${System.currentTimeMillis()}.json")
                } catch (e: Exception) { settingsError("Backup export launch failed", e) }
            }
            view.findViewById<Button>(R.id.btn_export_points_csv)?.setOnClickListener {
                try {
                    exportPointsCsvLauncher.launch("survey_points_${System.currentTimeMillis()}.csv")
                } catch (e: Exception) { settingsError("CSV export launch failed", e) }
            }
            // Export option: "Position reference" column in every CSV (off = the original six columns).
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_csv_position_reference)?.let { sw ->
                categoryScope.launch {
                    runCatching { settingsRepo.exportCsvPositionReference.first() }.onSuccess { sw.isChecked = it }
                    sw.setOnCheckedChangeListener { _, checked ->
                        categoryScope.launch {
                            runCatching { settingsRepo.setExportCsvPositionReference(checked) }
                                .onFailure { settingsError("CSV export option save failed", it) }
                        }
                    }
                }
            }
            view.findViewById<Button>(R.id.btn_import_coordinates)?.setOnClickListener {
                try {
                    // Most providers label .json as application/json; the broader types are a
                    // fallback for providers that mislabel it (the parser still validates content).
                    importBackupLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
                } catch (e: Exception) { settingsError("Import launch failed", e) }
            }
            view.findViewById<Button>(R.id.btn_cancel_import)?.setOnClickListener { try { cancelActiveImport() } catch (e: Exception) { settingsError("Import cancel failed", e) } }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=data", e)
        }
        root = view
        // An import still running from an earlier visit: show its progress (and the Cancel button) again.
        if (coordinatesImportJob?.isActive == true) showImportProgress(true, importProgressPercent, importProgressStatus)
        return view
    }

    override fun onDestroyView() {
        root = null
    }

    // ───────────────────────────── Launcher results ─────────────────────────────
    /** Backup save dialog result. A null uri means the user cancelled — do nothing (no error). */
    fun onExportBackupResult(uri: Uri?) { if (uri != null) writeBackupTo(uri) }

    /** Points CSV save dialog result. A null uri means cancelled. */
    fun onExportPointsCsvResult(uri: Uri?) { if (uri != null) writePointsCsvTo(uri) }

    /** Backup open dialog result. A null uri means cancelled. */
    fun onImportResult(uri: Uri?) { if (uri != null) onImportDocumentPicked(uri) }

    // ───────────────────────────── Import / Export ─────────────────────────────
    /** Writes the official full JSON backup to the document the user chose in the save dialog. */
    private fun writeBackupTo(uri: Uri) {
        // Captured now: the save scope outlives the fragment, so requireContext() may fail later.
        val appContext = fragment.requireContext().applicationContext
        saveScope.launch {
            runCatching {
                val appVersion = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                }.getOrNull()
                // Use case loads the data and builds the JSON (and logs its own failure); the
                // fragment owns the URI write, logged here.
                val result = exportBackup(appVersion)
                try {
                    withContext(Dispatchers.IO) {
                        // "wt" truncates: overwriting a longer existing file must not leave its tail behind.
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(result.json.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                } catch (e: Exception) {
                    settingsError("Backup export write failed rows=${result.coordinateCount} dest=SAF", e, DiagTags.EXPORT)
                    throw e
                }
                result.coordinateCount
            }.onSuccess { n ->
                fragment.showSettingsMessage("JSON backup exported: $n coordinates", Snackbar.LENGTH_LONG)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                fragment.showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    /** Writes all points as CSV in the configured coordinate system, units and height reference. */
    private fun writePointsCsvTo(uri: Uri) {
        val appContext = fragment.requireContext().applicationContext
        saveScope.launch {
            runCatching {
                // DataOpLog: start / done rows=n / failed (E, with the exception) lines.
                app.surrealar.util.diagnostics.DataOpLog.run("CSV export", "csv",
                    { r: app.surrealar.data.export.PointsCsvExporter.Result -> r.rowCount }) {
                    val coordSettings = settingsRepo.coordinateSystemSettings.first()
                    // Unreadable option → the default six-column file.
                    val withReference = runCatching { settingsRepo.exportCsvPositionReference.first() }.getOrDefault(false)
                    val points = withContext(Dispatchers.IO) { repository.getAllCoordinatesList() }
                    // Per-point datum/projection/geoid conversion is CPU work: keep it off the main thread. Off the
                    // main thread the SPCS2022 zone lookup loads the catalog synchronously (never "loading").
                    val result = withContext(Dispatchers.Default) {
                        app.surrealar.data.export.PointsCsvExporter.export(points,
                            app.surrealar.data.export.PointsCsvExporter.Layout.of(coordSettings), withReference) {
                            coordinateSystemService.convertCoordinate(it, coordSettings)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(result.text.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                    if (result.unavailableCount > 0) {
                        DiagnosticsLogger.w(DiagTags.EXPORT, "CSV export rows without position unavailable=${result.unavailableCount} rows=${result.rowCount}")
                    }
                    result
                }
            }.onSuccess { r ->
                fragment.showSettingsMessage(csvExportMessage(r), Snackbar.LENGTH_LONG)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                fragment.showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    /** Routes a picked backup document into the import confirmation/merge flow. */
    private fun onImportDocumentPicked(uri: Uri) {
        fragment.lifecycleScope.launch {
            try {
                prepareImportCoordinatesWithConfirmation(uri)
            } catch (e: Exception) {
                settingsError("Backup import failed stage=prepare", e, DiagTags.DATA)
                fragment.showSettingsMessage("Import failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    // --- Import pipeline (full JSON backup; basic JSON arrays are still auto-detected) ---
    private suspend fun prepareImportCoordinatesWithConfirmation(uri: Uri) {
        val existing = withContext(Dispatchers.IO) { repository.getAllCoordinatesList().size }
        // Captured now (still attached: this runs on the fragment's lifecycleScope): the import and the
        // dialog's buttons may run after the fragment has gone.
        val appContext = fragment.requireContext().applicationContext
        if (existing == 0) { launchImportCoordinates(appContext, uri, replace = false); return }
        pendingImportUri = uri
        if (!fragment.isAdded) return
        // Duplicate-ID policy: merge overwrites matching IDs (and reports the count); replace clears
        // first. The user must confirm before any existing data is overwritten.
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.import_select_file_title))
            .setMessage(fragment.getString(R.string.import_merge_replace_message))
            .setPositiveButton(R.string.import_merge) { _, _ -> pendingImportUri?.let { launchImportCoordinates(appContext, it, false) } }
            .setNeutralButton(R.string.import_replace) { _, _ -> pendingImportUri?.let { launchImportCoordinates(appContext, it, true) } }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
        dismissWhenFragmentDestroyed(dialog)
    }

    /**
     * Dismisses [dialog] when the fragment is destroyed while it is open (e.g. rotation), so it does
     * not outlive its activity, and runs [onDismiss] on every dismissal (buttons included).
     */
    private fun dismissWhenFragmentDestroyed(dialog: android.app.Dialog, onDismiss: () -> Unit = {}) {
        val lifecycle = fragment.lifecycle
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            lifecycle.removeObserver(observer)
            onDismiss()
        }
        lifecycle.addObserver(observer)
    }

    /**
     * Second confirmation for a Replace whose backup has invalid rows, now that the counts are known.
     * Cancel (or a dismissed dialog / gone or destroyed fragment) keeps the existing points.
     */
    private suspend fun confirmPartialReplace(
        plan: app.surrealar.data.backup.BackupImportPlan, existingCount: Int,
    ): PartialReplaceDecision {
        DiagnosticsLogger.w(DiagTags.DATA, "Backup replace needs confirmation valid=${plan.insertCount} " +
            "skipped=${plan.skippedCount} existing=$existingCount")
        return withContext(Dispatchers.Main) {
            if (!fragment.isAdded) return@withContext PartialReplaceDecision.CANCEL
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                fun answer(d: PartialReplaceDecision) {
                    if (cont.isActive) cont.resumeWith(Result.success(d))
                }
                val dialog = AlertDialog.Builder(fragment.requireContext())
                    .setTitle("Backup has invalid entries")
                    .setMessage("Only ${plan.insertCount} of ${plan.insertCount + plan.skippedCount} entries in this backup " +
                        "are valid (${plan.skippedCount} would be skipped).\n\nReplace deletes all $existingCount " +
                        "coordinates on this device and keeps only the ${plan.insertCount} valid ones. This cannot be undone.")
                    .setPositiveButton("Merge instead") { _, _ -> answer(PartialReplaceDecision.MERGE_INSTEAD) }
                    .setNeutralButton("Replace anyway") { _, _ -> answer(PartialReplaceDecision.REPLACE) }
                    .setNegativeButton(R.string.import_cancel) { _, _ -> answer(PartialReplaceDecision.CANCEL) }
                    .setOnCancelListener { answer(PartialReplaceDecision.CANCEL) }
                    .show()
                // Destroyed with the dialog open (rotation): answer CANCEL instead of waiting forever
                // (the import job would hang and hold the old fragment). Any other dismissal also
                // answers CANCEL; a button answer comes first, so this is then a no-op.
                dismissWhenFragmentDestroyed(dialog) { answer(PartialReplaceDecision.CANCEL) }
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }.also { DiagnosticsLogger.i(DiagTags.DATA, "Backup replace confirmation answered decision=$it") }
    }

    private fun launchImportCoordinates(appContext: Context, uri: Uri, replace: Boolean) {
        if (coordinatesImportJob?.isActive == true) return
        // Save scope: leaving Settings must not cancel the import mid-write. Cancel still works
        // through the job reference.
        coordinatesImportJob = saveScope.launch { importCoordinates(appContext, uri, replace) }
    }

    private suspend fun importCoordinates(appContext: Context, uri: Uri, replace: Boolean) {
        showImportProgress(true, 0, "Scanning…")
        runCatching {
            // Fragment owns the URI read; the use case parses, plans (duplicates/missing models),
            // and writes via the repositories.
            val raw = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { inp ->
                        BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
                    } ?: error("Unable to open input stream")
                }
            } catch (e: Exception) {
                // The use case logs parse/write failures itself (DataOpLog); the file read is logged here.
                settingsError("Backup import failed stage=readFile", e, DiagTags.DATA)
                throw e
            }
            showImportProgress(true, 70, "Writing…")
            runImport(raw, replace) { plan, existingCount -> confirmPartialReplace(plan, existingCount) }
        }.onSuccess { plan ->
            showImportProgress(false, 100, when {
                plan.cancelled -> "Canceled"
                plan.isNoOp -> "Nothing to import"
                else -> "Completed"
            })
            // Counts only: the lists hold point names.
            if (plan.skippedInvalid.isNotEmpty() || plan.missingModelRefs.isNotEmpty()) {
                DiagnosticsLogger.w(DiagTags.DATA, "Import skipped invalid rows skipped=${plan.skippedInvalid.size} missingModels=${plan.missingModelRefs.size}")
            }
            fragment.showSettingsMessage(plan.summaryMessage(), Snackbar.LENGTH_LONG)
        }.onFailure { e ->
            if (e is CancellationException) {
                showImportProgress(false, 0, "Canceled")
            } else {
                showImportProgress(false, 0, "Error")
                fragment.showSettingsMessage("Import failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
        coordinatesImportJob = null
    }

    private fun showImportProgress(visible: Boolean, percent: Int, status: String) {
        importProgressPercent = percent
        importProgressStatus = status
        try {
            // A MaterialCardView: looking it up as LinearLayout threw ClassCastException (caught below),
            // so the progress card - and its Cancel button - never appeared.
            root?.findViewById<View>(R.id.import_progress_container)?.let { c ->
                c.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) {
                    c.findViewById<ProgressBar>(R.id.progress_import)?.progress = percent.coerceIn(0, 100)
                    c.findViewById<TextView>(R.id.text_import_progress)?.text = status
                }
            }
        } catch (e: Exception) {
            settingsWarn("Import progress display failed", e)
        }
    }

    private fun cancelActiveImport() {
        try {
            coordinatesImportJob?.takeIf { it.isActive }?.cancel()?.also {
                fragment.showSettingsMessage(fragment.getString(R.string.import_cancel))
            }
        } catch (e: Exception) {
            settingsWarn("Import cancel failed", e)
        }
    }

    internal companion object {
        /** Snackbar after a CSV export: the row count, plus how many rows have no position in the chosen system. */
        fun csvExportMessage(r: app.surrealar.data.export.PointsCsvExporter.Result): String =
            if (r.unavailableCount == 0) "Points CSV exported: ${r.rowCount} points"
            else "Points CSV exported: ${r.rowCount} points; ${r.unavailableCount} without a position in the selected " +
                "coordinate system (blank, reason in the System column)"
    }
}
