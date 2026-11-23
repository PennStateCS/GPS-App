package app.surrealar.ui.openinar

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure maths for moving a model in AR with touch gestures (no Android, no ARCore types).
 *
 * Frames: ARCore world (metres, +Y up against gravity) and the anchor frame of a geospatial anchor,
 * which is East-Up-South (anchors are created with an identity rotation): east = +X, up = +Y,
 * north = -Z. Matrices are column-major float[16], as ARCore and OpenGL give them.
 */
object ArMoveMath {

    /** The camera as the frame loop saw it: display-oriented pose and the projection terms needed. */
    data class CameraState(
        /** Camera position in world. */
        val px: Float, val py: Float, val pz: Float,
        /** Camera rotation (display-oriented pose) as a unit quaternion x, y, z, w. */
        val qx: Float, val qy: Float, val qz: Float, val qw: Float,
        /** Projection matrix terms: proj[0], proj[5] (focal scale) and proj[8], proj[9] (principal point offset). */
        val p0: Float, val p5: Float, val p8: Float, val p9: Float,
        /** View size in pixels. */
        val widthPx: Int, val heightPx: Int,
    )

    /**
     * Where the screen point ([xPx], [yPx], y down) hits the horizontal plane at world height [planeY],
     * or null when the ray points away from it (above the horizon) or is nearly parallel.
     */
    fun hitHorizontalPlane(cam: CameraState, xPx: Float, yPx: Float, planeY: Float): FloatArray? {
        if (cam.widthPx <= 0 || cam.heightPx <= 0) return null
        // Normalised device coordinates, then the camera-space ray direction (camera looks down -Z).
        val ndcX = 2f * xPx / cam.widthPx - 1f
        val ndcY = 1f - 2f * yPx / cam.heightPx
        val cx = (ndcX + cam.p8) / cam.p0
        val cy = (ndcY + cam.p9) / cam.p5
        val d = rotate(cam.qx, cam.qy, cam.qz, cam.qw, cx, cy, -1f)
        if (kotlin.math.abs(d[1]) < 1e-4f) return null
        val t = (planeY - cam.py) / d[1]
        if (t <= 0f) return null
        return floatArrayOf(cam.px + t * d[0], planeY, cam.pz + t * d[2])
    }

    /**
     * A world-frame displacement as metres east / north / up, given the anchor's world matrix
     * ([anchorWorld], column-major; its rotation maps the anchor's East-Up-South frame into world).
     */
    fun worldDeltaToEnu(anchorWorld: FloatArray, dx: Float, dy: Float, dz: Float): FloatArray {
        // local = Rᵀ · d, with R's columns = the anchor's X (east), Y (up), Z (south) axes in world.
        val east = anchorWorld[0] * dx + anchorWorld[1] * dy + anchorWorld[2] * dz
        val up = anchorWorld[4] * dx + anchorWorld[5] * dy + anchorWorld[6] * dz
        val south = anchorWorld[8] * dx + anchorWorld[9] * dy + anchorWorld[10] * dz
        return floatArrayOf(east, -south, up)
    }

    /** Angle (degrees, screen coordinates with y down) of the line from pointer 0 to pointer 1. */
    fun pointerAngleDeg(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    /**
     * Yaw change for a two-finger twist from [startAngleDeg] to [angleDeg] (see [pointerAngleDeg]). A
     * clockwise twist on screen turns the model clockwise seen from above, which is negative yaw in the
     * renderer's convention (counter-clockwise positive about +Y). Wrapped to (-180, 180].
     */
    fun twistToYawDeg(startAngleDeg: Float, angleDeg: Float): Float {
        var d = angleDeg - startAngleDeg
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return -d
    }

    /**
     * Metres of height for a vertical finger drag of [dyPx] pixels (y down, so dragging up raises the
     * model) at [distanceM] from the camera: one pixel covers 2·d·tan(fovY/2)/height = 2·d/(proj[5]·h).
     */
    fun verticalDragToUpM(cam: CameraState, dyPx: Float, distanceM: Float): Float {
        if (cam.heightPx <= 0 || cam.p5 == 0f) return 0f
        return -dyPx * (2f * distanceM / (cam.p5 * cam.heightPx))
    }

    /**
     * A nudge relative to where the camera looks — [rightM] to the right, [forwardM] away from the user,
     * both horizontal — as metres east / north / up through [anchorWorld] (see [worldDeltaToEnu]).
     * Null when the camera points straight up or down (no horizontal forward direction).
     */
    fun viewNudgeToEnu(cam: CameraState, anchorWorld: FloatArray, rightM: Float, forwardM: Float): FloatArray? {
        val f = rotate(cam.qx, cam.qy, cam.qz, cam.qw, 0f, 0f, -1f)
        val fLen = sqrt(f[0] * f[0] + f[2] * f[2])
        if (fLen < 1e-3f) return null
        val fx = f[0] / fLen; val fz = f[2] / fLen
        // Right = forward turned 90° clockwise seen from above (+Y up): (fx, fz) → (-fz, fx).
        val rx = -fz; val rz = fx
        return worldDeltaToEnu(anchorWorld, rx * rightM + fx * forwardM, 0f, rz * rightM + fz * forwardM)
    }

    /** Distance from the camera to world point ([x], [y], [z]). */
    fun distance(cam: CameraState, x: Float, y: Float, z: Float): Float {
        val dx = x - cam.px; val dy = y - cam.py; val dz = z - cam.pz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Rotates vector ([vx], [vy], [vz]) by unit quaternion ([qx], [qy], [qz], [qw]). */
    internal fun rotate(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): FloatArray {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        )
    }
}
