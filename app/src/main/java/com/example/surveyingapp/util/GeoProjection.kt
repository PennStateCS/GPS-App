package com.example.surveyingapp.util

import kotlin.math.*

/**
 * Haversine distance in metres between two WGS84 lat/lon points.
 * Accurate to within ~0.5% at field distances relevant for surveying.
 */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * True bearing in degrees (0 = North, clockwise) from point 1 to point 2.
 */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * Minimal WGS84 -> UTM conversion for client-side projection display.
 *
 * - Accuracy is sufficient for UI display/export (cm-level or better for typical lat/lon ranges).
 * - Handles southern hemisphere false northing.
 * - Zone letters follow standard UTM bands C..X (excluding I and O).
 * - Special Norway/Svalbard exceptions are not implemented (rare; acceptable for field usage).
 */
object GeoProjection {
    private const val WGS84_A = 6378137.0                 // semi-major axis
    private const val WGS84_F_INV = 298.257223563
    private const val K0 = 0.9996

    private val WGS84_F = 1.0 / WGS84_F_INV
    private val E2 = WGS84_F * (2.0 - WGS84_F)            // first eccentricity squared
    private val EP2 = E2 / (1.0 - E2)                     // second eccentricity squared

    data class Utm(
        val easting: Double,
        val northing: Double,
        val zone: Int,
        val letter: Char
    ) {
        val zoneString: String get() = "$zone$letter"
    }

    fun wgs84ToUtm(latDeg: Double, lonDeg: Double): Utm {
        // Wrap longitude to [-180, 180] for zone calculation
        val lonWrapped = wrapLongitude(lonDeg)

        // UTM zone number (1..60), each zone is 6 degrees wide
        val zone = floor((lonWrapped + 180.0) / 6.0).toInt() + 1
        // Central meridian of the zone (in radians)
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)

        val lat = latDeg
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lonWrapped)

        // Trigonometric values for latitude
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)

        // Radius of curvature in the prime vertical
        val N = WGS84_A / sqrt(1.0 - E2 * sinLat * sinLat)
        // Square of tangent of latitude
        val T = tanLat * tanLat
        // Second eccentricity squared times cosine squared latitude
        val C = EP2 * cosLat * cosLat
        // Difference in longitude from central meridian, scaled by cosine latitude
        val A = cosLat * (lonRad - lon0)

        // Meridional arc length from equator to latitude
        val M = meridionalArc(latRad)

        // Powers of A for series expansion
        val A2 = A * A
        val A3 = A2 * A
        val A4 = A3 * A
        val A5 = A4 * A
        val A6 = A5 * A

        // UTM easting calculation (includes series expansion for accuracy)
        val easting = K0 * N * (
            A + (1 - T + C) * A3 / 6.0 + (5 - 18 * T + T * T + 72 * C - 58 * EP2) * A5 / 120.0
        ) + 500_000.0 // false easting

        // UTM northing calculation (includes series expansion for accuracy)
        var northing = K0 * (
            M + N * tanLat * (
                A2 / 2.0 + (5 - T + 9 * C + 4 * C * C) * A4 / 24.0 + (61 - 58 * T + T * T + 600 * C - 330 * EP2) * A6 / 720.0
            )
        )
        // Add false northing for southern hemisphere
        if (lat < 0) northing += 10_000_000.0

        // Determine UTM zone letter for latitude
        val letter = utmLetterForLatitude(lat)
        return Utm(easting, northing, zone, letter)
    }

    /**
     * Computes the meridional arc length from the equator to latitude phi (radians).
     * Uses a series expansion for WGS84 ellipsoid.
     */
    private fun meridionalArc(phi: Double): Double {
        val e2 = E2
        val e4 = e2 * e2
        val e6 = e4 * e2
        // Series coefficients for meridional arc
        val a0 = 1 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val a2 = 3.0 / 8.0 * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
        val a4 = 15.0 / 256.0 * (e4 + 3.0 * e6 / 4.0)
        val a6 = 35.0 * e6 / 3072.0
        // Meridional arc formula
        return WGS84_A * (a0 * phi - a2 * sin(2 * phi) + a4 * sin(4 * phi) - a6 * sin(6 * phi))
    }

    /**
     * Returns the UTM zone letter for a given latitude.
     * UTM bands: C (-80) .. X (84), 8-degree bands, skipping I and O.
     */
    private fun utmLetterForLatitude(lat: Double): Char {
        // UTM bands: C (-80) .. X (84), 8-degree bands, skipping I and O
        val letters = charArrayOf(
            'C','D','E','F','G','H','J','K','L','M','N','P','Q','R','S','T','U','V','W','X'
        )
        if (lat <= -80) return 'C'
        if (lat >= 84) return 'X'
        val idx = floor((lat + 80) / 8.0).toInt()
        return letters[idx]
    }

    /**
     * Wraps longitude to the [-180, 180] range for UTM calculations.
     */
    private fun wrapLongitude(lon: Double): Double {
        var x = lon
        while (x <= -180.0) x += 360.0
        while (x > 180.0) x -= 360.0
        return x
    }
}
