package io.github.pennstatecs.geokit.heading

import io.github.pennstatecs.geokit.ellipsoid.Geodesic
import kotlin.math.abs
import kotlin.math.acos

/**
 * Pure heading math for the compass (no Android imports; the app's HeadingProvider does the sensor
 * and GeomagneticField work and calls this).
 *
 * The rotation-vector sensor gives a heading relative to MAGNETIC north. Stakeout and the map
 * arrow need TRUE north: true = magnetic + declination (east-positive). Around Hazleton, PA the
 * declination is about −11.6°, so without it the arrow pointed ~11.6° left of the target.
 */
public object HeadingMath {

    /** Same value as `android.hardware.SensorManager.AXIS_X` (checked by the app's HeadingProviderTest). */
    public const val AXIS_X: Int = 1
    /** Same value as `SensorManager.AXIS_Y`. */
    public const val AXIS_Y: Int = 2
    /** Same value as `SensorManager.AXIS_Z`. */
    public const val AXIS_Z: Int = 3
    /** Same value as `SensorManager.AXIS_MINUS_X`. */
    public const val AXIS_MINUS_X: Int = 0x81
    /** Same value as `SensorManager.AXIS_MINUS_Y`. */
    public const val AXIS_MINUS_Y: Int = 0x82
    /** Same value as `SensorManager.AXIS_MINUS_Z`. */
    public const val AXIS_MINUS_Z: Int = 0x83

    /** Same value as `android.view.Surface.ROTATION_0`. */
    public const val ROTATION_0: Int = 0
    /** Same value as `Surface.ROTATION_90`. */
    public const val ROTATION_90: Int = 1
    /** Same value as `Surface.ROTATION_180`. */
    public const val ROTATION_180: Int = 2
    /** Same value as `Surface.ROTATION_270`. */
    public const val ROTATION_270: Int = 3

    /** [deg] folded into [0, 360); non-finite input returns 0. */
    public fun normalize360(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** True heading (degrees, [0, 360)) from a magnetic heading and the local declination (degrees, east-positive). */
    public fun trueHeading(magneticDeg: Double, declinationDeg: Double): Double =
        normalize360(magneticDeg + declinationDeg)

    /**
     * Axes for SensorManager.remapCoordinateSystem so the azimuth is the direction the user is
     * facing for the current display rotation ([displayRotation] is a ROTATION_* value).
     *  - Flat (screen up): the heading is the direction of the screen's top edge.
     *  - Upright (tablet held vertically / pole mount): the heading is where the BACK CAMERA faces.
     */
    public fun remapAxes(displayRotation: Int, upright: Boolean): Pair<Int, Int> =
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
    public fun isUpright(deviceZUp: Double, wasUpright: Boolean): Boolean {
        val tiltDeg = Math.toDegrees(acos(deviceZUp.coerceIn(-1.0, 1.0).let { abs(it) }))
        return when {
            tiltDeg > 50.0 -> true
            tiltDeg < 40.0 -> false
            else -> wasUpright
        }
    }

    /**
     * Height (metres) for GeomagneticField, which expects height above sea level: MSL, else
     * ellipsoidal − N when both are known, else 0. (Effect on declination is tiny.)
     */
    public fun altitudeForDeclination(altMslM: Double?, altEllipsoidalM: Double?, geoidSeparationM: Double?): Double = when {
        altMslM != null && altMslM.isFinite() -> altMslM
        altEllipsoidalM != null && geoidSeparationM != null &&
            altEllipsoidalM.isFinite() && geoidSeparationM.isFinite() -> altEllipsoidalM - geoidSeparationM
        else -> 0.0
    }

    /**
     * True when the declination should be recomputed: no previous value, older than [maxAgeMs], or
     * moved more than [maxMoveM] (WGS84 geodesic) since it was computed. Positions in degrees.
     */
    public fun needsDeclinationUpdate(
        lastLat: Double?, lastLon: Double?, lastTimeMs: Long?,
        lat: Double, lon: Double, nowMs: Long,
        maxMoveM: Double = 10_000.0, maxAgeMs: Long = 5 * 60_000L,
    ): Boolean {
        if (lastLat == null || lastLon == null || lastTimeMs == null) return true
        if (nowMs - lastTimeMs > maxAgeMs) return true
        return Geodesic.distanceM(lastLat, lastLon, lat, lon) > maxMoveM
    }
}
