package io.github.pennstatecs.geokit.ar.projection

import io.github.pennstatecs.geokit.ar.frame.ArFrames

/**
 * Result of projecting a world-space point onto the 2-D screen.
 *
 * [sx]/[sy] are raw pixel coordinates (may be outside screen bounds).
 * [onScreen] is true when the point falls inside the visible viewport.
 */
public data class ScreenProjection(val sx: Float, val sy: Float, val onScreen: Boolean)

/**
 * Projects a world-space point ([worldPos] = x, y, z, w) through the column-major view-projection
 * matrix [vp] to screen pixel coordinates (y down) without frustum-clamping.
 *
 * Returns null only when the point is behind the camera (clip.w ≤ 0) or the view has no size.
 * The returned [ScreenProjection.onScreen] flag indicates viewport membership.
 */
public fun projectToScreen(
    worldPos: FloatArray,
    vp: FloatArray,
    w: Int,
    h: Int,
): ScreenProjection? {
    if (w <= 0 || h <= 0) return null
    val clip = ArFrames.multiplyMV(vp, worldPos)
    if (clip[3] <= 0f) return null  // behind camera
    val ndcX = clip[0] / clip[3]
    val ndcY = clip[1] / clip[3]
    val sx = ArFrames.ndcToScreenX(ndcX, w)
    val sy = ArFrames.ndcToScreenY(ndcY, h)  // NDC Y is up; screen Y is down
    return ScreenProjection(sx, sy, ndcX in -1f..1f && ndcY in -1f..1f)
}
