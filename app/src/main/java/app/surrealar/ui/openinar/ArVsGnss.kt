package app.surrealar.ui.openinar

import kotlin.math.atan2
import kotlin.math.cos
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
        private const val M_PER_DEG_LAT = 111_132.0

        fun compute(
            arLat: Double, arLon: Double, arAlt: Double?,
            gnssLat: Double, gnssLon: Double, gnssEllipsoidalM: Double?,
            antennaAboveCameraM: Double,
            arHAccM: Double?, arYawAccDeg: Double?,
            farthestModelM: Double?, speedMps: Double?,
        ): ArVsGnss? {
            if (!arLat.isFinite() || !arLon.isFinite() || !gnssLat.isFinite() || !gnssLon.isFinite()) return null
            // Local tangent plane: fine at the metres-to-tens-of-metres scale compared here.
            val dn = (arLat - gnssLat) * M_PER_DEG_LAT
            val de = (arLon - gnssLon) * M_PER_DEG_LAT * cos(Math.toRadians(gnssLat))
            val h = hypot(dn, de)
            val bearing = (Math.toDegrees(atan2(de, dn)) + 360.0) % 360.0
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
