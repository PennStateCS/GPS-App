package app.surrealar.ui.openinar

import app.surrealar.geo.ellipsoid.Geodesic
import kotlin.math.hypot

/**
 * How far ARCore's idea of where the camera is (Earth `cameraGeospatialPose`) is from the GNSS feed's
 * fix — the live measure of AR placement accuracy, since models are placed in ARCore's frame.
 *
 * With an RTK receiver the fix is ~cm-accurate, so the horizontal offset IS ARCore's localization
 * error (plus the antenna-to-tablet lever arm, which the user keeps small by holding the tablet near
 * the pole). Vertical compares camera altitudes: the antenna height minus "Antenna Height Above
 * Tablet". Only meaningful when standing still (the fix and the camera pose are sampled at slightly
 * different instants), so [speedMps] is carried for the reader.
 *
 * Pure (no ARCore types) → unit-tested.
 */
data class ArVsGnss(
    /** Horizontal distance ARCore↔GNSS, metres. */
    val horizontalM: Double,
    /** Compass bearing (°) from the GNSS position to where ARCore thinks the camera is. */
    val bearingDeg: Double,
    /** ARCore camera altitude − GNSS camera altitude (m); null without both heights. */
    val verticalM: Double?,
    /** ARCore's own 1σ horizontal / yaw accuracy at this moment. */
    val arHAccM: Double?,
    val arYawAccDeg: Double?,
    /** Expected sideways error at the farthest anchored model (h + d·tan yaw), and that distance. */
    val farthestModelM: Double?,
    val expectedErrAtFarthestM: Double?,
    val speedMps: Double?,
) {
    /** Short label for the debug panel / logs: GOOD ≤ 0.5 m, FAIR ≤ 2 m, else POOR. */
    val grade: String get() = when {
        horizontalM <= 0.5 -> "GOOD"
        horizontalM <= 2.0 -> "FAIR"
        else -> "POOR"
    }

    companion object {
        fun compute(
            arLat: Double, arLon: Double, arAlt: Double?,
            gnssLat: Double, gnssLon: Double, gnssEllipsoidalM: Double?,
            antennaAboveCameraM: Double,
            arHAccM: Double?, arYawAccDeg: Double?,
            farthestModelM: Double?, speedMps: Double?,
        ): ArVsGnss? {
            if (!arLat.isFinite() || !arLon.isFinite() || !gnssLat.isFinite() || !gnssLon.isFinite()) return null
            // WGS84 geodesic from the GNSS fix to ARCore's camera position (was 111,132 m/° on a sphere).
            val ne = Geodesic.northEastDeltaM(gnssLat, gnssLon, arLat, arLon)
            val h = hypot(ne.northM, ne.eastM)
            val bearing = Geodesic.initialBearingDeg(gnssLat, gnssLon, arLat, arLon)
            val v = if (arAlt != null && arAlt.isFinite() && gnssEllipsoidalM != null && gnssEllipsoidalM.isFinite())
                arAlt - (gnssEllipsoidalM - antennaAboveCameraM) else null
            val hAcc = arHAccM?.takeIf { it.isFinite() }
            val yaw = arYawAccDeg?.takeIf { it.isFinite() }
            val err = if (hAcc != null && yaw != null && farthestModelM != null)
                GeoAnchorGate.expectedErrorM(hAcc, yaw, farthestModelM) else null
            return ArVsGnss(h, bearing, v, hAcc, yaw, farthestModelM, err, speedMps)
        }
    }
}
