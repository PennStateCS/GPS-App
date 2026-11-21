package app.surrealar.ui.rendermap

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import app.surrealar.geo.HeadingMath
import app.surrealar.gnss.model.Fix

/**
 * Turns rotation-vector readings into a TRUE heading (degrees from true north, 0–360).
 *
 * - Declination from GeomagneticField at the latest fix (the same FixSwitchboard fix the map uses),
 *   recomputed only after moving > 10 km or every 5 minutes.
 * - Display rotation is handled with SensorManager.remapCoordinateSystem.
 * - Held flat, the heading is the screen's top edge; held upright (pole mount), it is the direction
 *   the BACK CAMERA faces.
 *
 * Without any fix yet the declination is unknown and the magnetic heading is returned
 * ([lastDeclinationDeg] stays null) — there is no map position to stake out from in that case.
 */
class HeadingProvider(private val latestFix: () -> Fix?) {

    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)
    private var upright = false

    private var declLat: Double? = null
    private var declLon: Double? = null
    private var declTimeMs: Long? = null

    /** Declination currently applied (degrees, east-positive), or null while no fix is known. */
    var lastDeclinationDeg: Double? = null
        private set

    /** True when the last reading was treated as "tablet upright" (back-camera heading). */
    val isUpright: Boolean get() = upright

    /** Heading for a TYPE_ROTATION_VECTOR event's values at the given Surface.ROTATION_*. */
    fun trueHeadingDeg(rotationVector: FloatArray, displayRotation: Int, nowMs: Long = System.currentTimeMillis()): Double {
        SensorManager.getRotationMatrixFromVector(r, rotationVector)
        upright = HeadingMath.isUpright(r[8].toDouble(), upright)
        val (ax, ay) = HeadingMath.remapAxes(displayRotation, upright)
        SensorManager.remapCoordinateSystem(r, ax, ay, remapped)
        SensorManager.getOrientation(remapped, angles)
        val magnetic = HeadingMath.normalize360(Math.toDegrees(angles[0].toDouble()))
        return HeadingMath.trueHeading(magnetic, declinationDeg(nowMs) ?: 0.0)
    }

    private fun declinationDeg(nowMs: Long): Double? {
        val fix = latestFix()
        if (fix != null && fix.latDeg.isFinite() && fix.lonDeg.isFinite() &&
            HeadingMath.needsDeclinationUpdate(declLat, declLon, declTimeMs, fix.latDeg, fix.lonDeg, nowMs)) {
            lastDeclinationDeg = declinationAt(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM ?: fix.altMslM ?: 0.0, nowMs)
            declLat = fix.latDeg; declLon = fix.lonDeg; declTimeMs = nowMs
        }
        return lastDeclinationDeg
    }

    companion object {
        /** Magnetic declination (degrees, east-positive) from Android's World Magnetic Model. */
        fun declinationAt(latDeg: Double, lonDeg: Double, altM: Double, timeMs: Long): Double =
            GeomagneticField(latDeg.toFloat(), lonDeg.toFloat(), altM.toFloat(), timeMs).declination.toDouble()
    }
}
