package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hotine Oblique Mercator (variant B), EPSG method 9815 (EPSG Guidance Note 7-2, §3.2.4), on GRS80,
 * forward and inverse — the "OMC" projection of SPCS2022: the centred (local) rectified form, false
 * easting/northing at the projection centre, skew-axis azimuth [azimuthDeg] at the centre and
 * rectified-to-skew angle [rectifiedToSkewDeg] (SPCS2022 "rectified skew orthomorphic": equal to the
 * azimuth). Matches PROJ `+proj=omerc` (without `+no_uoff`). Angles in degrees, lengths in metres.
 * Verified against NGS-published SPCS2022 example coordinates and PROJ (`tools/nsrs/gen_spcs2022.py`).
 *
 * The SPCS83 Alaska zone 1 path keeps using [HomA] (variant A) unchanged.
 *
 * @param latCentre latitude of projection centre. @param lonCentre longitude of projection centre.
 * @param azimuthDeg azimuth of the initial line at the centre (not ±90°, not 0).
 * @param rectifiedToSkewDeg angle from the rectified to the skew grid.
 * @param kc scale factor on the initial line. @param fe easting at the projection centre.
 * @param fn northing at the projection centre.
 */
public class HomB(
    latCentre: Double,
    lonCentre: Double,
    azimuthDeg: Double,
    rectifiedToSkewDeg: Double,
    kc: Double,
    private val fe: Double,
    private val fn: Double,
) : SpcsProjection {
    override val method: ProjectionMethod get() = ProjectionMethod.HOTINE_OBLIQUE_MERCATOR_B
    override val parameters: Map<String, Double> = linkedMapOf(
        "lat_0" to latCentre, "lonc" to lonCentre, "alpha" to azimuthDeg, "gamma" to rectifiedToSkewDeg,
        "k_0" to kc, "x_0" to fe, "y_0" to fn,
    )
    private val e2 = Ellipsoid.GRS80.eccentricitySquared
    private val e = sqrt(e2)
    private val bb: Double
    private val aa: Double
    private val hh: Double
    private val gamma0: Double
    private val lambda0: Double
    private val ucAbsSigned: Double   // |uc| · sign(φc)
    private val cosGc = cos(Math.toRadians(rectifiedToSkewDeg))
    private val sinGc = sin(Math.toRadians(rectifiedToSkewDeg))

    init {
        require(latCentre > -90.0 && latCentre < 90.0 && latCentre != 0.0) {
            "Hotine Oblique Mercator (variant B): latitude of centre must be within (-90, 90) and not 0 ($latCentre)"
        }
        require(abs(azimuthDeg) > 0.0 && abs(azimuthDeg) < 90.0) {
            "Hotine Oblique Mercator (variant B): azimuth must be within (-90, 90) and not 0 ($azimuthDeg)"
        }
        require(kc > 0.0) { "Hotine Oblique Mercator (variant B): scale factor must be positive ($kc)" }
        val phiC = Math.toRadians(latCentre)
        val alphaC = Math.toRadians(azimuthDeg)
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
        ucAbsSigned = abs(aa / bb * atan(sqrt(dd * dd - 1) / cos(alphaC))) * sign(phiC)
    }

    private fun tsf(phi: Double): Double {
        val es = e * sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - es) / (1 + es)).pow(e / 2)
    }

    private fun dLambda(lonRad: Double): Double {
        var d = lonRad - lambda0
        while (d < -PI) d += 2 * PI
        while (d > PI) d -= 2 * PI
        return d
    }

    /** Latitude/longitude (degrees) → (easting, northing) metres. */
    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val dLam = dLambda(Math.toRadians(lonDeg))
        val q = hh / tsf(phi).pow(bb)
        val s = (q - 1 / q) / 2
        val t = (q + 1 / q) / 2
        val v = sin(bb * dLam)
        val u0 = (-v * cos(gamma0) + s * sin(gamma0)) / t
        val vv = aa * ln((1 - u0) / (1 + u0)) / (2 * bb)
        val cosBl = cos(bb * dLam)
        val uRaw = if (abs(cosBl) < 1e-12) aa * dLam
                   else aa * atan2(s * cos(gamma0) + v * sin(gamma0), cosBl) / bb
        val uu = uRaw - ucAbsSigned
        return (vv * cosGc + uu * sinGc + fe) to (uu * cosGc - vv * sinGc + fn)
    }

    /** Inverse: (easting, northing) metres → (latitude, longitude) degrees; longitude in −180..180. */
    public fun inverse(eastingM: Double, northingM: Double): Pair<Double, Double> {
        val dx = eastingM - fe
        val dy = northingM - fn
        val vp = dx * cosGc - dy * sinGc
        val up = dy * cosGc + dx * sinGc + ucAbsSigned
        val qp = exp(-bb * vp / aa)
        val sp = (qp - 1 / qp) / 2
        val tp = (qp + 1 / qp) / 2
        val vvp = sin(bb * up / aa)
        val upp = (vvp * cos(gamma0) + sp * sin(gamma0)) / tp
        val tt = (hh / sqrt((1 + upp) / (1 - upp))).pow(1 / bb)
        var phi = PI / 2 - 2 * atan(tt)
        for (i in 0 until 30) {
            val es = e * sin(phi)
            val next = PI / 2 - 2 * atan(tt * ((1 - es) / (1 + es)).pow(e / 2))
            val done = abs(next - phi) < 1e-15
            phi = next
            if (done) break
        }
        val lam = lambda0 - atan2(sp * cos(gamma0) - vvp * sin(gamma0), cos(bb * up / aa)) / bb
        return Math.toDegrees(phi) to wrapLon180(Math.toDegrees(lam))
    }
}
