package com.example.surveyingapp.gnss.capture.math

import kotlin.math.*

/**
 * Minimal ECEF<->LLA for short-duration averaging.
 */
object Geodesy {
    private const val A = 6378137.0             // WGS84 major axis (semi-major axis in meters)
    private const val F = 1.0 / 298.257223563   // WGS84 flattening factor
    private const val B = A * (1 - F)           // WGS84 minor axis (semi-minor axis)
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
        val h = p / cos(lat) - N                      // Ellipsoidal height above reference ellipsoid
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)  // Convert radians back to degrees
    }
}
