package app.surrealar.data.files

import android.app.Application
import app.surrealar.SurRealApplicationEntryPoint
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Removes imported model files/bundles in `filesDir/models` that no saved model points at
 * (left behind by a crash mid-import, a dialog lost on rotation, or older versions that deleted
 * the row but not the file).
 *
 * Only entries older than [MIN_AGE_MS] are touched, so an import that is still copying or waiting
 * in the "Add 3D Model" dialog is never swept.
 */
object ModelOrphanSweeper {

    private const val TAG = "ModelOrphanSweeper"

    /** Entries younger than this are assumed to belong to an in-progress import. */
    const val MIN_AGE_MS: Long = 10 * 60 * 1000L

    /** Runs one sweep on a background thread. Call once from `Application.onCreate`. */
    fun start(app: Application) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = EntryPointAccessors
                    .fromApplication(app, SurRealApplicationEntryPoint::class.java)
                    .modelRepository()
                val paths = repo.getAllModels().first().map { it.filePath }
                val root = File(app.filesDir, ModelFileCleaner.MODELS_DIR_NAME)
                val removed = sweep(root, paths, System.currentTimeMillis())
                if (removed.isNotEmpty()) {
                    DiagnosticsLogger.i(DiagTags.MODEL, "Model orphan sweep completed removedFiles=${removed.size}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                DiagnosticsLogger.w(DiagTags.MODEL, "Model orphan sweep failed", t)
            }
        }
    }

    /**
     * Deletes every top-level entry of [modelsRoot] that is not referenced by [referencedPaths]
     * and whose newest file is older than [minAgeMs]. Returns the entries that were deleted.
     */
    fun sweep(
        modelsRoot: File,
        referencedPaths: Collection<String?>,
        nowMs: Long,
        minAgeMs: Long = MIN_AGE_MS
    ): List<File> {
        val entries = modelsRoot.listFiles() ?: return emptyList()
        val referenced = referencedEntryNames(modelsRoot, referencedPaths)
        val removed = mutableListOf<File>()
        for (entry in entries) {
            if (entry.name in referenced) continue
            if (nowMs - newestModified(entry) < minAgeMs) continue
            val ok = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (ok) removed += entry
        }
        return removed
    }

    /**
     * Names of the top-level entries under [modelsRoot] that saved models use. A path is matched by
     * its location under [modelsRoot], or — if the app's data path changed since it was saved — by
     * the first segment after its last `/models/`.
     */
    internal fun referencedEntryNames(modelsRoot: File, referencedPaths: Collection<String?>): Set<String> {
        val names = HashSet<String>()
        for (path in referencedPaths) {
            if (path.isNullOrBlank()) continue
            runCatching { ModelFileCleaner.deletionTarget(File(path), modelsRoot) }
                .getOrNull()?.let { names += it.name }
            val normalized = path.replace('\\', '/')
            val filesMarker = "/files/${ModelFileCleaner.MODELS_DIR_NAME}/"
            val marker = "/${ModelFileCleaner.MODELS_DIR_NAME}/"
            val filesIdx = normalized.indexOf(filesMarker)
            val (idx, len) = if (filesIdx >= 0) {
                filesIdx to filesMarker.length
            } else {
                normalized.lastIndexOf(marker) to marker.length
            }
            if (idx >= 0) {
                val first = normalized.substring(idx + len).substringBefore('/')
                if (first.isNotEmpty()) names += first
            }
        }
        return names
    }

    private fun newestModified(entry: File): Long =
        if (entry.isDirectory) {
            entry.walkTopDown().maxOfOrNull { it.lastModified() } ?: entry.lastModified()
        } else {
            entry.lastModified()
        }
}
