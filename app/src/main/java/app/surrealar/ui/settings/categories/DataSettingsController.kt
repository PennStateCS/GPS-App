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
) : SettingsCategoryController {

    // Coordinate backup import state (survives category switches, like the fragment fields it replaces)
    private var pendingImportUri: Uri? = null
    private var coordinatesImportJob: Job? = null
    private var importProcessed: Int = 0
    private var importTotal: Int = 0

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
                app.surrealar.util.diagnostics.DataOpLog.run("CSV export", "csv", { n: Int -> n }) {
                    val coordSettings = settingsRepo.coordinateSystemSettings.first()
                    val points = withContext(Dispatchers.IO) { repository.getAllCoordinatesList() }
                    // Per-point datum/projection/geoid conversion is CPU work: keep it off the main thread.
                    val csv = withContext(Dispatchers.Default) {
                        app.surrealar.data.export.PointsCsvExporter.build(points) {
                            coordinateSystemService.convertCoordinate(it, coordSettings)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(csv.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                    points.size
                }
            }.onSuccess { n ->
                fragment.showSettingsMessage("Points CSV exported: $n points", Snackbar.LENGTH_LONG)
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
        if (existing == 0) { launchImportCoordinates(uri, replace = false); return }
        pendingImportUri = uri
        if (!fragment.isAdded) return
        // Duplicate-ID policy: merge overwrites matching IDs (and reports the count); replace clears
        // first. The user must confirm before any existing data is overwritten.
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.import_select_file_title))
            .setMessage(fragment.getString(R.string.import_merge_replace_message))
            .setPositiveButton(R.string.import_merge) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, false) } }
            .setNeutralButton(R.string.import_replace) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, true) } }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    /**
     * Second confirmation for a Replace whose backup has invalid rows, now that the counts are known.
     * Cancel (or a dismissed dialog / gone screen) keeps the existing points.
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
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }.also { DiagnosticsLogger.i(DiagTags.DATA, "Backup replace confirmation answered decision=$it") }
    }

    private fun launchImportCoordinates(uri: Uri, replace: Boolean) {
        if (coordinatesImportJob?.isActive == true) return
        // Save scope: leaving Settings must not cancel the import mid-write. Cancel still works
        // through the job reference.
        val appContext = fragment.requireContext().applicationContext
        coordinatesImportJob = saveScope.launch { importCoordinates(appContext, uri, replace) }
    }

    private suspend fun importCoordinates(appContext: Context, uri: Uri, replace: Boolean) {
        showImportProgress(true, 0, "Scanning…")
        importProcessed = 0; importTotal = 0
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
            importBackup(raw, replace) { plan, existingCount -> confirmPartialReplace(plan, existingCount) }
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
        try {
            root?.findViewById<LinearLayout>(R.id.import_progress_container)?.let { c ->
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
}
