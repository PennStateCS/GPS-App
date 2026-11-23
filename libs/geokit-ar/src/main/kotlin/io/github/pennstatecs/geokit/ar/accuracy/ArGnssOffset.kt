package io.github.pennstatecs.geokit.ar.accuracy

import io.github.pennstatecs.geokit.ellipsoid.Geodesic
import kotlin.math.hypot

/**
 * How far ARCore's camera position (Earth `cameraGeospatialPose`) is from the GNSS fix: the WGS84
 * geodesic north/east offset from the fix to ARCore's position, its length and bearing, and the
 * vertical difference of camera heights.
 *
 * Vertical compares camera altitudes: ARCore's camera altitude minus (GNSS ellipsoidal height −
 * antenna height above the camera). Both are WGS84 ellipsoidal heights.
 */
public data class ArGnssOffset(
    /** Metres north / east from the GNSS position to ARCore's camera position. */
    val northM: Double,
    val eastM: Double,
    /** Horizontal distance, metres (hypot of [northM], [eastM]). */
    val horizontalM: Double,
    /** Compass bearing (°) from the GNSS position to ARCore's camera position. */
    val bearingDeg: Double,
    /** ARCore camera altitude − GNSS camera altitude (m); null without both heights. */
    val verticalM: Double?,
) {
    public companion object {
        /** Null when either position is not finite. */
        public fun compute(
            arLat: Double, arLon: Double, arAlt: Double?,
            gnssLat: Double, gnssLon: Double, gnssEllipsoidalM: Double?,
            antennaAboveCameraM: Double,
        ): ArGnssOffset? {
            if (!arLat.isFinite() || !arLon.isFinite() || !gnssLat.isFinite() || !gnssLon.isFinite()) return null
            // WGS84 geodesic from the GNSS fix to ARCore's camera position.
            val ne = Geodesic.northEastDeltaM(gnssLat, gnssLon, arLat, arLon)
            val h = hypot(ne.northM, ne.eastM)
            val bearing = Geodesic.initialBearingDeg(gnssLat, gnssLon, arLat, arLon)
            val v = if (arAlt != null && arAlt.isFinite() && gnssEllipsoidalM != null && gnssEllipsoidalM.isFinite())
                arAlt - (gnssEllipsoidalM - antennaAboveCameraM) else null
            return ArGnssOffset(ne.northM, ne.eastM, h, bearing, v)
        }
    }
}

/** Expected AR placement error from ARCore's own accuracy estimates. */
public object ArPlacementError {
    /**
     * Worst-case sideways placement error (m) of a model [distanceM] away, from ARCore's horizontal
     * accuracy plus the heading error's lever: h + d·tan(yaw). Null when any input is not finite.
     */
    public fun expectedErrorM(horizontalAccuracyM: Double, yawAccuracyDeg: Double, distanceM: Double): Double? {
        if (!horizontalAccuracyM.isFinite() || !yawAccuracyDeg.isFinite() || !distanceM.isFinite()) return null
        return horizontalAccuracyM + distanceM * kotlin.math.tan(Math.toRadians(yawAccuracyDeg))
    }
}
