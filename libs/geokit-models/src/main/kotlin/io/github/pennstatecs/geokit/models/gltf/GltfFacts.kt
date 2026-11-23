package io.github.pennstatecs.geokit.models.gltf

/** Axis-aligned bounds in model units (glTF: metres, Y up), before any app scale. */
public data class GltfBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
)

/** Facts read from a glTF/GLB file itself (no rendering). Null fields = could not be determined. */
public data class GltfFacts(
    val fileSizeBytes: Long,
    /** Bounds of all mesh geometry with node transforms applied; null if unreadable. */
    val bounds: GltfBounds?,
    val triangleCount: Long?,
    val vertexCount: Long?,
    val meshCount: Int?,
    val textureCount: Int?,
    /** Largest texture side in pixels (from PNG/JPEG/WebP/KTX2 headers); null if unknown. */
    val maxTextureSidePx: Int?,
    /** Rough GPU memory for all textures: sum of w*h*4*4/3 (mipmaps). */
    val estimatedTextureBytes: Long?,
    val extensionsRequired: List<String>,
    val extensionsUsed: List<String>,
    /** Relative URIs the file references that don't exist next to it (or are unsafe), as written. */
    val missingResources: List<String>,
    /** Set when the file could not be parsed at all. */
    val parseError: String? = null,
)
