package com.example.surveyingapp.util

import kotlin.math.*

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
        val lat = latDeg.coerceIn(-80.0, 84.0)            // UTM valid range
        val lonWrapped = wrapLongitude(lonDeg)

        val zone = floor((lonWrapped + 180.0) / 6.0).toInt() + 1
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0) // central meridian

        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lonWrapped)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)

        val N = WGS84_A / sqrt(1.0 - E2 * sinLat * sinLat)
        val T = tanLat * tanLat
        val C = EP2 * cosLat * cosLat
        val A = cosLat * (lonRad - lon0)

        val M = meridionalArc(latRad)

        val A2 = A * A
        val A3 = A2 * A
        val A4 = A3 * A
        val A5 = A4 * A
        val A6 = A5 * A

        val easting = K0 * N * (
            A + (1 - T + C) * A3 / 6.0 + (5 - 18 * T + T * T + 72 * C - 58 * EP2) * A5 / 120.0
        ) + 500_000.0

        var northing = K0 * (
            M + N * tanLat * (
                A2 / 2.0 + (5 - T + 9 * C + 4 * C * C) * A4 / 24.0 + (61 - 58 * T + T * T + 600 * C - 330 * EP2) * A6 / 720.0
            )
        )
        if (lat < 0) northing += 10_000_000.0 // false northing for southern hemisphere

        val letter = utmLetterForLatitude(lat)
        return Utm(easting, northing, zone, letter)
    }

    private fun meridionalArc(phi: Double): Double {
        val e2 = E2
        val e4 = e2 * e2
        val e6 = e4 * e2
        val a0 = 1 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val a2 = 3.0 / 8.0 * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
        val a4 = 15.0 / 256.0 * (e4 + 3.0 * e6 / 4.0)
        val a6 = 35.0 * e6 / 3072.0
        return WGS84_A * (a0 * phi - a2 * sin(2 * phi) + a4 * sin(4 * phi) - a6 * sin(6 * phi))
    }

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

    private fun wrapLongitude(lon: Double): Double {
        var x = lon
        while (x <= -180.0) x += 360.0
        while (x > 180.0) x -= 360.0
        return x
    }
}

