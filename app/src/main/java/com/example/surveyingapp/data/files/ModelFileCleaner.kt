package com.example.surveyingapp.data.files

import java.io.File

/**
 * Centralized deletion of a model's on-disk files (the imported model file and its thumbnail).
 *
 * Why this exists: model deletion is intentionally split across layers — `ModelRepository` removes
 * the Room row and the **thumbnail** file, while the model-list UI removes the **imported model**
 * file. Both delegate here so the file-cleanup logic lives in one place and stays consistent:
 * every call is graceful when the path is null/blank or the file is already gone.
 *
 * This helper does NOT touch the database — callers remain responsible for removing the Room row.
 * Behavior is preserved exactly from the previous inline implementations (no path/filename changes).
 */
object ModelFileCleaner {

    private const val TAG = "ModelFileCleaner"

    /**
     * Deletes the imported model file at [filePath]. No-op for a null/blank path or a missing file.
     * Returns true only if a file existed and was deleted.
     */
    fun deleteModelFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val file = File(filePath)
            if (file.exists() && file.isFile) file.delete() else false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Model file deletion failed for $filePath: ${e.message}")
            false
        }
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
                    android.util.Log.w(TAG, "Failed to delete thumbnail: $filePath")
                } else {
                    android.util.Log.d(TAG, "Deleted thumbnail: $filePath")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error deleting thumbnail file: $filePath", e)
        }
    }
}
