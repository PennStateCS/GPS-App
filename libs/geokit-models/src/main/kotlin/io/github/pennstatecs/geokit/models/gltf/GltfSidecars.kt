package io.github.pennstatecs.geokit.models.gltf

import java.io.File
import java.io.FileOutputStream

/**
 * Copies the external files ("sidecars": `.bin` buffers, textures) a `.gltf` references from the
 * folder it came from into another folder (for example an app's managed model bundle), keeping their
 * relative layout. Pure file work on [File]s: never logs, the caller logs from the [CopyReport].
 */
public object GltfSidecars {

    /**
     * The relative files a `.gltf` document references (`buffers`/`images` URIs), decoded by
     * [GltfUris]: `%xx` decoded, `+` kept literal, `data:` URIs, schemes and absolute paths skipped. A
     * `?query` or `#fragment` is dropped first (a file on disk has neither). Failure = the JSON can't
     * be parsed.
     */
    public fun resourcePaths(gltfJson: String): Result<Set<String>> =
        GltfUris.externalResourceUris(gltfJson).map { uris ->
            uris.mapNotNull { GltfUris.relativePath(it.substringBefore('#').substringBefore('?')) }.toSet()
        }

    /** What [copy] could not copy, by reason (relative paths as given). */
    public class CopyReport(
        public val missing: List<String>,
        public val unsafe: List<String>,
        public val failed: List<String>,
        public val firstError: Exception?,
    ) {
        public val problems: Int get() = missing.size + unsafe.size + failed.size
    }

    /**
     * Copies each relative path in [resources] from [sourceRoot] to the same place under [targetRoot].
     * A source that resolves outside [sourceRoot] (or doesn't exist) is missing; a target that resolves
     * outside [targetRoot] is unsafe; an I/O error is failed (the first one is kept). "Inside" means
     * strictly below the folder ([GltfUris.isInside]): a sibling such as `models2/` is not inside
     * `models/`. Never throws for a single resource.
     */
    public fun copy(sourceRoot: File, targetRoot: File, resources: Set<String>): CopyReport {
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
