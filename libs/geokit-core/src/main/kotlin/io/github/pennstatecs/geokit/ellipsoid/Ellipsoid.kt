package io.github.pennstatecs.geokit.ellipsoid

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geodetic position on some ellipsoid/datum (the caller knows which: WGS84/ITRF or NAD83(2011)).
 * @property latDeg latitude, degrees (−90..90). @property lonDeg longitude, degrees (−180..180).
 * @property heightM ellipsoidal height, metres.
 */
public data class GeodeticPosition(val latDeg: Double, val lonDeg: Double, val heightM: Double)

/** Earth-centred, Earth-fixed Cartesian coordinates (metres) on the ellipsoid's geocentric frame. */
public data class EcefPosition(val xM: Double, val yM: Double, val zM: Double)

/**
 * A reference ellipsoid and its geocentric (ECEF) conversions and radii of curvature.
 *
 * Replaces four earlier overlapping implementations (WGS84 Bowring, GRS80 iterative, the local-ENU
 * helper and the stakeout geometry). [fromEcef] iterates to convergence, so it agrees with the old
 * Bowring and iterative versions to well under a micrometre for terrestrial points.
 *
 * @property name short label, e.g. "WGS84".
 * @property semiMajorAxisM equatorial radius a, metres.
 * @property inverseFlattening 1/f.
 */
public class Ellipsoid(
    public val name: String,
    public val semiMajorAxisM: Double,
    public val inverseFlattening: Double,
) {
    /** Flattening f. */
    public val flattening: Double = 1.0 / inverseFlattening
    /** First eccentricity squared e² = f(2 − f). */
    public val eccentricitySquared: Double = flattening * (2.0 - flattening)
    /** Semi-minor (polar) axis b = a(1 − f), metres. */
    public val semiMinorAxisM: Double = semiMajorAxisM * (1.0 - flattening)
    /** Third flattening n = f / (2 − f), used by the Krüger transverse-Mercator series. */
    public val thirdFlattening: Double = flattening / (2.0 - flattening)

    /** Geodetic (degrees, metres) → ECEF metres. */
    public fun toEcef(latDeg: Double, lonDeg: Double, heightM: Double): EcefPosition {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val n = semiMajorAxisM / sqrt(1.0 - eccentricitySquared * sLat * sLat)
        return EcefPosition(
            (n + heightM) * cLat * cos(lon),
            (n + heightM) * cLat * sin(lon),
            (n * (1.0 - eccentricitySquared) + heightM) * sLat,
        )
    }

    /** [GeodeticPosition] → ECEF metres (same as the three-argument form). */
    public fun toEcef(p: GeodeticPosition): EcefPosition = toEcef(p.latDeg, p.lonDeg, p.heightM)

    /**
     * ECEF metres → geodetic. Fixed-point iteration on latitude; the height uses the form
     * h = p·cos φ + z·sin φ − a·√(1 − e² sin² φ), which stays well conditioned at the poles.
     */
    public fun fromEcef(xM: Double, yM: Double, zM: Double): GeodeticPosition {
        val a = semiMajorAxisM; val e2 = eccentricitySquared
        val lon = atan2(yM, xM)
        val p = sqrt(xM * xM + yM * yM)
        var lat = atan2(zM, p * (1.0 - e2))
        repeat(12) {
            val s = sin(lat)
            val n = a / sqrt(1.0 - e2 * s * s)
            val h = p * cos(lat) + zM * s - a * sqrt(1.0 - e2 * s * s)
            lat = atan2(zM, p * (1.0 - e2 * n / (n + h)))
        }
        val s = sin(lat)
        val h = p * cos(lat) + zM * s - a * sqrt(1.0 - e2 * s * s)
        return GeodeticPosition(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }

    /** ECEF → geodetic (same as the three-argument form). */
    public fun fromEcef(e: EcefPosition): GeodeticPosition = fromEcef(e.xM, e.yM, e.zM)

    /** Meridian radius of curvature M at [latDeg], metres. */
    public fun meridianRadiusM(latDeg: Double): Double {
        val s = sin(Math.toRadians(latDeg))
        val w = 1.0 - eccentricitySquared * s * s
        return semiMajorAxisM * (1.0 - eccentricitySquared) / (w * sqrt(w))
    }

    /** Prime-vertical radius of curvature N at [latDeg], metres. */
    public fun primeVerticalRadiusM(latDeg: Double): Double {
        val s = sin(Math.toRadians(latDeg))
        return semiMajorAxisM / sqrt(1.0 - eccentricitySquared * s * s)
    }

    /** Metres per degree of latitude at [latDeg] (on the ellipsoid, h = 0). */
    public fun metersPerDegLat(latDeg: Double): Double = Math.toRadians(meridianRadiusM(latDeg))

    /** Metres per degree of longitude at [latDeg] (on the ellipsoid, h = 0). */
    public fun metersPerDegLon(latDeg: Double): Double =
        Math.toRadians(primeVerticalRadiusM(latDeg) * cos(Math.toRadians(latDeg)))

    override fun toString(): String = name

    public companion object {
        /** WGS84 (GNSS, ITRF-aligned). */
        public val WGS84: Ellipsoid = Ellipsoid("WGS84", 6_378_137.0, 298.257223563)
        /** GRS80 (NAD83, State Plane). Differs from WGS84 only in the flattening's 6th digit (< 0.1 mm). */
        public val GRS80: Ellipsoid = Ellipsoid("GRS80", 6_378_137.0, 298.257222101)
    }
}
