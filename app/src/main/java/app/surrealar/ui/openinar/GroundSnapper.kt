package app.surrealar.ui.openinar

import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.pennstatecs.geokit.ar.ground.GroundSurface
import io.github.pennstatecs.geokit.ar.ground.MainFloor
import kotlin.math.hypot

/**
 * Snaps AR content to the ground / floor ARCore has actually DETECTED (Terrain altitude mode).
 *
 * Google's terrain model (`resolveAnchorOnTerrain`) is a bare-earth elevation dataset: it knows
 * nothing about floors or buildings, is only good to a few metres, and does not resolve indoors.
 * Users expect "Terrain" to put points on the surface they can see, so we use ARCore's
 * horizontal upward-facing planes:
 *  1. a detected surface directly under the point (point inside the plane polygon), else
 *  2. the main floor/ground surface for points within [NEAR_RADIUS_M] of the camera (ground near the
 *     user is assumed locally level), else
 *  3. null — the caller keeps the anchor's own height (e.g. the terrain-model height for far models).
 *
 * GL thread only. Call [update] once per frame before querying [groundY].
 */
internal class GroundSnapper {


    private var planes: List<Plane> = emptyList()
    private var mainFloorY: Float? = null
    private var camX = 0f
    private var camZ = 0f
    /** Logging only: true once a floor has been seen, so "lost" is reported only after a detection. */
    private var floorEverDetected = false

    /** Main floor height in ARCore world metres, or null until a suitable surface is detected. */
    val floorY: Float? get() = mainFloorY

    fun update(session: Session, cameraPose: Pose) {
        val camY = cameraPose.ty()
        camX = cameraPose.tx(); camZ = cameraPose.tz()
        planes = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING &&
                it.subsumedBy == null &&
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                it.centerPose.ty() < camY - MIN_BELOW_CAMERA_M
        }
        val floor = MainFloor.choose(planes.map { GroundSurface(it.centerPose.ty(), it.extentX * it.extentZ) })
        mainFloorY = floor
        // Per frame: log detected/lost only once the state holds 3 s (planes flicker). Nothing is logged
        // before the first detection, so start-up doesn't report a spurious "lost".
        if (floor != null) floorEverDetected = true
        if (floorEverDetected) {
            val detected = floor != null
            val surfaces = planes.size
            app.surrealar.util.DiagnosticsLogger.logOnChange(ArFilamentRenderer.DIAG, FLOOR_LOG_KEY, detected,
                stableMs = 3_000L) {
                if (detected && floor != null) "Ground snap floor detected " +
                    "belowCameraM=${"%.2f".format(camY - floor)} surfaces=$surfaces"
                else "Ground snap floor lost: content keeps its anchor height until a surface is seen"
            }
        }
    }

    fun reset() {
        planes = emptyList()
        mainFloorY = null
        floorEverDetected = false
        app.surrealar.util.DiagnosticsLogger.resetThrottles(FLOOR_LOG_KEY)
    }

    /** Ground height (ARCore world Y, metres) for the point at world (x, z), or null when unknown. */
    fun groundY(x: Float, z: Float): Float? {
        // 1) A detected surface that actually extends under this point (largest wins over e.g. a step).
        val under = planes
            .filter { it.isPoseInPolygon(Pose.makeTranslation(x, it.centerPose.ty(), z)) }
            .maxByOrNull { it.extentX * it.extentZ }
        if (under != null) return under.centerPose.ty()
        // 2) Nearby: assume the main floor/ground continues under the point.
        if (hypot(x - camX, z - camZ) <= NEAR_RADIUS_M) return mainFloorY
        return null
    }

    companion object {
        /** Surfaces must be at least this far below the camera (excludes the plane the tablet rests on). */
        const val MIN_BELOW_CAMERA_M = 0.3f
        /** Points within this horizontal distance of the camera use the main floor when not over a surface. */
        const val NEAR_RADIUS_M = 15f
        private const val FLOOR_LOG_KEY = "ar.groundsnap.floor"
        // Main-floor selection (lowest large surface, MIN_FLOOR_AREA_M2) is geokit-ar's MainFloor.
    }
}
