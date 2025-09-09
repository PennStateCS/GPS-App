package com.example.surveyingapp.gnss.capture.math

import kotlin.math.*

/**
 * Minimal ECEF<->LLA for short-duration averaging.
 */
object Geodesy {
    private const val A = 6378137.0             // WGS84 major axis
    private const val F = 1.0 / 298.257223563
    private const val B = A * (1 - F)
    private val E2 = 1 - (B*B)/(A*A)

    fun llaToEcef(latDeg: Double, lonDeg: Double, h: Double): Triple<Double, Double, Double> {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val sinLat = sin(lat); val cosLat = cos(lat)
        val sinLon = sin(lon); val cosLon = cos(lon)
        val N = A / sqrt(1 - E2 * sinLat * sinLat)
        val x = (N + h) * cosLat * cosLon
        val y = (N + h) * cosLat * sinLon
        val z = (N * (1 - E2) + h) * sinLat
        return Triple(x, y, z)
    }

    fun ecefToLla(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val e2 = E2
        val ep2 = (A*A - B*B) / (B*B)
        val p = sqrt(x*x + y*y)
        val th = atan2(A * z, B * p)
        val lon = atan2(y, x)
        val lat = atan2(z + ep2 * B * sin(th).pow(3), p - e2 * A * cos(th).pow(3))
        val N = A / sqrt(1 - e2 * sin(lat) * sin(lat))
        val h = p / cos(lat) - N
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
}
