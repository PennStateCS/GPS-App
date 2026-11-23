package app.surrealar.ui.models

import io.github.pennstatecs.geokit.models.gltf.GltfUris
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the external files ("sidecars": .bin buffers, textures) a .gltf references from the folder it
 * was picked from into its bundle folder under models/. Pure file work: the import screen logs.
 */
internal object GltfSidecars {

    /**
     * The relative files a .gltf document references (`buffers`/`images` URIs), decoded by geokit-models'
     * [GltfUris]: `%xx` decoded, `+` kept literal, `data:` URIs, schemes and absolute paths skipped. A
     * `?query` or `#fragment` is dropped first (the viewer resolves resources without them). Failure =
     * the JSON can't be parsed.
     */
    fun resourcePaths(gltfJson: String): Result<Set<String>> =
        GltfUris.externalResourceUris(gltfJson).map { uris ->
            uris.mapNotNull { GltfUris.relativePath(it.substringBefore('#').substringBefore('?')) }.toSet()
        }

    /** What [copy] could not copy, by reason (relative paths as given). */
    class CopyReport(
        val missing: List<String>,
        val unsafe: List<String>,
        val failed: List<String>,
        val firstError: Exception?,
    ) {
        val problems: Int get() = missing.size + unsafe.size + failed.size
    }

    /**
     * Copies each relative path in [resources] from [sourceRoot] to the same place under [targetRoot].
     * A source that resolves outside [sourceRoot] (or doesn't exist) is missing; a target that resolves
     * outside [targetRoot] is unsafe. "Inside" means strictly below the folder: a sibling such as
     * `models2/` is not inside `models/`.
     */
    fun copy(sourceRoot: File, targetRoot: File, resources: Set<String>): CopyReport {
        val missing = ArrayList<String>()
        val unsafe = ArrayList<String>()
        val failed = ArrayList<String>()
        var firstError: Exception? = null
        resources.forEach { relative ->
            try {
                val source = File(sourceRoot, relative).canonicalFile
                if (!GltfUris.isInside(sourceRoot.canonicalFile, source) || !source.exists()) {
                    missing += relative
                    return@forEach
                }
                val target = File(targetRoot, relative).canonicalFile
                if (!GltfUris.isInside(targetRoot.canonicalFile, target)) {
                    unsafe += relative
                    return@forEach
                }
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                failed += relative
                if (firstError == null) firstError = e
            }
        }
        return CopyReport(missing, unsafe, failed, firstError)
    }
}
