package app.surrealar.geo.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * GEOID18 grid against the NGS geoid service. The packed grid stays an app asset
 * (app/src/main/assets/geoid/geoid18.srgt); Gradle passes its path as `geoid18.path`.
 */
class Geoid18GridTest {

    private val grid: Geoid18Grid by lazy {
        val path = System.getProperty("geoid18.path")
        val file = path?.let { File(it) }
        assumeTrue(
            "Skipping: GEOID18 grid not found (system property geoid18.path=$path). " +
                "It is the app asset app/src/main/assets/geoid/geoid18.srgt.",
            file != null && file.isFile
        )
        Geoid18Grid(file!!.readBytes())
    }

    @Test fun `matches the NGS geoid service in Pennsylvania`() {
        // geodesy.noaa.gov/api/geoid/ght (GEOID18) — same values as the app's reference test.
        assertEquals(-31.601, grid.undulation(41.3477, -76.0226)!!, 0.01)
        assertEquals(-33.298, grid.undulation(40.7934, -77.8600)!!, 0.01)
        assertEquals(-32.957, grid.undulation(39.9526, -75.1652)!!, 0.01)
        assertEquals(-34.197, grid.undulation(41.9000, -80.1000)!!, 0.01)
    }

    @Test fun `no value outside GEOID18 coverage`() {
        assertNull(grid.undulation(61.2, -149.9))   // Anchorage
        assertNull(grid.undulation(51.5, -0.1))     // London
    }
}
