package io.github.pennstatecs.geokit.ar.model

import kotlin.math.sqrt

/**
 * Which model-local point should coincide with the AR anchor when a GLB is placed. The constant names
 * are stored tokens (database, backups) — never rename them.
 */
public enum class ModelPlacementOrigin {
    /** Anchor the GLB origin (0,0,0). */
    ORIGIN,
    /** Anchor the bounding-box center (the app's historical default). */
    CENTER,
    /** Anchor the bottom-center (center X/Z, min Y) — sits large structures on the ground at the coordinate. */
    BOTTOM_CENTER,
    /** Anchor the GLB origin plus the per-coordinate custom offset (originOffsetX/Y/Z). */
    CUSTOM;

    public companion object {
        /** Parse a stored token; unknown/null falls back to [CENTER] (the historical default). */
        public fun from(s: String?): ModelPlacementOrigin = runCatching { valueOf(s ?: "") }.getOrDefault(CENTER)
    }
}

/** A model-local 3D vector (metres, glTF Y-up). */
public data class Vec3(val x: Float, val y: Float, val z: Float) {
    val length: Float get() = sqrt(x * x + y * y + z * z)
    public operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)
}

/** Anchor point (folded into the base correction) + the fine nudge applied on top of it. */
public data class PlacementOffsets(val anchorPoint: Vec3, val nudge: Vec3)

/**
 * Axis-aligned bounds of a model's visible geometry in model-local space (glTF Y-up). The app's
 * renderer builds one from Filament's `asset.boundingBox` (center + half-extent).
 *
 * The point of this type is large "structure" GLBs whose geometry sits far from the GLB origin (e.g.
 * Model1_local.glb, ~156 m from origin to bottom-center): the anchor must align to a point ON the
 * geometry (bottom-center), not the distant origin.
 */
public data class ModelBounds(val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = Vec3(max.x - min.x, max.y - min.y, max.z - min.z)
    val center: Vec3 get() = Vec3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
    /** Center in X/Z, minimum in Y (Y-up ⇒ Y minimum is the bottom). */
    val bottomCenter: Vec3 get() = Vec3(center.x, min.y, center.z)
    val originToCenterM: Float get() = center.length
    val originToBottomCenterM: Float get() = bottomCenter.length

    /**
     * The model-local point that should coincide with the AR anchor for [origin]. The renderer
     * translates the model by the negative of this so that point lands on the anchor. For CUSTOM,
     * the geometry-independent [customOffset] (originOffsetX/Y/Z) is the anchor point.
     */
    public fun anchorPoint(origin: ModelPlacementOrigin, customOffset: Vec3 = Vec3(0f, 0f, 0f)): Vec3 =
        when (origin) {
            ModelPlacementOrigin.ORIGIN        -> Vec3(0f, 0f, 0f)
            ModelPlacementOrigin.CENTER        -> center
            ModelPlacementOrigin.BOTTOM_CENTER -> bottomCenter
            ModelPlacementOrigin.CUSTOM        -> customOffset
        }

    /**
     * Splits a placement into the [PlacementOffsets.anchorPoint] (folded into the base correction as
     * `T(-anchorPoint)`) and the fine [PlacementOffsets.nudge] applied on top. The per-coordinate
     * [customOffset] (originOffsetX/Y/Z) is applied in EXACTLY ONE place — as the anchor point for
     * CUSTOM, or as the nudge for every other preset — so it can never be double-applied. The vertical
     * offset is always part of the nudge. This is the single source of truth used by the renderer.
     */
    public fun resolveOffsets(
        origin: ModelPlacementOrigin,
        customOffset: Vec3,
        verticalOffsetM: Float,
    ): PlacementOffsets {
        val ap = anchorPoint(origin, customOffset)
        val nudge = if (origin == ModelPlacementOrigin.CUSTOM)
            Vec3(0f, verticalOffsetM, 0f)                                            // offset already in anchorPoint
        else
            Vec3(customOffset.x, verticalOffsetM + customOffset.y, customOffset.z)   // offset as nudge (historical)
        return PlacementOffsets(ap, nudge)
    }

    public companion object {
        public fun fromCenterHalfExtent(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float): ModelBounds =
            ModelBounds(Vec3(cx - hx, cy - hy, cz - hz), Vec3(cx + hx, cy + hy, cz + hz))

        public fun fromMinMax(
            minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float,
        ): ModelBounds = ModelBounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))

        /** Above this origin-to-bottom-center distance, a model's geometry is "far" from its origin. */
        public const val FAR_FROM_ORIGIN_M: Float = 5.0f
    }
}
