package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

/**
 * Universal Transverse Mercator on WGS84, built on the Krüger-series [Tm] (k0 0.9996, false easting
 * 500 000 m, false northing 0 m north / 10 000 000 m south, 6° zones from longitude). Replaces the
 * older Snyder-series UtmConverter. Matches PROJ to well under a millimetre within a zone.
 */
public object Utm {

    /**
     * UTM grid coordinate (WGS84).
     * @property eastingM easting, metres (500 000 at the central meridian).
     * @property northingM northing, metres (10 000 000 false northing in the south).
     * @property zone UTM zone 1..60. @property hemisphere 'N' or 'S'.
     * @property bandLetter MGRS latitude band (C..X, no I/O).
     */
    public data class UtmPosition(
        val eastingM: Double,
        val northingM: Double,
        val zone: Int,
        /** 'N' or 'S'. */
        val hemisphere: Char,
        val bandLetter: Char,
    ) {
        /** Zone number + band letter, e.g. "18T". */
        val zoneLabel: String get() = "$zone$bandLetter"
    }

    private const val K0 = 0.9996
    private const val FALSE_EASTING_M = 500_000.0
    private const val FALSE_NORTHING_SOUTH_M = 10_000_000.0

    private val north = HashMap<Int, Tm>()
    private val south = HashMap<Int, Tm>()

    /** UTM zone (1..60) for a longitude in degrees (no Norway/Svalbard exceptions). */
    public fun zoneFor(lonDeg: Double): Int = minOf(60, ((lonDeg + 180) / 6).toInt() + 1)

    /** Central meridian of [zone], degrees. */
    public fun centralMeridianDeg(zone: Int): Double = (zone - 1) * 6.0 - 180.0 + 3.0

    private fun tm(zone: Int, hemisphere: Char): Tm {
        val cache = if (hemisphere == 'S') south else north
        return synchronized(cache) {
            cache.getOrPut(zone) {
                Tm(0.0, centralMeridianDeg(zone), K0, FALSE_EASTING_M,
                    if (hemisphere == 'S') FALSE_NORTHING_SOUTH_M else 0.0, Ellipsoid.WGS84)
            }
        }
    }

    /** WGS84 latitude/longitude (degrees) → UTM in the zone containing the longitude. */
    public fun forward(latDeg: Double, lonDeg: Double): UtmPosition {
        require(latDeg in -90.0..90.0) { "Latitude must be between -90 and +90 degrees" }
        require(lonDeg in -180.0..180.0) { "Longitude must be between -180 and +180 degrees" }
        return forward(latDeg, lonDeg, zoneFor(lonDeg))
    }

    /** WGS84 latitude/longitude (degrees) → UTM in a given [zone] (e.g. to stay in one zone near an edge). */
    public fun forward(latDeg: Double, lonDeg: Double, zone: Int): UtmPosition {
        require(zone in 1..60) { "UTM zone must be between 1 and 60" }
        val hemisphere = if (latDeg >= 0) 'N' else 'S'
        val (e, n) = tm(zone, hemisphere).forward(latDeg, lonDeg)
        return UtmPosition(e, n, zone, hemisphere, bandLetter(latDeg))
    }

    /** UTM (metres) → WGS84 (latitude, longitude) degrees. */
    public fun inverse(eastingM: Double, northingM: Double, zone: Int, hemisphere: Char): Pair<Double, Double> {
        require(zone in 1..60) { "UTM zone must be between 1 and 60" }
        require(hemisphere == 'N' || hemisphere == 'S') { "Hemisphere must be 'N' or 'S'" }
        return tm(zone, hemisphere).inverse(eastingM, northingM)
    }

    /** MGRS latitude band letter: C at 80°S … X at 84°N (8° bands, X is 12°; I and O skipped). */
    public fun bandLetter(latDeg: Double): Char = when {
        latDeg < -72 -> 'C'; latDeg < -64 -> 'D'; latDeg < -56 -> 'E'; latDeg < -48 -> 'F'
        latDeg < -40 -> 'G'; latDeg < -32 -> 'H'; latDeg < -24 -> 'J'; latDeg < -16 -> 'K'
        latDeg < -8 -> 'L'; latDeg < 0 -> 'M'; latDeg < 8 -> 'N'; latDeg < 16 -> 'P'
        latDeg < 24 -> 'Q'; latDeg < 32 -> 'R'; latDeg < 40 -> 'S'; latDeg < 48 -> 'T'
        latDeg < 56 -> 'U'; latDeg < 64 -> 'V'; latDeg < 72 -> 'W'; else -> 'X'
    }
}
