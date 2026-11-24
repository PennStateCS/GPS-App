package app.surrealar.data.backup

import app.surrealar.domain.model.Coordinate

/**
 * How an import reconciles with existing local data.
 *
 * - [MERGE]  keeps existing coordinates; rows whose id collides are overwritten (REPLACE on insert).
 * - [REPLACE] clears all existing coordinates first, then inserts the imported set.
 */
enum class ImportMode { MERGE, REPLACE }

/**
 * The decision/summary for a coordinate backup import, derived purely from the parsed coordinates
 * and the current local state. UI-free so it can be unit tested; [app.surrealar.ui.settings.categories.DataSettingsController]
 * uses it to drive the write and the final message.
 */
data class BackupImportPlan(
    val mode: ImportMode,
    /** Validated coordinates that will be written. */
    val toInsert: List<Coordinate>,
    /** "name (id): reason" for entries the parser dropped as invalid. */
    val skippedInvalid: List<String>,
    /** "name (id) → modelId" for imported coordinates whose model isn't installed locally. */
    val missingModelRefs: List<String>,
    /** Ids that already exist locally and will be overwritten (MERGE only; empty for REPLACE). */
    val duplicateIds: List<String>,
    /** The user declined a partial replace after seeing the counts: nothing was written. */
    val cancelled: Boolean = false,
) {
    val insertCount: Int get() = toInsert.size
    val skippedCount: Int get() = skippedInvalid.size
    val missingModelCount: Int get() = missingModelRefs.size
    val duplicateCount: Int get() = duplicateIds.size

    /** True when there is nothing to import — the caller should not touch the database. */
    val isNoOp: Boolean get() = toInsert.isEmpty() || cancelled

    /**
     * A replace that would delete every local point while dropping some backup rows as invalid. The
     * user chose Replace before the file was read, so they must confirm again with the counts: a backup
     * with 499 bad rows of 500 used to wipe the job and keep one point.
     */
    val needsPartialReplaceConfirmation: Boolean
        get() = mode == ImportMode.REPLACE && !isNoOp && skippedCount > 0

    /** Final, user-facing summary line. */
    fun summaryMessage(): String {
        if (cancelled) return "Import canceled: existing coordinates kept"
        if (isNoOp) return "No coordinates to import"
        val parts = mutableListOf("Imported $insertCount")
        if (skippedCount > 0) parts += "$skippedCount skipped"
        if (missingModelCount > 0) parts += "$missingModelCount missing model(s)"
        if (mode == ImportMode.MERGE && duplicateCount > 0) parts += "$duplicateCount overwritten"
        parts += if (mode == ImportMode.REPLACE) "replaced" else "merged"
        return parts.joinToString(" · ")
    }
}

/** Pure planner for coordinate backup imports — no Android, no I/O. */
object BackupImportPlanner {

    /**
     * Builds a [BackupImportPlan] from already-parsed/validated [parsedCoordinates] (e.g. from
     * `CoordinateBackup.parse`) plus the current local id sets.
     *
     * @param skippedInvalid entries the parser already rejected, carried through for reporting.
     * @param existingCoordinateIds ids currently in the local database.
     * @param existingModelIds model ids currently installed locally (the backup carries metadata,
     *        not model files, so a referenced model may be absent here).
     */
    fun plan(
        parsedCoordinates: List<Coordinate>,
        skippedInvalid: List<String>,
        existingCoordinateIds: Set<String>,
        existingModelIds: Set<String>,
        mode: ImportMode
    ): BackupImportPlan {
        val missing = parsedCoordinates.mapNotNull { c ->
            c.modelId
                ?.takeIf { it.isNotBlank() && it !in existingModelIds }
                ?.let { "'${c.name}' (${c.id}) → $it" }
        }
        val duplicates = if (mode == ImportMode.MERGE) {
            parsedCoordinates.filter { it.id in existingCoordinateIds }.map { it.id }
        } else {
            emptyList()
        }
        return BackupImportPlan(
            mode = mode,
            toInsert = parsedCoordinates,
            skippedInvalid = skippedInvalid,
            missingModelRefs = missing,
            duplicateIds = duplicates
        )
    }
}
