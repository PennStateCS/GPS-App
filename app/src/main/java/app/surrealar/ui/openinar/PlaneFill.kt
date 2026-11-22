package app.surrealar.ui.openinar

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * GL-free maths for the filled AR plane overlay (debug). [PlaneVisualizer] only uploads and draws.
 *
 * A plane polygon from ARCore is a convex loop of (x, z) points in the plane's local frame (the plane
 * is the local y = 0 plane; the pose maps it to world space). It is filled as a triangle fan from the
 * polygon's centroid: vertex 0 is the centre (edge factor 0), vertices 1..n are the polygon points
 * (edge factor 1). The fragment shader fades the fill from the centre to the edge and draws a grid
 * in plane-local coordinates, so it sticks to the surface.
 */
object PlaneFill {

    // ── Look ─────────────────────────────────────────────────────────────────────
    const val CENTER_ALPHA = 0.35f
    const val EDGE_ALPHA = 0.10f
    const val OUTLINE_ALPHA = 0.85f
    const val OUTLINE_WIDTH_PX = 1.5f
    /** Grid line spacing in plane-local metres. */
    const val GRID_SPACING_M = 0.5f
    /** Extra alpha on a grid line (added to the fill alpha). */
    const val GRID_LINE_ALPHA = 0.22f

    // ── Filters ──────────────────────────────────────────────────────────────────
    /** Planes whose closest point is farther than this start fading out… */
    const val FADE_START_M = 8f
    /** …and are invisible (not drawn) from here on. */
    const val FADE_END_M = 15f
    /** Planes smaller than this (extentX × extentZ) are skipped as noise. */
    const val MIN_AREA_M2 = 0.15f

    /** Floats per fan vertex: world x, y, z, local x, local z, edge factor. */
    const val FLOATS_PER_VERTEX = 6

    /** Plane kinds, mapped from ARCore's Plane.Type by the visualizer (keeps this file ARCore-free). */
    enum class Kind { FLOOR, CEILING, WALL }

    /** Fill/outline colours by kind (linear RGB 0..1). One place to change them. */
    object Colors {
        val FLOOR = floatArrayOf(0.00f, 0.78f, 0.72f)    // teal
        val CEILING = floatArrayOf(0.62f, 0.38f, 0.90f)  // purple
        val WALL = floatArrayOf(1.00f, 0.56f, 0.12f)     // orange
    }

    fun colorFor(kind: Kind): FloatArray = when (kind) {
        Kind.FLOOR -> Colors.FLOOR
        Kind.CEILING -> Colors.CEILING
        Kind.WALL -> Colors.WALL
    }

    /** Planes at least [MIN_AREA_M2] in extent; smaller patches are noise. */
    fun isLargeEnough(extentX: Float, extentZ: Float): Boolean = extentX * extentZ >= MIN_AREA_M2

    /** 1 up to [FADE_START_M], linear to 0 at [FADE_END_M], 0 beyond. */
    fun distanceFade(distanceM: Float): Float = when {
        distanceM <= FADE_START_M -> 1f
        distanceM >= FADE_END_M -> 0f
        else -> 1f - (distanceM - FADE_START_M) / (FADE_END_M - FADE_START_M)
    }

    /** Vertices in the fan for an [n]-point polygon (centre + n). */
    fun fanVertexCount(n: Int): Int = if (n < 3) 0 else n + 1

    /** Indices for an [n]-point polygon fan (n triangles, closing back to vertex 1). */
    fun fanIndexCount(n: Int): Int = if (n < 3) 0 else 3 * n

    /**
     * Writes the fan vertices for [polygon] (n = [pointCount] x/z pairs, plane-local) into [out]
     * (stride [FLOATS_PER_VERTEX]) using the plane's column-major 4×4 [pose] (Pose.toMatrix).
     * Returns the vertex count written (0 for fewer than 3 points). [out] must hold
     * fanVertexCount(n) × FLOATS_PER_VERTEX floats.
     */
    fun buildFanVertices(polygon: FloatArray, pointCount: Int, pose: FloatArray, out: FloatArray): Int {
        val n = pointCount
        if (n < 3) return 0
        var cx = 0f; var cz = 0f
        for (i in 0 until n) { cx += polygon[2 * i]; cz += polygon[2 * i + 1] }
        cx /= n; cz /= n
        putVertex(out, 0, cx, cz, 0f, pose)
        for (i in 0 until n) putVertex(out, i + 1, polygon[2 * i], polygon[2 * i + 1], 1f, pose)
        return n + 1
    }

    /** Writes fan indices (0, i, i+1 … 0, n, 1) into [out]; returns the count. */
    fun buildFanIndices(pointCount: Int, out: ShortArray): Int {
        val n = pointCount
        if (n < 3) return 0
        var k = 0
        for (i in 1..n) {
            out[k++] = 0
            out[k++] = i.toShort()
            out[k++] = (if (i == n) 1 else i + 1).toShort()
        }
        return k
    }

    private fun putVertex(out: FloatArray, index: Int, lx: Float, lz: Float, edge: Float, m: FloatArray) {
        val o = index * FLOATS_PER_VERTEX
        out[o] = m[0] * lx + m[8] * lz + m[12]
        out[o + 1] = m[1] * lx + m[9] * lz + m[13]
        out[o + 2] = m[2] * lx + m[10] * lz + m[14]
        out[o + 3] = lx
        out[o + 4] = lz
        out[o + 5] = edge
    }

    /**
     * Distance (m) from the camera to the closest point of the plane's polygon AREA: the height above
     * the plane when the camera is over the polygon, otherwise the distance to the nearest edge. (Using
     * only the corners would fade out a big floor you are standing on.)
     */
    fun closestDistance(polygon: FloatArray, pointCount: Int, pose: FloatArray, camX: Float, camY: Float, camZ: Float): Float {
        val n = pointCount
        if (n < 1) return Float.MAX_VALUE
        // World → plane-local for a rigid pose: R^T (p − t).
        val dx = camX - pose[12]; val dy = camY - pose[13]; val dz = camZ - pose[14]
        val lx = pose[0] * dx + pose[1] * dy + pose[2] * dz
        val ly = pose[4] * dx + pose[5] * dy + pose[6] * dz
        val lz = pose[8] * dx + pose[9] * dy + pose[10] * dz
        if (n >= 3 && insideConvex(polygon, n, lx, lz)) return abs(ly)
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val d = segmentDistance(lx, lz, polygon[2 * i], polygon[2 * i + 1], polygon[2 * j], polygon[2 * j + 1])
            if (d < best) best = d
        }
        return sqrt(best * best + ly * ly)
    }

    /** Distance from the camera to the plane's centre (used to sort far to near). */
    fun centerDistance(pose: FloatArray, camX: Float, camY: Float, camZ: Float): Float {
        val dx = camX - pose[12]; val dy = camY - pose[13]; val dz = camZ - pose[14]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Sorts [count] entries of [items] in place, farthest first (back-to-front for blending),
     * using [distance]. Insertion sort: tiny n, stable, no allocation.
     */
    fun <T> sortFarToNear(items: MutableList<T>, count: Int, distance: (T) -> Float) {
        for (i in 1 until count) {
            val item = items[i]
            val d = distance(item)
            var j = i - 1
            while (j >= 0 && distance(items[j]) < d) { items[j + 1] = items[j]; j-- }
            items[j + 1] = item
        }
    }

    private fun insideConvex(p: FloatArray, n: Int, x: Float, z: Float): Boolean {
        var sign = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = p[2 * i]; val az = p[2 * i + 1]; val bx = p[2 * j]; val bz = p[2 * j + 1]
            val cross = (bx - ax) * (z - az) - (bz - az) * (x - ax)
            if (cross != 0f) {
                val s = if (cross > 0f) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    private fun segmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val vx = bx - ax; val vz = bz - az
        val len2 = vx * vx + vz * vz
        val t = if (len2 == 0f) 0f else (((px - ax) * vx + (pz - az) * vz) / len2).coerceIn(0f, 1f)
        val qx = ax + t * vx - px; val qz = az + t * vz - pz
        return sqrt(qx * qx + qz * qz)
    }
}
