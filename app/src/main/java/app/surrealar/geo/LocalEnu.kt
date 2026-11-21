package app.surrealar.geo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 local East/North/Up frame about a fixed origin. Pure math (no Android), all in Double.
 *
 * Points go geodetic → ECEF and the difference from the origin is rotated into the origin's local
 * tangent plane, so north–south and east–west scales follow the ellipsoid (meridian and
 * prime-vertical radii) instead of a 111,320 m/° sphere — at 41° N that sphere was +0.24 % N–S and
 * −0.14 % E–W.
 */
class LocalEnu(val originLatDeg: Double, val originLonDeg: Double, val originHeightM: Double = 0.0) {

    private val sinLat = sin(Math.toRadians(originLatDeg))
    private val cosLat = cos(Math.toRadians(originLatDeg))
    private val sinLon = sin(Math.toRadians(originLonDeg))
    private val cosLon = cos(Math.toRadians(originLonDeg))
    private val origin = Wgs84.toEcef(originLatDeg, originLonDeg, originHeightM)

    /** (east, north, up) metres of the point relative to the origin. */
    fun toEnu(latDeg: Double, lonDeg: Double, heightM: Double): DoubleArray {
        val p = Wgs84.toEcef(latDeg, lonDeg, heightM)
        val dx = p[0] - origin[0]; val dy = p[1] - origin[1]; val dz = p[2] - origin[2]
        val e = -sinLon * dx + cosLon * dy
        val n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz
        return doubleArrayOf(e, n, u)
    }
}

/** WGS84 ellipsoid constants and helpers. */
object Wgs84 {
    const val A = 6_378_137.0                       // semi-major axis (m)
    const val F = 1.0 / 298.257223563               // flattening
    val E2 = F * (2.0 - F)                          // first eccentricity squared

    fun toEcef(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val n = A / sqrt(1.0 - E2 * sLat * sLat)
        return doubleArrayOf(
            (n + h) * cLat * cos(lon),
            (n + h) * cLat * sin(lon),
            (n * (1.0 - E2) + h) * sLat
        )
    }

    /** Meridian radius of curvature M at [latDeg] (m). */
    fun meridianRadius(latDeg: Double): Double {
        val s = sin(Math.toRadians(latDeg))
        val w = 1.0 - E2 * s * s
        return A * (1.0 - E2) / (w * sqrt(w))
    }

    /** Prime-vertical radius of curvature N at [latDeg] (m). */
    fun primeVerticalRadius(latDeg: Double): Double {
        val s = sin(Math.toRadians(latDeg))
        return A / sqrt(1.0 - E2 * s * s)
    }

    /** Metres per degree of latitude at [latDeg]. */
    fun metersPerDegLat(latDeg: Double): Double = Math.toRadians(meridianRadius(latDeg))

    /** Metres per degree of longitude at [latDeg]. */
    fun metersPerDegLon(latDeg: Double): Double =
        Math.toRadians(primeVerticalRadius(latDeg) * cos(Math.toRadians(latDeg)))
}
