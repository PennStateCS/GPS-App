package io.github.pennstatecs.geokit.ellipsoid

import org.junit.Assert.assertEquals
import org.junit.Test

class GeodesyEnuTest {

    private fun enuOf(lat: Double, lon: Double, h: Double, refLat: Double, refLon: Double, refH: Double): Triple<Double, Double, Double> {
        val v = io.github.pennstatecs.geokit.local.LocalEnu(refLat, refLon, refH).toEnu(lat, lon, h)
        return Triple(v[0], v[1], v[2])
    }

    @Test fun `a point north of the reference is pure north`() {
        val (e, n, u) = enuOf(41.00001, -76.0, 200.0, 41.0, -76.0, 200.0)
        assertEquals(0.0, e, 1e-3)
        assertEquals(1.11, n, 0.01)       // 1e-5 deg latitude ≈ 1.11 m
        assertEquals(0.0, u, 1e-3)
    }

    @Test fun `a point east of the reference is pure east`() {
        val (e, n, u) = enuOf(41.0, -75.99999, 200.0, 41.0, -76.0, 200.0)
        assertEquals(0.84, e, 0.01)       // 1e-5 deg longitude × cos(41°) ≈ 0.84 m
        assertEquals(0.0, n, 1e-3)
        assertEquals(0.0, u, 1e-3)
    }

    @Test fun `a height change is pure up`() {
        val (e, n, u) = enuOf(41.0, -76.0, 203.0, 41.0, -76.0, 200.0)
        assertEquals(0.0, e, 1e-6)
        assertEquals(0.0, n, 1e-6)
        assertEquals(3.0, u, 1e-6)
    }
}
