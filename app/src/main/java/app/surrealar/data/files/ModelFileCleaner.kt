package app.surrealar.data.files

import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import java.io.File

/**
 * Centralized deletion of a model's on-disk files (the imported model file and its thumbnail).
 *
 * Why this exists: model deletion is intentionally split across layers — `ModelRepository` removes
 * the Room row and the **thumbnail** file, while `ModelsViewModel` removes the **imported model**
 * file (after the row is gone). Both delegate here so the file-cleanup logic lives in one place and
 * stays consistent: every call is graceful when the path is null/blank or the file is already gone.
 *
 * This helper does NOT touch the database — callers remain responsible for removing the Room row.
 */
object ModelFileCleaner {

    private const val TAG = "ModelFileCleaner"

    /** Name of the folder under `filesDir` that holds imported models. */
    const val MODELS_DIR_NAME = "models"

    /**
     * Deletes the imported model at [filePath], which must live inside [modelsRoot]
     * (normally `filesDir/models`). Anything outside [modelsRoot] is left alone.
     *
     * - A file directly in [modelsRoot] (a .glb import) deletes just that file.
     * - A file inside a bundle folder `modelsRoot/<bundle>/...` (a .gltf import with its .bin and
     *   textures) deletes the whole bundle folder.
     *
     * No-op for a null/blank path or a missing file. Returns true only if something was deleted.
     */
    fun deleteModelFile(filePath: String?, modelsRoot: File): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val target = deletionTarget(File(filePath), modelsRoot) ?: return false
            if (!target.exists()) return false
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.MODEL, "Model file deletion failed", e)
            false
        }
    }

    /**
     * Returns what deleting [file] should remove: the file itself when it sits directly in
     * [modelsRoot], its top-level bundle folder when nested deeper, or null when [file] is
     * not strictly inside [modelsRoot] (or is [modelsRoot] itself).
     */
    fun deletionTarget(file: File, modelsRoot: File): File? {
        val root = modelsRoot.canonicalFile
        val canonical = file.canonicalFile
        var child: File = canonical
        var parent: File? = canonical.parentFile
        while (parent != null && parent != root) {
            child = parent
            parent = parent.parentFile
        }
        if (parent == null) return null // not under modelsRoot
        return child
    }

    /**
     * Deletes the thumbnail file at [filePath] from disk.
     * Silently ignores null/blank paths or files that don't exist.
     */
    fun deleteThumbnailFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        try {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                val deleted = file.delete()
                if (!deleted) {
                    DiagnosticsLogger.w(DiagTags.MODEL, "Model thumbnail deletion failed file=${file.name}")
                } else {
                    DevLog.d(TAG) { "Deleted thumbnail: ${file.name}" }
                }
            }
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.MODEL, "Model thumbnail deletion failed", e)
        }
    }
}
