package io.github.pennstatecs.geokit.ellipsoid

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stakeout offsets via the geodesic (moved from StakeoutGeometryTest): WGS84 north/east offsets at 41°N (meridian ≈ 111,054 m/°, parallel ≈ 84,135 m/°). */
class GeodesicStakeoutTest {

    @Test fun `north offset uses the meridian radius, not a sphere`() {
        val (n, e) = Geodesic.northEastDeltaM(41.0, -76.0, 41.001, -76.0)
        assertEquals(111.054, n, 0.01)       // sphere gave 111.195 (+0.13 %)
        assertEquals(0.0, e, 1e-6)
    }

    @Test fun `east offset uses the prime-vertical radius, not a sphere`() {
        val (n, e) = Geodesic.northEastDeltaM(41.0, -76.0, 41.0, -75.999)
        assertEquals(84.135, e, 0.01)        // sphere gave 83.92 (−0.26 %)
        assertEquals(0.0, n, 0.001)
    }

    @Test fun `distance and bearing are consistent with the north-east offsets`() {
        val from = 41.0 to -76.0
        val (n, e) = Geodesic.northEastDeltaM(from.first, from.second, 41.0009, -75.9988)
        assertEquals(Math.hypot(n, e), Geodesic.distanceM(from.first, from.second, 41.0009, -75.9988), 1e-9)
        val b = Geodesic.initialBearingDeg(from.first, from.second, 41.0009, -75.9988)
        assertEquals(Math.toDegrees(Math.atan2(e, n)), b, 1e-9)
        // A point on the same parallel lies a hair north of the local E–W line (parallels curve
        // poleward), so due-west is 270.0003°, not exactly 270°.
        assertEquals(270.0, Geodesic.initialBearingDeg(41.0, -76.0, 41.0, -76.001), 1e-3)
    }
}
