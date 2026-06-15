package com.example.surveyingapp.util

import kotlin.math.*

/**
 * Converts between WGS84 geographic coordinates and UTM projected coordinates.
 *
 * WGS84 latitude/longitude is angular data measured on the Earth ellipsoid.
 * UTM converts that position into a local metric grid using the Transverse
 * Mercator projection. The result is easier to use for surveying-style display,
 * export, and distance calculations because easting and northing are measured in
 * meters.
 *
 * This converter uses WGS84 ellipsoid constants and standard UTM false easting,
 * false northing, and scale factor values.
 */
object UtmConverter {

    // WGS84 ellipsoid parameters.
    private const val WGS84_A = 6378137.0                   // Semi-major axis in meters.
    private const val WGS84_E2 = 0.00669437999014           // First eccentricity squared.
    private const val WGS84_E_PRIME2 = 0.00673949674228     // Second eccentricity squared.

    // UTM projection parameters.
    private const val UTM_K0 = 0.9996                       // Scale factor at the central meridian.
    private const val UTM_E0 = 500000.0                     // False easting in meters.
    private const val UTM_N0_NORTH = 0.0                    // False northing for northern hemisphere.
    private const val UTM_N0_SOUTH = 10000000.0             // False northing for southern hemisphere.

    /**
     * Projected UTM coordinate and its zone information.
     *
     * Easting and northing are meter-based grid coordinates. The zone identifies
     * which 6-degree UTM longitudinal band was used for the projection. The zone
     * letter identifies the latitude band.
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
     * Converts WGS84 latitude/longitude to UTM easting and northing.
     *
     * The conversion uses the Transverse Mercator series expansion. Longitude is
     * measured relative to the selected UTM zone's central meridian, then corrected
     * with higher-order terms to account for ellipsoid shape and distortion across
     * the width of the zone.
     *
     * @param latDeg Latitude in decimal degrees.
     * @param lonDeg Longitude in decimal degrees.
     * @return UTM easting, northing, zone number, hemisphere, and zone letter.
     */
    fun latLonToUtm(latDeg: Double, lonDeg: Double): UtmCoordinate {
        require(latDeg in -90.0..90.0) { "Latitude must be between -90 and +90 degrees" }
        require(lonDeg in -180.0..180.0) { "Longitude must be between -180 and +180 degrees" }

        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        /*
         * UTM divides the world into 60 longitudinal zones, each 6 degrees wide.
         * The projection is centered on the zone's central meridian to keep
         * distortion low inside that zone.
         */
        val zone = minOf(60, ((lonDeg + 180) / 6).toInt() + 1)
        val zoneLetter = getUtmLetter(latDeg)
        val hemisphere = if (latDeg >= 0) 'N' else 'S'

        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        /*
         * Core Transverse Mercator terms:
         *
         * n = radius of curvature in the prime vertical
         * t = tan²(latitude)
         * c = second eccentricity term scaled by cos²(latitude)
         * a = longitude offset from the central meridian, scaled by cos(latitude)
         *
         * These values are reused in the easting and northing series below.
         */
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = WGS84_E_PRIME2 * cos(lat).pow(2)
        val a = cos(lat) * (lon - lonOrigin)

        /*
         * Meridional arc length from the equator to this latitude on the WGS84
         * ellipsoid. Northing starts with this north-south distance, then adds
         * corrections for the point's east-west offset within the UTM zone.
         */
        val m = WGS84_A * (
                (1 - WGS84_E2 / 4 - 3 * WGS84_E2.pow(2) / 64 - 5 * WGS84_E2.pow(3) / 256) * lat -
                        (3 * WGS84_E2 / 8 + 3 * WGS84_E2.pow(2) / 32 + 45 * WGS84_E2.pow(3) / 1024) * sin(2 * lat) +
                        (15 * WGS84_E2.pow(2) / 256 + 45 * WGS84_E2.pow(3) / 1024) * sin(4 * lat) -
                        (35 * WGS84_E2.pow(3) / 3072) * sin(6 * lat)
                )

        /*
         * Easting is the projected east-west distance from the central meridian.
         * The 500,000 m false easting shifts values positive throughout the zone.
         */
        val easting = UTM_K0 * n * (
                a + (1 - t + c) * a.pow(3) / 6 +
                        (5 - 18 * t + t.pow(2) + 72 * c - 58 * WGS84_E_PRIME2) * a.pow(5) / 120
                ) + UTM_E0

        /*
         * Northing is based on the meridional arc plus correction terms for the
         * point's distance from the central meridian. Southern hemisphere values
         * receive a 10,000,000 m false northing so coordinates remain positive.
         */
        val northing = UTM_K0 * (
                m + n * tan(lat) * (
                        a.pow(2) / 2 + (5 - t + 9 * c + 4 * c.pow(2)) * a.pow(4) / 24 +
                                (61 - 58 * t + t.pow(2) + 600 * c - 330 * WGS84_E_PRIME2) * a.pow(6) / 720
                        )
                ) + if (hemisphere == 'N') UTM_N0_NORTH else UTM_N0_SOUTH

        return UtmCoordinate(easting, northing, zone, hemisphere, zoneLetter)
    }

    /**
     * Converts UTM easting/northing back to WGS84 latitude/longitude.
     *
     * This reverses the Transverse Mercator projection. The method first removes
     * false easting/northing, estimates the footprint latitude from the meridional
     * arc, then applies series corrections to recover geographic latitude and
     * longitude.
     *
     * @param easting UTM easting in meters.
     * @param northing UTM northing in meters.
     * @param zone UTM zone number, from 1 to 60.
     * @param hemisphere 'N' for northern hemisphere or 'S' for southern hemisphere.
     * @return Pair of latitude and longitude in decimal degrees.
     */
    fun utmToLatLon(easting: Double, northing: Double, zone: Int, hemisphere: Char): Pair<Double, Double> {
        require(zone in 1..60) { "UTM zone must be between 1 and 60" }
        require(hemisphere in listOf('N', 'S')) { "Hemisphere must be 'N' or 'S'" }

        val falseNorthing = if (hemisphere == 'N') UTM_N0_NORTH else UTM_N0_SOUTH
        val x = easting - UTM_E0
        val y = northing - falseNorthing

        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        /*
         * Convert northing back into meridional arc distance and then into mu, the
         * angular distance used to estimate the footprint latitude.
         */
        val m = y / UTM_K0
        val mu = m / (WGS84_A * (1 - WGS84_E2 / 4 - 3 * WGS84_E2.pow(2) / 64 - 5 * WGS84_E2.pow(3) / 256))

        /*
         * e1 is the third flattening-related term used in the inverse meridional
         * arc series. The j coefficients refine mu into the footprint latitude.
         */
        val e1 = (1 - sqrt(1 - WGS84_E2)) / (1 + sqrt(1 - WGS84_E2))

        val j1 = 3 * e1 / 2 - 27 * e1.pow(3) / 32
        val j2 = 21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32
        val j3 = 151 * e1.pow(3) / 96
        val j4 = 1097 * e1.pow(4) / 512

        val fp = mu + j1 * sin(2 * mu) + j2 * sin(4 * mu) + j3 * sin(6 * mu) + j4 * sin(8 * mu)

        /*
         * Terms evaluated at the footprint latitude. They mirror the forward
         * projection terms and are used to recover latitude and longitude from
         * the projected x/y offsets.
         */
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
     * Returns the UTM latitude band letter for a latitude.
     *
     * UTM latitude bands run from C at 80°S to X at 84°N. Each band is 8 degrees
     * tall, except X, which extends to 84°N. Letters I and O are skipped to avoid
     * confusion with the numbers 1 and 0.
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
     * Calculates straight-line grid distance between two UTM coordinates.
     *
     * This is a planar Euclidean distance using easting and northing in meters.
     * It should be used only when both coordinates are in the same UTM zone.
     *
     * @return Distance in meters.
     */
    fun distanceUtm(easting1: Double, northing1: Double, easting2: Double, northing2: Double): Double {
        val deltaE = easting2 - easting1
        val deltaN = northing2 - northing1
        return sqrt(deltaE.pow(2) + deltaN.pow(2))
    }
}