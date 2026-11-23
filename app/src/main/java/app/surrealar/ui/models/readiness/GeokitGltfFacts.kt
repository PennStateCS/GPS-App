package app.surrealar.ui.models.readiness

import app.surrealar.ui.openinar.Vec3
import io.github.pennstatecs.geokit.models.gltf.GltfFactsReader
import java.io.File
import io.github.pennstatecs.geokit.models.gltf.GltfFacts as ModelFileFacts

/**
 * The app's [GltfFactsSource]: geokit-models' [GltfFactsReader], with its Double bounds mapped to the
 * readiness [Bounds] (float [Vec3], as before the reader moved to the library).
 */
internal object GeokitGltfFactsSource : GltfFactsSource {
    override fun read(file: File): GltfFacts = GltfFactsReader.read(file).toReadinessFacts()
}

internal fun ModelFileFacts.toReadinessFacts(): GltfFacts = GltfFacts(
    fileSizeBytes = fileSizeBytes,
    bounds = bounds?.let {
        Bounds(
            Vec3(it.minX.toFloat(), it.minY.toFloat(), it.minZ.toFloat()),
            Vec3(it.maxX.toFloat(), it.maxY.toFloat(), it.maxZ.toFloat()),
        )
    },
    triangleCount = triangleCount,
    vertexCount = vertexCount,
    meshCount = meshCount,
    textureCount = textureCount,
    maxTextureSidePx = maxTextureSidePx,
    estimatedTextureBytes = estimatedTextureBytes,
    extensionsRequired = extensionsRequired,
    extensionsUsed = extensionsUsed,
    missingResources = missingResources,
    parseError = parseError,
)
