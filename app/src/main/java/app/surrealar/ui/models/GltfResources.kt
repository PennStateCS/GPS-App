package app.surrealar.ui.models

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import io.github.pennstatecs.geokit.models.gltf.GltfUris

/** Helpers for the external files a .gltf model references (parsing lives in geokit-models' [GltfUris]). */
object GltfResources {

    /**
     * The external resource URIs in a glTF JSON document: the `uri` of every entry in
     * `buffers` and `images`, as written in the file (not decoded). Embedded `data:` URIs and
     * blank entries are skipped; duplicates are returned once, in document order.
     * Returns an empty list (and logs why) for JSON that can't be parsed.
     */
    fun externalResourceUris(gltfJson: String): List<String> =
        GltfUris.externalResourceUris(gltfJson).getOrElse { e ->
            // The model then loads without pre-read resources (on demand); note why.
            DiagnosticsLogger.w(DiagTags.MODEL, "Model glTF resource listing failed: JSON unparseable", e)
            emptyList()
        }
}
