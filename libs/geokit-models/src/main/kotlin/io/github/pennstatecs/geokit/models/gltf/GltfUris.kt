package io.github.pennstatecs.geokit.models.gltf

import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * The external files a glTF document references (`buffers[].uri`, `images[].uri`) and how to find
 * them safely next to the model. One parser for the whole app: the facts reader, the importer and
 * the viewer agree on what a URI means.
 *
 * Rules: `data:` URIs are embedded, not files. Other URIs are relative references, percent-decoded
 * (`%20` → space) with `+` kept literal (a file named `a+b.png` stays `a+b.png`). A URI with a scheme
 * (`http:`, `file:`, ...) or an absolute path is not a file next to the model. A Windows drive path
 * (`C:\...`) is not mistaken for a scheme, and is then treated as a relative name.
 *
 * Never logs: results say what happened and callers log.
 */
public object GltfUris {

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    /**
     * The external resource URIs in a glTF JSON document: the `uri` of every entry in `buffers` and
     * `images`, as written in the file (not decoded). Embedded `data:` URIs and blank entries are
     * skipped; duplicates are returned once, in document order. Failure = JSON that can't be parsed.
     */
    public fun externalResourceUris(gltfJson: String): Result<List<String>> {
        val root = try {
            JSONObject(gltfJson)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val uris = LinkedHashSet<String>()
        for (key in arrayOf("buffers", "images")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val uri = arr.optJSONObject(i)?.optString("uri", "") ?: ""
                if (uri.isNotBlank() && !uri.startsWith("data:")) uris += uri
            }
        }
        return Result.success(uris.toList())
    }

    /** True for an embedded `data:` URI. */
    public fun isDataUri(uri: String): Boolean = uri.startsWith("data:")

    /**
     * [uri] percent-decoded with `+` kept literal. A URI that isn't valid percent-encoding is returned
     * unchanged.
     */
    public fun decode(uri: String): String =
        runCatching { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)

    /**
     * The relative path [uri] names, decoded; null for a `data:` URI, a URI with a scheme, or an
     * absolute path (`/x`, `\x`).
     */
    public fun relativePath(uri: String): String? {
        if (isDataUri(uri)) return null
        val decoded = decode(uri)
        val hasScheme = SCHEME.containsMatchIn(decoded) && !DRIVE.containsMatchIn(decoded)
        if (hasScheme || decoded.startsWith("/") || decoded.startsWith("\\")) return null
        return decoded
    }

    /** Where a URI points, relative to a model's folder. */
    public sealed class Resolution {
        /** An embedded `data:` URI (nothing on disk). */
        public object Embedded : Resolution()
        /** A scheme or an absolute path: not a file next to the model. */
        public object NotRelative : Resolution()
        /** Resolves outside the folder (`../x`, links). */
        public object Outside : Resolution()
        /** Inside the folder, but no such file. */
        public data class Missing(val file: File) : Resolution()
        /** An existing file inside the folder (canonical). */
        public data class Found(val file: File) : Resolution()
    }

    /**
     * Resolves [uri] against [folder] (made canonical here). Only files strictly inside [folder] are
     * accepted.
     */
    public fun resolve(folder: File, uri: String): Resolution {
        if (isDataUri(uri)) return Resolution.Embedded
        val relative = relativePath(uri) ?: return Resolution.NotRelative
        val base = runCatching { folder.canonicalFile }.getOrNull() ?: return Resolution.Outside
        val f = runCatching { File(base, relative).canonicalFile }.getOrNull()
        if (f == null || !isInside(base, f)) return Resolution.Outside
        return if (f.isFile) Resolution.Found(f) else Resolution.Missing(f)
    }

    /**
     * True when canonical [file] is strictly inside canonical [folder]: a path prefix followed by a
     * separator, so a sibling such as `models2/x` is not inside `models`.
     */
    public fun isInside(folder: File, file: File): Boolean =
        file.path.startsWith(folder.path + File.separator)
}
