package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Lambert Conformal Conic, two standard parallels (EPSG method 9802), on GRS80 — the projection of
 * the US State Plane Coordinate System 1983 zones used here. Formulas: Snyder, "Map Projections —
 * A Working Manual" (USGS PP 1395), §15. Inputs/outputs in degrees and metres.
 */
internal class LambertConformalConic(
    latOriginDeg: Double, private val lonOriginDeg: Double,
    lat1Deg: Double, lat2Deg: Double,
    private val falseEastingM: Double, private val falseNorthingM: Double,
) {
    private val e = sqrt(Ellipsoid.GRS80.eccentricitySquared)
    private val n: Double
    private val aF: Double
    private val rho0: Double

    init {
        val p1 = Math.toRadians(lat1Deg); val p2 = Math.toRadians(lat2Deg); val p0 = Math.toRadians(latOriginDeg)
        val m1 = m(p1); val m2 = m(p2)
        val t1 = t(p1); val t2 = t(p2)
        n = (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
        aF = Ellipsoid.GRS80.semiMajorAxisM * m1 / (n * t1.pow(n))
        rho0 = aF * t(p0).pow(n)
    }

    private fun m(phi: Double) = cos(phi) / sqrt(1 - Ellipsoid.GRS80.eccentricitySquared * sin(phi) * sin(phi))
    private fun t(phi: Double): Double {
        val es = e * sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - es) / (1 + es)).pow(e / 2)
    }

    /** (easting, northing) in metres. */
    fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val rho = aF * t(Math.toRadians(latDeg)).pow(n)
        val theta = n * Math.toRadians(lonDeg - lonOriginDeg)
        return (falseEastingM + rho * sin(theta)) to (falseNorthingM + rho0 - rho * cos(theta))
    }

    /** (latitude, longitude) in degrees. */
    fun inverse(eastingM: Double, northingM: Double): Pair<Double, Double> {
        val dx = eastingM - falseEastingM
        val dy = rho0 - (northingM - falseNorthingM)
        val rho = sqrt(dx * dx + dy * dy) * (if (n < 0) -1 else 1)
        val tp = (rho / aF).pow(1 / n)
        val theta = if (n < 0) atan2(-dx, -dy) else atan2(dx, dy)
        var phi = PI / 2 - 2 * atan(tp)
        repeat(15) {
            val es = e * sin(phi)
            val next = PI / 2 - 2 * atan(tp * ((1 - es) / (1 + es)).pow(e / 2))
            if (abs(next - phi) < 1e-14) return Math.toDegrees(next) to (Math.toDegrees(theta / n) + lonOriginDeg)
            phi = next
        }
        return Math.toDegrees(phi) to (Math.toDegrees(theta / n) + lonOriginDeg)
    }

    companion object {
        /** SPCS83 Pennsylvania North (FIPS 3701), EPSG:6562 — parameters verified against epsg.io. */
        val PA_NORTH = LambertConformalConic(40.1666666666667, -77.75, 41.95, 40.8833333333333, 600_000.0, 0.0)
        /** SPCS83 Pennsylvania South (FIPS 3702), EPSG:6564 — parameters verified against epsg.io. */
        val PA_SOUTH = LambertConformalConic(39.3333333333333, -77.75, 40.9666666666667, 39.9333333333333, 600_000.0, 0.0)
    }
}
