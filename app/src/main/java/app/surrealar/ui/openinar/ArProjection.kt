package app.surrealar.ui.openinar

import android.graphics.PointF
import android.opengl.Matrix

/**
 * Result of projecting a world-space point onto the 2-D screen.
 *
 * [sx]/[sy] are raw pixel coordinates (may be outside screen bounds).
 * [onScreen] is true when the point falls inside the visible viewport.
 */
internal data class ScreenProjection(val sx: Float, val sy: Float, val onScreen: Boolean)

/**
 * Projects a world-space point to screen pixel coordinates without frustum-clamping.
 *
 * Returns null only when the point is behind the camera (clip.w ≤ 0).
 * The returned [ScreenProjection.onScreen] flag indicates viewport membership.
 */
internal fun projectToScreen(
    worldPos: FloatArray,
    vp: FloatArray,
    w: Int,
    h: Int
): ScreenProjection? {
    if (w <= 0 || h <= 0) return null
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, vp, 0, worldPos, 0)
    if (clip[3] <= 0f) return null  // behind camera
    val ndcX = clip[0] / clip[3]
    val ndcY = clip[1] / clip[3]
    val sx = (ndcX + 1f) * 0.5f * w
    val sy = (1f - ndcY) * 0.5f * h  // NDC Y is up; screen Y is down
    return ScreenProjection(sx, sy, ndcX in -1f..1f && ndcY in -1f..1f)
}

/**
 * Given an unclamped projected screen position, computes a clamped screen-edge position
 * and the direction angle (degrees) for an off-screen arrow indicator.
 *
 * @param margin Edge inset in pixels to keep arrows away from screen corners.
 * @return Pair of (edge position PointF, angleDeg toward pin), or null if degenerate.
 */
internal fun computeEdgeArrow(
    sx: Float,
    sy: Float,
    w: Int,
    h: Int,
    margin: Float
): Pair<PointF, Float>? {
    if (w <= 0 || h <= 0) return null
    val cx = w / 2f
    val cy = h / 2f
    val dx = sx - cx
    val dy = sy - cy
    if (dx == 0f && dy == 0f) return null

    val tRight  = if (dx > 0f) (w - margin - cx) / dx else Float.MAX_VALUE
    val tLeft   = if (dx < 0f) (margin - cx)     / dx else Float.MAX_VALUE
    val tBottom = if (dy > 0f) (h - margin - cy) / dy else Float.MAX_VALUE
    val tTop    = if (dy < 0f) (margin - cy)     / dy else Float.MAX_VALUE

    val t = minOf(tRight, tLeft, tBottom, tTop)
    if (t <= 0f || t == Float.MAX_VALUE) return null

    val ex       = (cx + t * dx).coerceIn(margin, w - margin)
    val ey       = (cy + t * dy).coerceIn(margin, h - margin)
    val angleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return Pair(PointF(ex, ey), angleDeg)
}

