package app.surrealar.geo

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure heading math for the compass (no Android imports; the Android side is HeadingProvider).
 *
 * The rotation-vector sensor gives a heading relative to MAGNETIC north. Stakeout and the map
 * arrow need TRUE north: true = magnetic + declination (east-positive). Around Hazleton, PA the
 * declination is about −11.6°, so without it the arrow pointed ~11.6° left of the target.
 */
object HeadingMath {

    // Same values as android.hardware.SensorManager.AXIS_* (checked in HeadingProviderTest).
    const val AXIS_X = 1
    const val AXIS_Y = 2
    const val AXIS_Z = 3
    const val AXIS_MINUS_X = 0x81
    const val AXIS_MINUS_Y = 0x82
    const val AXIS_MINUS_Z = 0x83

    // Same values as android.view.Surface.ROTATION_*.
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    fun normalize360(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** True heading from a magnetic heading and the local declination (degrees, east-positive). */
    fun trueHeading(magneticDeg: Double, declinationDeg: Double): Double =
        normalize360(magneticDeg + declinationDeg)

    /**
     * Axes for SensorManager.remapCoordinateSystem so the azimuth is the direction the user is
     * facing for the current display rotation.
     *  - Flat (screen up): the heading is the direction of the screen's top edge.
     *  - Upright (tablet held vertically / pole mount): the heading is where the BACK CAMERA faces.
     */
    fun remapAxes(displayRotation: Int, upright: Boolean): Pair<Int, Int> =
        if (!upright) when (displayRotation) {
            ROTATION_90  -> AXIS_Y to AXIS_MINUS_X
            ROTATION_180 -> AXIS_MINUS_X to AXIS_MINUS_Y
            ROTATION_270 -> AXIS_MINUS_Y to AXIS_X
            else         -> AXIS_X to AXIS_Y
        } else when (displayRotation) {
            ROTATION_90  -> AXIS_Z to AXIS_MINUS_X
            ROTATION_180 -> AXIS_MINUS_X to AXIS_MINUS_Z
            ROTATION_270 -> AXIS_MINUS_Z to AXIS_X
            else         -> AXIS_X to AXIS_Z
        }

    /**
     * Upright vs flat from the world-up component of the device's Z axis (screen normal):
     * rotation matrix element R[8]. 1 = lying flat face up, 0 = vertical. Hysteresis between
     * 40° and 50° of tilt keeps the choice from flickering.
     */
    fun isUpright(deviceZUp: Double, wasUpright: Boolean): Boolean {
        val tiltDeg = Math.toDegrees(acos(deviceZUp.coerceIn(-1.0, 1.0).let { abs(it) }))
        return when {
            tiltDeg > 50.0 -> true
            tiltDeg < 40.0 -> false
            else -> wasUpright
        }
    }

    /**
     * Height for GeomagneticField, which expects height above sea level: MSL, else ellipsoidal − N
     * when both are known, else 0. (Effect on declination is tiny; this just passes the right thing.)
     */
    fun altitudeForDeclination(altMslM: Double?, altEllipsoidalM: Double?, geoidSeparationM: Double?): Double = when {
        altMslM != null && altMslM.isFinite() -> altMslM
        altEllipsoidalM != null && geoidSeparationM != null &&
            altEllipsoidalM.isFinite() && geoidSeparationM.isFinite() -> altEllipsoidalM - geoidSeparationM
        else -> 0.0
    }

    /** Recompute declination when moved > [maxMoveM] or older than [maxAgeMs]. */
    fun needsDeclinationUpdate(
        lastLat: Double?, lastLon: Double?, lastTimeMs: Long?,
        lat: Double, lon: Double, nowMs: Long,
        maxMoveM: Double = 10_000.0, maxAgeMs: Long = 5 * 60_000L,
    ): Boolean {
        if (lastLat == null || lastLon == null || lastTimeMs == null) return true
        if (nowMs - lastTimeMs > maxAgeMs) return true
        return distanceM(lastLat, lastLon, lat, lon) > maxMoveM
    }

    /** Great-circle distance (m); a 10 km threshold doesn't need the ellipsoid. */
    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
