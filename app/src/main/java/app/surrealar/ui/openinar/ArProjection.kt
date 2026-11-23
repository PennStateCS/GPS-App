package app.surrealar.ui.openinar

import android.graphics.PointF

// projectToScreen / ScreenProjection live in geokit-ar (io.github.pennstatecs.geokit.ar.projection).

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
