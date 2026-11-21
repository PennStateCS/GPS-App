package app.surrealar.geo.projection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SPCS83 Pennsylvania (LCC 2SP) against NGS NCAT (values captured 2026-11-19). Moved here from the
 * app's CoordinateSystemReferenceTest when LambertConformalConic became internal to geo-core.
 */
class LambertConformalConicTest {

    private val usft = 1200.0 / 3937.0

    @Test fun `PA North matches NCAT`() {
        val (e, n) = LambertConformalConic.PA_NORTH.forward(41.3477, -76.0226)
        assertEquals(744_552.966, e, 0.001)
        assertEquals(132_599.685, n, 0.001)
        assertEquals(2_442_754.191, e / usft, 0.002)    // NCAT ftUS
        assertEquals(435_037.467, n / usft, 0.002)
        assertEquals(2_442_759.076, e / 0.3048, 0.002)  // NCAT international ft
    }

    @Test fun `PA South matches NCAT`() {
        val (e, n) = LambertConformalConic.PA_SOUTH.forward(39.9526, -75.1652)
        assertEquals(820_846.396, e, 0.001)
        assertEquals(71_992.354, n, 0.001)
    }

    @Test fun `LCC inverse round-trips to sub-millimetre`() {
        val (e, n) = LambertConformalConic.PA_NORTH.forward(41.3477, -76.0226)
        val (lat, lon) = LambertConformalConic.PA_NORTH.inverse(e, n)
        assertEquals(41.3477, lat, 1e-9)
        assertEquals(-76.0226, lon, 1e-9)
    }

    @Test fun `public Lcc2 inverse round-trips (PA North zone from the table)`() {
        val zone = SpcsZones.byEpsg(6562)!!
        val lcc = zone.projection as Lcc2
        val (e, n) = lcc.forward(41.3477, -76.0226)
        val (lat, lon) = lcc.inverse(e, n)
        assertEquals(41.3477, lat, 1e-9)
        assertEquals(-76.0226, lon, 1e-9)
    }
}
