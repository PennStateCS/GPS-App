package app.surrealar.ui.rendermap

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import io.github.pennstatecs.geokit.heading.HeadingMath
import app.surrealar.gnss.lastknown.LastKnownPosition
import io.github.pennstatecs.geokit.gnss.model.Fix

/**
 * Turns rotation-vector readings into a TRUE heading (degrees from true north, 0–360).
 *
 * - Declination from GeomagneticField at the latest fix (the same FixSwitchboard fix the map uses),
 *   recomputed only after moving > 10 km or every 5 minutes.
 * - Display rotation is handled with SensorManager.remapCoordinateSystem.
 * - Held flat, the heading is the screen's top edge; held upright (pole mount), it is the direction
 *   the BACK CAMERA faces.
 *
 * Declination position, in order: the live fix, else the last known position (declination barely
 * changes over tens of km), else none — then the magnetic heading is returned and
 * [isMagneticOnly] is true so the UI can say so.
 */
class HeadingProvider(
    private val latestFix: () -> Fix?,
    private val lastKnownPosition: () -> LastKnownPosition? = { null },
) {

    enum class DeclinationSource { LIVE_FIX, LAST_KNOWN_POSITION, NONE }

    /** Where the applied declination came from. */
    var declinationSource: DeclinationSource = DeclinationSource.NONE
        private set

    /** True when no position was available, so the heading is relative to MAGNETIC north. */
    val isMagneticOnly: Boolean get() = lastDeclinationDeg == null

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
        val fix = latestFix()?.takeIf { it.latDeg.isFinite() && it.lonDeg.isFinite() }
        val known = if (fix == null) lastKnownPosition() else null
        val (lat, lon, alt, source) = when {
            fix != null -> Quad(fix.latDeg, fix.lonDeg,
                HeadingMath.altitudeForDeclination(fix.altMslM, fix.altEllipsoidalM, fix.geoidSeparationM),
                DeclinationSource.LIVE_FIX)
            known != null -> Quad(known.latDeg, known.lonDeg, 0.0, DeclinationSource.LAST_KNOWN_POSITION)
            else -> return lastDeclinationDeg
        }
        // Upgrading from the last known position to a live fix always refreshes the source label;
        // the value itself is only recomputed after a > 10 km move or 5 minutes.
        val upgrade = source == DeclinationSource.LIVE_FIX && declinationSource != DeclinationSource.LIVE_FIX
        if (upgrade || HeadingMath.needsDeclinationUpdate(declLat, declLon, declTimeMs, lat, lon, nowMs)) {
            lastDeclinationDeg = declinationAt(lat, lon, alt, nowMs)
            declLat = lat; declLon = lon; declTimeMs = nowMs
            declinationSource = source
        }
        return lastDeclinationDeg
    }

    private data class Quad(val lat: Double, val lon: Double, val alt: Double, val source: DeclinationSource)

    companion object {
        /** Magnetic declination (degrees, east-positive) from Android's World Magnetic Model. */
        fun declinationAt(latDeg: Double, lonDeg: Double, altM: Double, timeMs: Long): Double =
            GeomagneticField(latDeg.toFloat(), lonDeg.toFloat(), altM.toFloat(), timeMs).declination.toDouble()
    }
}
