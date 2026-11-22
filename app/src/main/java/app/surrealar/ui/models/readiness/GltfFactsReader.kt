package app.surrealar.ui.models.readiness

import java.io.File

/** Reads glTF/GLB facts. Stub until the reader lands; the UI compiles against this. */
object GltfFactsReader : GltfFactsSource {
    override fun read(file: File): GltfFacts = GltfFacts(
        fileSizeBytes = file.length(), bounds = null, triangleCount = null, vertexCount = null, meshCount = null,
        textureCount = null, maxTextureSidePx = null, estimatedTextureBytes = null,
        extensionsRequired = emptyList(), extensionsUsed = emptyList(), missingResources = emptyList(),
        parseError = "not implemented",
    )
}