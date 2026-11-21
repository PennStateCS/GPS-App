package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** A map projection on GRS80: NAD83(2011) latitude/longitude (degrees) → (easting, northing) metres. */
public sealed interface SpcsProjection {
    /** NAD83(2011) latitude/longitude (degrees) → (easting, northing) metres. */
    public fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double>
}

/** [lonDeg] shifted by ±360° to lie within 180° of [originDeg] (Alaska zone 10 spans the antimeridian). */
internal fun lonNear(lonDeg: Double, originDeg: Double): Double {
    var d = lonDeg - originDeg
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return originDeg + d
}

/**
 * Lambert Conic Conformal (2SP), EPSG 9802, on GRS80 (NAD83(2011)). Angles in degrees, lengths in
 * metres. Matches PROJ to < 1 µm over each SPCS zone (spcs83_vectors.csv).
 * @param latOrigin latitude of false origin. @param lonOrigin longitude of false origin.
 * @param lat1 first standard parallel. @param lat2 second standard parallel.
 * @param fe false easting. @param fn false northing.
 */
public class Lcc2(latOrigin: Double, private val lonOrigin: Double, lat1: Double, lat2: Double, fe: Double, fn: Double) : SpcsProjection {
    private val lcc = LambertConformalConic(latOrigin, lonOrigin, lat1, lat2, fe, fn)
    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> = lcc.forward(latDeg, lonNear(lonDeg, lonOrigin))

    /** Inverse: (easting, northing) metres → (latitude, longitude) degrees. */
    public fun inverse(eastingM: Double, northingM: Double): Pair<Double, Double> = lcc.inverse(eastingM, northingM)
}

/**
 * Transverse Mercator, EPSG 9807, by the Krüger n-series to 4th order (EPSG Guidance Note 7-2,
 * "JHS" formulas) — sub-millimetre within State Plane zone widths (UTM: < 1 µm vs PROJ within ±3°).
 * Angles in degrees, lengths in metres; GRS80 unless another [ellipsoid] is given (UTM uses WGS84).
 * @param latOrigin latitude of natural origin. @param lonOrigin central meridian. @param k0 scale factor.
 * @param fe false easting. @param fn false northing.
 */
public class Tm(latOrigin: Double, private val lonOrigin: Double, private val k0: Double,
         private val fe: Double, private val fn: Double,
         private val ellipsoid: Ellipsoid = Ellipsoid.GRS80) : SpcsProjection {
    private val e = sqrt(ellipsoid.eccentricitySquared)
    private val n = ellipsoid.thirdFlattening
    private val b = ellipsoid.semiMajorAxisM / (1 + n) * (1 + n * n / 4 + n.pow(4) / 64)
    private val h = doubleArrayOf(
        n / 2 - 2.0 / 3 * n * n + 5.0 / 16 * n.pow(3) + 41.0 / 180 * n.pow(4),
        13.0 / 48 * n * n - 3.0 / 5 * n.pow(3) + 557.0 / 1440 * n.pow(4),
        61.0 / 240 * n.pow(3) - 103.0 / 140 * n.pow(4),
        49561.0 / 161280 * n.pow(4),
    )
    private val m0: Double = run {
        val phi0 = Math.toRadians(latOrigin)
        if (phi0 == 0.0) 0.0 else {
            val xi0 = atan(sinh(conformalQ(phi0)))
            var xi = xi0
            for (i in 1..4) xi += h[i - 1] * sin(2 * i * xi0)
            b * xi
        }
    }

    private fun conformalQ(phi: Double) = asinh(tan(phi)) - e * atanh(e * sin(phi))

    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val beta = atan(sinh(conformalQ(phi)))
        val eta0 = atanh(cos(beta) * sin(Math.toRadians(lonNear(lonDeg, lonOrigin) - lonOrigin)))
        val xi0 = asin(sin(beta) * cosh(eta0))
        var xi = xi0; var eta = eta0
        for (i in 1..4) {
            xi += h[i - 1] * sin(2 * i * xi0) * cosh(2 * i * eta0)
            eta += h[i - 1] * cos(2 * i * xi0) * sinh(2 * i * eta0)
        }
        return (fe + k0 * b * eta) to (fn + k0 * (b * xi - m0))
    }

    /** Inverse-series coefficients h1'..h4' (EPSG Guidance Note 7-2, Krüger n-series, 4th order). */
    private val hInv = doubleArrayOf(
        n / 2 - 2.0 / 3 * n * n + 37.0 / 96 * n.pow(3) - 1.0 / 360 * n.pow(4),
        1.0 / 48 * n * n + 1.0 / 15 * n.pow(3) - 437.0 / 1440 * n.pow(4),
        17.0 / 480 * n.pow(3) - 37.0 / 840 * n.pow(4),
        4397.0 / 161280 * n.pow(4),
    )

    /**
     * Inverse: (easting, northing) metres → (latitude, longitude) degrees, by the Krüger inverse
     * series (EPSG Guidance Note 7-2). Longitude is returned in −180..180.
     */
    public fun inverse(eastingM: Double, northingM: Double): Pair<Double, Double> {
        val etaP = (eastingM - fe) / (b * k0)
        val xiP = ((northingM - fn) + k0 * m0) / (b * k0)
        var xi0 = xiP; var eta0 = etaP
        for (i in 1..4) {
            xi0 -= hInv[i - 1] * sin(2 * i * xiP) * cosh(2 * i * etaP)
            eta0 -= hInv[i - 1] * cos(2 * i * xiP) * sinh(2 * i * etaP)
        }
        val betaP = asin(sin(xi0) / cosh(eta0))
        val qP = asinh(tan(betaP))
        var qPP = qP + e * atanh(e * tanh(qP))
        repeat(12) { qPP = qP + e * atanh(e * tanh(qPP)) }
        val phi = atan(sinh(qPP))
        var lonDeg = lonOrigin + Math.toDegrees(asin(tanh(eta0) / cos(betaP)))
        while (lonDeg > 180.0) lonDeg -= 360.0
        while (lonDeg < -180.0) lonDeg += 360.0
        return Math.toDegrees(phi) to lonDeg
    }
}

/**
 * Hotine Oblique Mercator variant A, EPSG 9812 (EPSG Guidance Note 7-2) — SPCS83 Alaska zone 1. GRS80;
 * angles in degrees, lengths in metres. Forward only.
 * @param latCentre latitude of projection centre. @param lonCentre longitude of projection centre.
 * @param azimuthDeg azimuth of the initial line. @param rectifiedToSkewDeg angle from rectified to skew grid.
 * @param kc scale factor on the initial line. @param fe false easting. @param fn false northing.
 */
public class HomA(latCentre: Double, lonCentre: Double, azimuthDeg: Double, rectifiedToSkewDeg: Double,
           kc: Double, private val fe: Double, private val fn: Double) : SpcsProjection {
    private val e = sqrt(Ellipsoid.GRS80.eccentricitySquared)
    private val bb: Double
    private val aa: Double
    private val hh: Double
    private val gamma0: Double
    private val lambda0: Double
    private val gammaC = Math.toRadians(rectifiedToSkewDeg)

    init {
        val phiC = Math.toRadians(latCentre)
        val alphaC = Math.toRadians(azimuthDeg)
        val e2 = Ellipsoid.GRS80.eccentricitySquared
        bb = sqrt(1 + e2 * cos(phiC).pow(4) / (1 - e2))
        aa = Ellipsoid.GRS80.semiMajorAxisM * bb * kc * sqrt(1 - e2) / (1 - e2 * sin(phiC).pow(2))
        val t0 = tsf(phiC)
        val d = bb * sqrt(1 - e2) / (cos(phiC) * sqrt(1 - e2 * sin(phiC).pow(2)))
        val dd = if (d < 1) 1.0 else d
        val f = dd + sqrt(dd * dd - 1) * sign(phiC)
        hh = f * t0.pow(bb)
        val g = (f - 1 / f) / 2
        gamma0 = asin(sin(alphaC) / dd)
        lambda0 = Math.toRadians(lonCentre) - asin(g * tan(gamma0)) / bb
    }

    private fun tsf(phi: Double): Double {
        val es = e * sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - es) / (1 + es)).pow(e / 2)
    }

    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        var dLam = Math.toRadians(lonDeg) - lambda0
        if (dLam < -PI) dLam += 2 * PI else if (dLam > PI) dLam -= 2 * PI
        val q = hh / tsf(phi).pow(bb)
        val s = (q - 1 / q) / 2
        val t = (q + 1 / q) / 2
        val v = sin(bb * dLam)
        val u0 = (-v * cos(gamma0) + s * sin(gamma0)) / t
        val vv = aa * ln((1 - u0) / (1 + u0)) / (2 * bb)
        val cosBl = cos(bb * dLam)
        val uu = if (abs(cosBl) < 1e-12) aa * bb * dLam
                 else aa * kotlin.math.atan2(s * cos(gamma0) + v * sin(gamma0), cosBl) / bb
        return (vv * cos(gammaC) + uu * sin(gammaC) + fe) to (uu * cos(gammaC) - vv * sin(gammaC) + fn)
    }
}

/**
 * One official SPCS83 zone on NAD83(2011).
 * @param epsg EPSG code of the zone's metre CRS (persisted in settings — stable).
 * @param ngsZone NGS/FIPS zone number, e.g. 3701.
 * @param name state/zone name, e.g. "Pennsylvania North".
 * @param projection the zone's projection (NAD83(2011) degrees → metres).
 * @param areaOfUse EPSG area of use: west, south, east, north (degrees).
 */
public class SpcsZone(public val epsg: Int, public val ngsZone: Int, public val name: String,
               public val projection: SpcsProjection, public val areaOfUse: DoubleArray) {
    /** Zero-padded NGS zone number, e.g. "3701". */
    public val zoneCode: String get() = "%04d".format(ngsZone)
    /** "Pennsylvania North (3701)". */
    public val label: String get() = "$name ($zoneCode)"

    /** True if the point (degrees) lies inside the zone's EPSG area-of-use box (antimeridian-aware). */
    public fun contains(latDeg: Double, lonDeg: Double): Boolean {
        val (w, s, e, n) = areaOfUse.toList()
        if (latDeg < s || latDeg > n) return false
        return if (e >= w) lonDeg in w..e else lonDeg >= w || lonDeg <= e   // antimeridian
    }

    private val areaDeg2: Double get() {
        val (w, s, e, n) = areaOfUse.toList()
        return (n - s) * (if (e >= w) e - w else e - w + 360)
    }

    public companion object {
        /** Smallest-area zone whose area of use contains the point (state-border overlaps → the more specific). */
        public fun suggestFor(latDeg: Double, lonDeg: Double): SpcsZone? =
            SpcsZones.all.filter { it.contains(latDeg, lonDeg) }.minByOrNull { it.areaDeg2 }
    }
}

/** Registry of all official SPCS83 zones (generated table: SpcsZoneTable). */
public object SpcsZones {
    /** All 118 official SPCS83 zones on NAD83(2011). */
    public val all: List<SpcsZone> get() = SpcsZoneTable.zones
    private val byEpsg by lazy { all.associateBy { it.epsg } }
    /** Zone by the EPSG code of its metre CRS, or null. */
    public fun byEpsg(epsg: Int): SpcsZone? = byEpsg[epsg]
}
