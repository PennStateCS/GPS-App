package app.surrealar.gnss.capture.math

import kotlin.math.*

/**
 * Minimal WGS84 coordinate conversions used during position averaging.
 *
 * Averaging is done in ECEF (Earth-Centred, Earth-Fixed) Cartesian space because
 * simple arithmetic on latitude/longitude produces wrong results near the poles and
 * across the prime meridian. Converting to ECEF first, averaging the X/Y/Z
 * components, then converting back gives a mathematically correct mean position
 * for short observation windows.
 */
object Geodesy {
    private const val A = 6378137.0             // WGS84 semi-major axis (metres)
    private const val F = 1.0 / 298.257223563   // WGS84 flattening factor
    private const val B = A * (1 - F)           // WGS84 semi-minor axis (metres)
    private val E2 = 1 - (B*B)/(A*A)            // First eccentricity squared

    fun llaToEcef(latDeg: Double, lonDeg: Double, h: Double): Triple<Double, Double, Double> {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)  // Convert degrees to radians
        val sinLat = sin(lat); val cosLat = cos(lat)  // Precompute sine and cosine of latitude
        val sinLon = sin(lon); val cosLon = cos(lon)  // Precompute sine and cosine of longitude
        val N = A / sqrt(1 - E2 * sinLat * sinLat)    // Radius of curvature in prime vertical
        val x = (N + h) * cosLat * cosLon             // ECEF X coordinate
        val y = (N + h) * cosLat * sinLon             // ECEF Y coordinate
        val z = (N * (1 - E2) + h) * sinLat           // ECEF Z coordinate (corrected for ellipsoid flattening)
        return Triple(x, y, z)
    }

    fun ecefToLla(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val e2 = E2                                   // First eccentricity squared
        val ep2 = (A*A - B*B) / (B*B)                 // Second eccentricity squared
        val p = sqrt(x*x + y*y)                       // Distance from Z-axis
        val th = atan2(A * z, B * p)                  // Parametric latitude (initial approximation)
        val lon = atan2(y, x)                         // Longitude (straightforward from ECEF X,Y)
        val lat = atan2(z + ep2 * B * sin(th).pow(3), p - e2 * A * cos(th).pow(3))  // Geodetic latitude (Bowring's formula)
        val N = A / sqrt(1 - e2 * sin(lat) * sin(lat))  // Radius of curvature in prime vertical

        // p / cos(lat) - N is the standard height formula but becomes unstable as cos(lat) → 0
        // near the poles. Above 45° absolute latitude, use z / sin(lat) - N*(1-e²) instead.
        // In the southern hemisphere z and sin(lat) are both negative, so the ratio stays valid
        // without any sign correction.
        val h = if (abs(lat) < PI / 4.0) {
            p / cos(lat) - N
        } else {
            z / sin(lat) - N * (1.0 - e2)
        }
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)  // Convert radians back to degrees
    }
}
