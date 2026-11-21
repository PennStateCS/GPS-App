package io.github.pennstatecs.geokit.local

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local East/North/Up frame about a fixed origin on an [ellipsoid] (default WGS84). All Double.
 *
 * Points go geodetic → ECEF and the difference from the origin is rotated into the origin's local
 * tangent plane. For short-range local work (capture spread, GLB reprojection). For distances and
 * bearings between two points use [io.github.pennstatecs.geokit.ellipsoid.Geodesic].
 *
 * @property originLatDeg origin latitude, degrees. @property originLonDeg origin longitude, degrees.
 * @property originHeightM origin ellipsoidal height, metres. @property ellipsoid the ellipsoid (WGS84 by default).
 */
public class LocalEnu(
    public val originLatDeg: Double,
    public val originLonDeg: Double,
    public val originHeightM: Double = 0.0,
    public val ellipsoid: Ellipsoid = Ellipsoid.WGS84,
) {
    private val sinLat = sin(Math.toRadians(originLatDeg))
    private val cosLat = cos(Math.toRadians(originLatDeg))
    private val sinLon = sin(Math.toRadians(originLonDeg))
    private val cosLon = cos(Math.toRadians(originLonDeg))
    private val origin = ellipsoid.toEcef(originLatDeg, originLonDeg, originHeightM)

    /** (east, north, up) metres of the point relative to the origin. */
    public fun toEnu(latDeg: Double, lonDeg: Double, heightM: Double): DoubleArray {
        val p = ellipsoid.toEcef(latDeg, lonDeg, heightM)
        return enuFromEcefDelta(p.xM - origin.xM, p.yM - origin.yM, p.zM - origin.zM)
    }

    /** Rotates an ECEF offset (metres) into (east, north, up) metres at the origin. */
    public fun enuFromEcefDelta(dxM: Double, dyM: Double, dzM: Double): DoubleArray {
        val e = -sinLon * dxM + cosLon * dyM
        val n = -sinLat * cosLon * dxM - sinLat * sinLon * dyM + cosLat * dzM
        val u = cosLat * cosLon * dxM + cosLat * sinLon * dyM + sinLat * dzM
        return doubleArrayOf(e, n, u)
    }
}
