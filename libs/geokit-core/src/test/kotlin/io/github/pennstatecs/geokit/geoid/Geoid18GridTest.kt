package io.github.pennstatecs.geokit.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GEOID18 grid against the NGS geoid service and the raw NGS grids. The packed grid ships in this
 * module (src/main/resources/geoid/geoid18.srgt) and is loaded with [Geoid18Grid.fromClasspath]; if it
 * is missing, every test here fails rather than skipping.
 */
class Geoid18GridTest {

    private val grid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }

    @Test fun `matches the NGS geoid service in Pennsylvania`() {
        // geodesy.noaa.gov/api/geoid/ght (GEOID18).
        assertEquals(-31.601, grid.undulation(41.3477, -76.0226)!!, 0.01)
        assertEquals(-33.298, grid.undulation(40.7934, -77.8600)!!, 0.01)
        assertEquals(-32.957, grid.undulation(39.9526, -75.1652)!!, 0.01)
        assertEquals(-34.197, grid.undulation(41.9000, -80.1000)!!, 0.01)
    }

    @Test fun `matches the NGS geoid service across CONUS, Puerto Rico and the Virgin Islands`() {
        // geodesy.noaa.gov/api/geoid/ght?model=14 (GEOID18), queried 2026-11-19.
        val ngs = listOf(
            Triple(34.0522, -118.2437, -35.109), Triple(29.7604, -95.3698, -27.334),
            Triple(25.7617, -80.1918, -25.531), Triple(47.6062, -122.3321, -23.686),
            Triple(44.8000, -68.7800, -24.874), Triple(39.7392, -104.9903, -17.111),
            Triple(46.8700, -113.9900, -14.754), Triple(41.8800, -87.6300, -33.597),
            Triple(18.4655, -66.1057, -43.121), Triple(18.3419, -64.9307, -42.190),
            Triple(17.7500, -64.7500, -41.468),
        )
        for ((lat, lon, n) in ngs) assertEquals("N at $lat, $lon", n, grid.undulation(lat, lon)!!, 0.005)
    }

    @Test fun `packed tiles reproduce the raw NGS grids to a millimetre`() {
        // 600 seeded random points bilinear-interpolated from the raw g2018u0/g2018p0 float grids
        // (libs/geokit-core/tools/coords/geoid_vectors.py).
        val csv = checkNotNull(javaClass.getResourceAsStream("/geoid/geoid18_vectors.csv")) {
            "test resource geoid/geoid18_vectors.csv missing"
        }
        val rows = csv.bufferedReader().use { it.readLines() }.drop(1).filter { it.isNotBlank() }
        assertEquals(600, rows.size)
        for (line in rows) {
            val (lat, lon, n) = line.split(",").map { it.toDouble() }
            assertEquals("N at $lat, $lon", n, grid.undulation(lat, lon)!!, 0.001)
        }
    }

    @Test fun `no value outside GEOID18 coverage`() {
        assertNull(grid.undulation(61.2, -149.9))   // Anchorage
        assertNull(grid.undulation(51.5, -0.1))     // London
    }

    @Test fun `classpath resource path matches the historical asset path`() {
        assertEquals("geoid/geoid18.srgt", Geoid18Grid.RESOURCE_PATH)
        assertEquals(Geoid18Grid.ASSET_PATH, Geoid18Grid.RESOURCE_PATH)
    }
}
