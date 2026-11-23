package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.accuracy.ArGnssOffset
import io.github.pennstatecs.geokit.ar.accuracy.ArPlacementError

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
 * The maths is geokit-ar's ArGnssOffset; this adds ARCore's accuracy context and the grade.
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
            // WGS84 geodesic offset + camera-height difference (geokit-ar); null for non-finite positions.
            val o = ArGnssOffset.compute(arLat, arLon, arAlt, gnssLat, gnssLon, gnssEllipsoidalM, antennaAboveCameraM)
                ?: return null
            val hAcc = arHAccM?.takeIf { it.isFinite() }
            val yaw = arYawAccDeg?.takeIf { it.isFinite() }
            val err = if (hAcc != null && yaw != null && farthestModelM != null)
                ArPlacementError.expectedErrorM(hAcc, yaw, farthestModelM) else null
            return ArVsGnss(o.horizontalM, o.bearingDeg, o.verticalM, hAcc, yaw, farthestModelM, err, speedMps)
        }
    }
}
