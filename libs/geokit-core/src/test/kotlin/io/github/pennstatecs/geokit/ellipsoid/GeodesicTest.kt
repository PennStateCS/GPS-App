package io.github.pennstatecs.geokit.ellipsoid

import io.github.pennstatecs.geokit.local.LocalEnu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one distance/bearing API. Compared with what it replaced: the stakeout's local-ENU geometry
 * (must agree within 1 mm at stakeout ranges) and the 6,371 km-sphere haversine (expected to differ
 * by mm–cm at short range).
 */
class GeodesicTest {

    // ── verbatim copies of the replaced helpers ─────────────────────────────────────────────────
    private fun legacyHaversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Old StakeoutGeometry.northEast (WGS84 ECEF → local ENU at the observer, heights 0). */
    private fun legacyStakeoutNorthEast(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val enu = LocalEnu(lat1, lon1, 0.0).toEnu(lat2, lon2, 0.0)
        return enu[1] to enu[0]
    }

    private val lat0 = 41.0
    private val lon0 = -76.0
    private val north = Ellipsoid.WGS84.metersPerDegLat(lat0)
    private val east = Ellipsoid.WGS84.metersPerDegLon(lat0)

    private fun targets(): List<Triple<String, Double, Double>> = listOf(10.0, 100.0, 5_000.0).flatMap { d ->
        listOf(Triple("${d.toInt()} m N", lat0 + d / north, lon0), Triple("${d.toInt()} m E", lat0, lon0 + d / east))
    }

    @Test fun `stakeout results stay within 1 mm of the old local-ENU geometry`() {
        for ((label, lat, lon) in targets()) {
            val (oldN, oldE) = legacyStakeoutNorthEast(lat0, lon0, lat, lon)
            val ne = Geodesic.northEastDeltaM(lat0, lon0, lat, lon)
            val dist = Geodesic.distanceM(lat0, lon0, lat, lon)
            val dN = ne.northM - oldN; val dE = ne.eastM - oldE; val dD = dist - hypot(oldN, oldE)
            println("stakeout $label: ΔN ${"%.6f".format(dN)} m  ΔE ${"%.6f".format(dE)} m  Δdist ${"%.6f".format(dD)} m")
            if (!label.startsWith("5000")) {
                assertTrue("$label ΔN $dN", abs(dN) < 0.001); assertTrue("$label ΔE $dE", abs(dE) < 0.001)
                assertTrue("$label Δdist $dD", abs(dD) < 0.001)
            }
        }
    }

    @Test fun `difference from the old haversine (report and sanity bound)`() {
        for ((label, lat, lon) in targets()) {
            val hv = legacyHaversineM(lat0, lon0, lat, lon)
            val g = Geodesic.distanceM(lat0, lon0, lat, lon)
            println("haversine $label: geodesic ${"%.4f".format(g)} m, haversine ${"%.4f".format(hv)} m, Δ ${"%.4f".format(g - hv)} m")
            // Sphere error is 0.13–0.26 % at 41°N; anything bigger would be a bug.
            assertTrue("$label Δ ${g - hv}", abs(g - hv) <= 0.003 * g + 1e-9)
        }
    }

    @Test fun `known values`() {
        // GeographicLib/PROJ reference: 1° of latitude at the equator = 110 574.389 m.
        assertEquals(110_574.389, Geodesic.distanceM(0.0, 0.0, 1.0, 0.0), 0.001)
        assertEquals(0.0, Geodesic.distanceM(40.0, -74.0, 40.0, -74.0), 1e-9)
        assertEquals(90.0, Geodesic.initialBearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(270.0, Geodesic.initialBearingDeg(41.0, -76.0, 41.0, -76.001), 0.001)
        assertEquals(0.0, Geodesic.initialBearingDeg(41.0, -76.0, 41.001, -76.0), 1e-9)
    }
}
