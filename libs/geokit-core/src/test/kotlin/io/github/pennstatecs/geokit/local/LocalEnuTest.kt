package io.github.pennstatecs.geokit.local

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalEnuTest {

    @Test fun `metres per degree at 41N match the WGS84 radii`() {
        assertEquals(111_054.0, Ellipsoid.WGS84.metersPerDegLat(41.0), 1.0)   // sphere: 111,320
        assertEquals(84_135.0, Ellipsoid.WGS84.metersPerDegLon(41.0), 1.0)    // sphere: 84,015
    }

    @Test fun `ENU offsets about an origin at 41N`() {
        val enu = LocalEnu(41.0, -76.0, 300.0)
        val n = enu.toEnu(41.001, -76.0, 300.0)
        assertEquals(0.0, n[0], 1e-6)
        assertEquals(111.054, n[1], 0.01)
        val e = enu.toEnu(41.0, -75.999, 300.0)
        assertEquals(84.135, e[0], 0.01)
        assertEquals(0.0, e[1], 0.001)
        val u = enu.toEnu(41.0, -76.0, 310.5)
        assertEquals(10.5, u[2], 1e-6)
    }

    @Test fun `200 m model - sphere error the old reprojector had`() {
        // 200 m N–S at 41°N: the 111,320 m/° sphere made it ~0.48 m too long.
        val dLat = 200.0 / Ellipsoid.WGS84.metersPerDegLat(41.0)
        assertEquals(0.48, dLat * 111_320.0 - 200.0, 0.01)
    }
}
