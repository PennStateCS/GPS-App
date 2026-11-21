package app.surrealar.geo.projection

import app.surrealar.geo.datum.Grs80

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

/** A map projection on GRS80: NAD83(2011) latitude/longitude (degrees) → (easting, northing) metres. */
sealed interface SpcsProjection {
    fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double>
}

/** [lonDeg] shifted by ±360° to lie within 180° of [originDeg] (Alaska zone 10 spans the antimeridian). */
internal fun lonNear(lonDeg: Double, originDeg: Double): Double {
    var d = lonDeg - originDeg
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return originDeg + d
}

/** Lambert Conic Conformal (2SP), EPSG 9802. */
class Lcc2(latOrigin: Double, private val lonOrigin: Double, lat1: Double, lat2: Double, fe: Double, fn: Double) : SpcsProjection {
    private val lcc = LambertConformalConic(latOrigin, lonOrigin, lat1, lat2, fe, fn)
    override fun forward(latDeg: Double, lonDeg: Double) = lcc.forward(latDeg, lonNear(lonDeg, lonOrigin))
}

/**
 * Transverse Mercator, EPSG 9807, by the Krüger n-series to 4th order (EPSG Guidance Note 7-2,
 * "JHS" formulas) — sub-millimetre within State Plane zone widths.
 */
class Tm(latOrigin: Double, private val lonOrigin: Double, private val k0: Double,
         private val fe: Double, private val fn: Double) : SpcsProjection {
    private val e = sqrt(Grs80.E2)
    private val n = Grs80.F / (2 - Grs80.F)
    private val b = Grs80.A / (1 + n) * (1 + n * n / 4 + n.pow(4) / 64)
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
}

/** Hotine Oblique Mercator variant A, EPSG 9812 (EPSG Guidance Note 7-2) — SPCS83 Alaska zone 1. */
class HomA(latCentre: Double, lonCentre: Double, azimuthDeg: Double, rectifiedToSkewDeg: Double,
           kc: Double, private val fe: Double, private val fn: Double) : SpcsProjection {
    private val e = sqrt(Grs80.E2)
    private val bb: Double
    private val aa: Double
    private val hh: Double
    private val gamma0: Double
    private val lambda0: Double
    private val gammaC = Math.toRadians(rectifiedToSkewDeg)

    init {
        val phiC = Math.toRadians(latCentre)
        val alphaC = Math.toRadians(azimuthDeg)
        val e2 = Grs80.E2
        bb = sqrt(1 + e2 * cos(phiC).pow(4) / (1 - e2))
        aa = Grs80.A * bb * kc * sqrt(1 - e2) / (1 - e2 * sin(phiC).pow(2))
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
 * @param areaOfUse EPSG area of use: west, south, east, north (degrees).
 */
class SpcsZone(val epsg: Int, val ngsZone: Int, val name: String, val projection: SpcsProjection,
               val areaOfUse: DoubleArray) {
    val zoneCode: String get() = "%04d".format(ngsZone)
    /** "Pennsylvania North (3701)". */
    val label: String get() = "$name ($zoneCode)"

    fun contains(latDeg: Double, lonDeg: Double): Boolean {
        val (w, s, e, n) = areaOfUse.toList()
        if (latDeg < s || latDeg > n) return false
        return if (e >= w) lonDeg in w..e else lonDeg >= w || lonDeg <= e   // antimeridian
    }

    private val areaDeg2: Double get() {
        val (w, s, e, n) = areaOfUse.toList()
        return (n - s) * (if (e >= w) e - w else e - w + 360)
    }

    companion object {
        /** Smallest-area zone whose area of use contains the point (state-border overlaps → the more specific). */
        fun suggestFor(latDeg: Double, lonDeg: Double): SpcsZone? =
            SpcsZones.all.filter { it.contains(latDeg, lonDeg) }.minByOrNull { it.areaDeg2 }
    }
}

/** Registry of all official SPCS83 zones (generated table: SpcsZoneTable). */
object SpcsZones {
    val all: List<SpcsZone> get() = SpcsZoneTable.zones
    private val byEpsg by lazy { all.associateBy { it.epsg } }
    fun byEpsg(epsg: Int): SpcsZone? = byEpsg[epsg]
}
