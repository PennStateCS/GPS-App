package app.surrealar.ui.models

import org.json.JSONObject

/** Helpers for the external files a .gltf model references. */
object GltfResources {

    /**
     * The external resource URIs in a glTF JSON document: the `uri` of every entry in
     * `buffers` and `images`, as written in the file (not decoded). Embedded `data:` URIs and
     * blank entries are skipped; duplicates are returned once, in document order.
     * Returns an empty list for JSON that can't be parsed.
     */
    fun externalResourceUris(gltfJson: String): List<String> {
        val root = try {
            JSONObject(gltfJson)
        } catch (e: Exception) {
            // The model then loads without pre-read resources (on demand); note why.
            app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.MODEL,
                "Model glTF resource listing failed: JSON unparseable", e)
            return emptyList()
        }
        val uris = LinkedHashSet<String>()
        for (key in arrayOf("buffers", "images")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val uri = arr.optJSONObject(i)?.optString("uri", "") ?: ""
                if (uri.isNotBlank() && !uri.startsWith("data:")) uris += uri
            }
        }
        return uris.toList()
    }
}
