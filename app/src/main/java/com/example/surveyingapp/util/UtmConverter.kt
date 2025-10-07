package com.example.surveyingapp.util

import kotlin.math.*

/**
 * UTM (Universal Transverse Mercator) coordinate conversion utilities.
 *
 * Provides accurate coordinate transformations between WGS84 geographic coordinates
 * (latitude/longitude) and UTM projected coordinates (easting/northing) suitable
 * for surveying applications.
 */
object UtmConverter {

    // WGS84 ellipsoid parameters
    private const val WGS84_A = 6378137.0                   // Semi-major axis (meters)
    private const val WGS84_E2 = 0.00669437999014       // First eccentricity squared
    private const val WGS84_E_PRIME2 = 0.00673949674228 // Second eccentricity squared

    // UTM projection parameters
    private const val UTM_K0 = 0.9996                 // Scale factor
    private const val UTM_E0 = 500000.0               // False easting (meters)
    private const val UTM_N0_NORTH = 0.0              // False northing for northern hemisphere
    private const val UTM_N0_SOUTH = 10000000.0       // False northing for southern hemisphere

    /**
     * UTM coordinate result containing projected coordinates and zone information.
     */
    data class UtmCoordinate(
        val easting: Double,
        val northing: Double,
        val zone: Int,
        val hemisphere: Char, // 'N' or 'S'
        val zoneLetter: Char
    ) {
        val utmZone: String get() = "$zone$zoneLetter"
    }

    /**
     * Convert WGS84 geographic coordinates to UTM projection.
     *
     * @param latDeg Latitude in decimal degrees (-90 to +90)
     * @param lonDeg Longitude in decimal degrees (-180 to +180)
     * @return UTM coordinates with zone information
     */
    fun latLonToUtm(latDeg: Double, lonDeg: Double): UtmCoordinate {
        require(latDeg in -90.0..90.0) { "Latitude must be between -90 and +90 degrees" }
        require(lonDeg in -180.0..180.0) { "Longitude must be between -180 and +180 degrees" }

        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        // Determine UTM zone
        val zone = ((lonDeg + 180) / 6).toInt() + 1
        val zoneLetter = getUtmLetter(latDeg)
        val hemisphere = if (latDeg >= 0) 'N' else 'S'

        // Central meridian for the zone
        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        // Calculate UTM coordinates
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = WGS84_E_PRIME2 * cos(lat).pow(2)
        val a = cos(lat) * (lon - lonOrigin)

        val m = WGS84_A * (
            (1 - WGS84_E2 / 4 - 3 * WGS84_E2.pow(2) / 64 - 5 * WGS84_E2.pow(3) / 256) * lat -
            (3 * WGS84_E2 / 8 + 3 * WGS84_E2.pow(2) / 32 + 45 * WGS84_E2.pow(3) / 1024) * sin(2 * lat) +
            (15 * WGS84_E2.pow(2) / 256 + 45 * WGS84_E2.pow(3) / 1024) * sin(4 * lat) -
            (35 * WGS84_E2.pow(3) / 3072) * sin(6 * lat)
        )

        val easting = UTM_K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
            (5 - 18 * t + t.pow(2) + 72 * c - 58 * WGS84_E_PRIME2) * a.pow(5) / 120
        ) + UTM_E0

        val northing = UTM_K0 * (
            m + n * tan(lat) * (
                a.pow(2) / 2 + (5 - t + 9 * c + 4 * c.pow(2)) * a.pow(4) / 24 +
                (61 - 58 * t + t.pow(2) + 600 * c - 330 * WGS84_E_PRIME2) * a.pow(6) / 720
            )
        ) + if (hemisphere == 'N') UTM_N0_NORTH else UTM_N0_SOUTH

        return UtmCoordinate(easting, northing, zone, hemisphere, zoneLetter)
    }

    /**
     * Convert UTM coordinates back to WGS84 geographic coordinates.
     *
     * @param easting UTM easting coordinate (meters)
     * @param northing UTM northing coordinate (meters)
     * @param zone UTM zone number (1-60)
     * @param hemisphere 'N' for northern hemisphere, 'S' for southern
     * @return Pair of (latitude, longitude) in decimal degrees
     */
    fun utmToLatLon(easting: Double, northing: Double, zone: Int, hemisphere: Char): Pair<Double, Double> {
        require(zone in 1..60) { "UTM zone must be between 1 and 60" }
        require(hemisphere in listOf('N', 'S')) { "Hemisphere must be 'N' or 'S'" }

        val falseNorthing = if (hemisphere == 'N') UTM_N0_NORTH else UTM_N0_SOUTH
        val x = easting - UTM_E0
        val y = northing - falseNorthing

        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        val m = y / UTM_K0
        val mu = m / (WGS84_A * (1 - WGS84_E2 / 4 - 3 * WGS84_E2.pow(2) / 64 - 5 * WGS84_E2.pow(3) / 256))

        val e1 = (1 - sqrt(1 - WGS84_E2)) / (1 + sqrt(1 - WGS84_E2))

        val j1 = 3 * e1 / 2 - 27 * e1.pow(3) / 32
        val j2 = 21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32
        val j3 = 151 * e1.pow(3) / 96
        val j4 = 1097 * e1.pow(4) / 512

        val fp = mu + j1 * sin(2 * mu) + j2 * sin(4 * mu) + j3 * sin(6 * mu) + j4 * sin(8 * mu)

        val c1 = WGS84_E_PRIME2 * cos(fp).pow(2)
        val t1 = tan(fp).pow(2)
        val n1 = WGS84_A / sqrt(1 - WGS84_E2 * sin(fp).pow(2))
        val r1 = WGS84_A * (1 - WGS84_E2) / (1 - WGS84_E2 * sin(fp).pow(2)).pow(1.5)
        val d = x / (n1 * UTM_K0)

        val lat = fp - (n1 * tan(fp) / r1) * (
            d.pow(2) / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * WGS84_E_PRIME2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * WGS84_E_PRIME2 - 3 * c1.pow(2)) * d.pow(6) / 720
        )

        val lon = lonOrigin + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * WGS84_E_PRIME2 + 24 * t1.pow(2)) * d.pow(5) / 120) / cos(fp)

        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /**
     * Get UTM letter designation for a given latitude.
     */
    private fun getUtmLetter(latDeg: Double): Char {
        return when {
            latDeg < -72 -> 'C'
            latDeg < -64 -> 'D'
            latDeg < -56 -> 'E'
            latDeg < -48 -> 'F'
            latDeg < -40 -> 'G'
            latDeg < -32 -> 'H'
            latDeg < -24 -> 'J'
            latDeg < -16 -> 'K'
            latDeg < -8 -> 'L'
            latDeg < 0 -> 'M'
            latDeg < 8 -> 'N'
            latDeg < 16 -> 'P'
            latDeg < 24 -> 'Q'
            latDeg < 32 -> 'R'
            latDeg < 40 -> 'S'
            latDeg < 48 -> 'T'
            latDeg < 56 -> 'U'
            latDeg < 64 -> 'V'
            latDeg < 72 -> 'W'
            else -> 'X'
        }
    }

    /**
     * Calculate the distance between two UTM coordinates in the same zone.
     *
     * @return Distance in meters
     */
    fun distanceUtm(easting1: Double, northing1: Double, easting2: Double, northing2: Double): Double {
        val deltaE = easting2 - easting1
        val deltaN = northing2 - northing1
        return sqrt(deltaE.pow(2) + deltaN.pow(2))
    }
}
