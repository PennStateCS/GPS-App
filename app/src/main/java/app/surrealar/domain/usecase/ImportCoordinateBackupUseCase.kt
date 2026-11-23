package app.surrealar.domain.usecase

import app.surrealar.data.backup.BackupImportPlan
import app.surrealar.data.backup.BackupImportPlanner
import app.surrealar.data.backup.ImportMode
import app.surrealar.data.export.CoordinateBackup
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject

/**
 * Imports a full JSON backup (or a legacy basic JSON array, auto-detected) and applies it.
 *
 * Parsing goes through [CoordinateBackup] for full backups; legacy arrays are parsed here and each
 * row is gated by [ValidateCoordinateForSaveUseCase]. Duplicate handling, missing-model detection, and
 * the user-facing summary are delegated to the pure [BackupImportPlanner]; this use case only loads the
 * local id sets and writes the result. The duplicate policy is preserved: `REPLACE` clears existing
 * coordinates first, `MERGE` overwrites rows with matching ids. Missing model references are reported
 * on the returned [BackupImportPlan], not treated as failures. Reading the `Uri` stays in the UI layer.
 */
class ImportCoordinateBackupUseCase @Inject constructor(
    private val coordinateRepository: CoordinateRepository,
    private val modelRepository: ModelRepository,
    private val validate: ValidateCoordinateForSaveUseCase,
) {

    /** What to do with a replace that would skip invalid rows (see [BackupImportPlan.needsPartialReplaceConfirmation]). */
    enum class PartialReplaceDecision { REPLACE, MERGE_INSTEAD, CANCEL }

    /**
     * @param confirmPartialReplace asked, with the plan and the number of local points that would be
     *   deleted, before a replace that skips invalid rows. Defaults to CANCEL, so a caller that can't
     *   ask never wipes data on a damaged backup.
     */
    suspend operator fun invoke(
        raw: String,
        replace: Boolean,
        confirmPartialReplace: suspend (plan: BackupImportPlan, existingCount: Int) -> PartialReplaceDecision =
            { _, _ -> PartialReplaceDecision.CANCEL },
    ): BackupImportPlan =
        app.surrealar.util.diagnostics.DataOpLog.run("Backup import", if (replace) "json/replace" else "json/merge",
            { it.toInsert.size }) { plan(raw, replace, confirmPartialReplace) }

    private suspend fun plan(
        raw: String, replace: Boolean,
        confirmPartialReplace: suspend (BackupImportPlan, Int) -> PartialReplaceDecision,
    ): BackupImportPlan = withContext(Dispatchers.IO) {
        val existingIds = coordinateRepository.getAllCoordinatesList().mapTo(HashSet()) { it.id }
        // The backup carries model metadata but not the model files; missing-model detection checks
        // the LOCAL model database.
        val existingModelIds = modelRepository.getAllModels().first().mapTo(HashSet()) { it.id }

        val parsed: List<Coordinate>
        val skipped: List<String>
        if (CoordinateBackup.isFullBackup(raw)) {
            val r = CoordinateBackup.parse(raw)
            parsed = r.coordinates
            skipped = r.skippedInvalid
        } else {
            val valid = mutableListOf<Coordinate>()
            val bad = mutableListOf<String>()
            parseLegacyJsonArray(raw, bad).forEach { c ->
                if (validate(c).isValid) valid += c else bad += "'${c.name}' (${c.id})"
            }
            parsed = valid
            skipped = bad
        }

        val mode = if (replace) ImportMode.REPLACE else ImportMode.MERGE
        var plan = BackupImportPlanner.plan(parsed, skipped, existingIds, existingModelIds, mode)

        if (plan.needsPartialReplaceConfirmation && existingIds.isNotEmpty()) {
            when (confirmPartialReplace(plan, existingIds.size)) {
                PartialReplaceDecision.REPLACE -> Unit
                PartialReplaceDecision.MERGE_INSTEAD ->
                    plan = BackupImportPlanner.plan(parsed, skipped, existingIds, existingModelIds, ImportMode.MERGE)
                PartialReplaceDecision.CANCEL -> return@withContext plan.copy(cancelled = true)
            }
        }

        if (!plan.isNoOp) {
            // Replace is one transaction: a failed write leaves the existing points untouched.
            if (plan.mode == ImportMode.REPLACE) coordinateRepository.replaceAll(plan.toInsert)
            else coordinateRepository.insertAll(plan.toInsert)
        }
        plan
    }

    /**
     * Parses a legacy "basic" JSON array (id/name/lat/lon/alt/timestamp/icon/color). Malformed rows are
     * skipped and added to [skipped] so they count in the import summary.
     */
    private fun parseLegacyJsonArray(raw: String, skipped: MutableList<String>): List<Coordinate> {
        val arr = JSONArray(raw)
        val list = mutableListOf<Coordinate>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = obj.optString("name", id)
                val lat = obj.optDouble("latitude")
                val lon = obj.optDouble("longitude")
                val alt = obj.optDouble("altitude", 0.0)
                val ts = obj.optLong("timestamp", now)
                val rawIcon = obj.optString("icon", "ic_pin")
                val icon = when (rawIcon) {
                    "ic_menu_camera" -> "ic_pin"
                    "ic_menu_gallery" -> "ic_star"
                    "ic_menu_slideshow" -> "ic_home"
                    else -> rawIcon
                }
                val color = obj.optInt("color", 0xFF64B5F6.toInt())
                list.add(Coordinate(id, name, lat, lon, alt, ts, icon, color))
            } catch (_: Exception) {
                // Expected for malformed entries: skip the row (previous behavior) but count it.
                skipped += "row ${i + 1} (malformed)"
            }
        }
        return list
    }
}
