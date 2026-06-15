package com.example.surveyingapp.util

import kotlin.math.*

/**
 * Computes the great-circle distance between two WGS84 latitude/longitude points.
 *
 * This uses the haversine formula, which treats the Earth as a sphere.
 *
 * Inputs are decimal degrees. The returned distance is in meters.
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
 * Computes the initial true bearing from the first point to the second point.
 *
 * The result is degrees clockwise from true north:
 * 0 = north, 90 = east, 180 = south, and 270 = west.
 *
 * This is the forward azimuth at the starting point along the great-circle path.
 * It is not a constant bearing over long distances.
 */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * Lightweight WGS84 to UTM projection utilities.
 *
 * The conversion uses the standard Transverse Mercator series for UTM on the WGS84
 * ellipsoid. This is intended for client-side display, export, and local coordinate
 * labeling, not as a replacement for a full geodesy/projection library.
 *
 * Notes:
 * - UTM zones are 6 degrees wide and use a 0.9996 central scale factor.
 * - Easting includes the standard 500,000 m false easting.
 * - Southern hemisphere northing includes the standard 10,000,000 m false northing.
 * - Latitude letters follow the standard C..X UTM bands, excluding I and O.
 * - Norway and Svalbard special zone exceptions are not implemented here.
 */
object GeoProjection {
    private const val WGS84_A = 6378137.0                 // WGS84 semi-major axis, in meters.
    private const val WGS84_F_INV = 298.257223563         // Inverse flattening.
    private const val K0 = 0.9996                         // UTM scale factor at the central meridian.

    private val WGS84_F = 1.0 / WGS84_F_INV

    /*
     * First eccentricity squared. This describes how far the ellipsoid is from a sphere
     * and appears throughout the Transverse Mercator series.
     */
    private val E2 = WGS84_F * (2.0 - WGS84_F)

    /*
     * Second eccentricity squared. This is used in the easting/northing correction terms
     * where longitude distance is scaled by latitude.
     */
    private val EP2 = E2 / (1.0 - E2)

    data class Utm(
        val easting: Double,
        val northing: Double,
        val zone: Int,
        val letter: Char
    ) {
        val zoneString: String get() = "$zone$letter"
    }

    fun wgs84ToUtm(latDeg: Double, lonDeg: Double): Utm {
        // Normalize longitude before choosing a UTM zone.
        val lonWrapped = wrapLongitude(lonDeg)

        /*
         * UTM has 60 zones, each 6 degrees wide. Zone 1 starts at 180°W.
         * The central meridian is the middle of the selected zone.
         */
        val zone = minOf(60, floor((lonWrapped + 180.0) / 6.0).toInt() + 1)
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)

        val lat = latDeg
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lonWrapped)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)

        /*
         * Radius of curvature in the prime vertical. This is the ellipsoid radius
         * perpendicular to the meridian at the current latitude.
         */
        val N = WGS84_A / sqrt(1.0 - E2 * sinLat * sinLat)

        /*
         * Common Transverse Mercator terms:
         *
         * T = tan²(latitude)
         * C = second eccentricity squared scaled by cos²(latitude)
         * A = longitude difference from the central meridian, scaled by cos(latitude)
         *
         * These terms feed the series expansion used to project the ellipsoid onto the
         * UTM grid with good accuracy near the zone's central meridian.
         */
        val T = tanLat * tanLat
        val C = EP2 * cosLat * cosLat
        val A = cosLat * (lonRad - lon0)

        /*
         * Meridional arc: distance along the ellipsoid from the equator to this latitude.
         * Northing is built from this value plus correction terms for distance east/west
         * from the zone's central meridian.
         */
        val M = meridionalArc(latRad)

        // Powers of A are reused in the easting and northing series terms.
        val A2 = A * A
        val A3 = A2 * A
        val A4 = A3 * A
        val A5 = A4 * A
        val A6 = A5 * A

        /*
         * Easting is measured from the zone's central meridian, then shifted by the
         * standard 500,000 m false easting so values stay positive inside the zone.
         */
        val easting = K0 * N * (
                A + (1 - T + C) * A3 / 6.0 + (5 - 18 * T + T * T + 72 * C - 58 * EP2) * A5 / 120.0
                ) + 500_000.0

        /*
         * Northing starts with the meridional arc and adds correction terms based on
         * how far the point is from the central meridian. The higher-order terms reduce
         * distortion across the full width of a UTM zone.
         */
        var northing = K0 * (
                M + N * tanLat * (
                        A2 / 2.0 + (5 - T + 9 * C + 4 * C * C) * A4 / 24.0 + (61 - 58 * T + T * T + 600 * C - 330 * EP2) * A6 / 720.0
                        )
                )

        // Southern hemisphere UTM northings are offset so they remain positive.
        if (lat < 0) northing += 10_000_000.0

        val letter = utmLetterForLatitude(lat)
        return Utm(easting, northing, zone, letter)
    }

    /**
     * Computes the meridional arc from the equator to latitude phi.
     *
     * The meridional arc is the north-south distance along the WGS84 ellipsoid. UTM
     * northing is based on this arc, then adjusted for the point's east-west offset
     * from the zone's central meridian.
     *
     * The coefficients below are the standard series terms through e⁶, which is
     * sufficient for this lightweight projection use case.
     */
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

    /**
     * Returns the UTM latitude band letter for a latitude in degrees.
     *
     * UTM latitude bands run from C at 80°S to X at 84°N. Each band is 8 degrees
     * high, except X, which is extended to 84°N. Letters I and O are skipped to avoid
     * confusion with the numbers 1 and 0.
     */
    private fun utmLetterForLatitude(lat: Double): Char {
        val letters = charArrayOf(
            'C','D','E','F','G','H','J','K','L','M','N','P','Q','R','S','T','U','V','W','X'
        )
        if (lat <= -80) return 'C'
        if (lat >= 84) return 'X'
        val idx = floor((lat + 80) / 8.0).toInt()
        return letters[idx]
    }

    /**
     * Wraps longitude into the conventional [-180, 180] interval.
     *
     * Keeping longitude in this range avoids bad UTM zone numbers for values that
     * cross the antimeridian or arrive as unnormalized input.
     */
    private fun wrapLongitude(lon: Double): Double {
        var x = lon
        while (x < -180.0) x += 360.0
        while (x > 180.0) x -= 360.0
        return x
    }
}