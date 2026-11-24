package io.github.pennstatecs.geokit.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SpcsZonesTest {

    @Test fun `all 118 official SPCS83 zones are present with unique EPSG codes and zone numbers`() {
        assertEquals(118, SpcsZones.all.size)
        assertEquals(118, SpcsZones.all.map { it.epsg }.toSet().size)
        assertEquals(118, SpcsZones.all.map { it.ngsZone }.toSet().size)
        assertEquals("Pennsylvania North (3701)", SpcsZones.byEpsg(6562)!!.label)
        assertEquals("Kentucky North (1601)", SpcsZones.byEpsg(6470)!!.label)
        assertEquals("Alabama East (0101)", SpcsZones.byEpsg(6355)!!.label)
    }

    /**
     * Every zone against PROJ 9.8.1 (pyproj 3.8.0): 9 points spread over each zone's area of use,
     * NAD83(2011) geographic → zone metres. Covers all three projection methods (LCC 2SP, TM, HOM A).
     */
    @Test fun `every zone matches PROJ to a millimetre`() {
        val lines = File("src/test/resources/coords/spcs83_vectors.csv").readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(118 * 9, lines.size)
        var worst = 0.0; var worstAt = ""
        for (line in lines) {
            val f = line.split(",")
            val zone = SpcsZones.byEpsg(f[0].toInt())
            assertNotNull("zone ${f[0]}", zone)
            val (e, n) = zone!!.projection.forward(f[1].toDouble(), f[2].toDouble())
            val err = maxOf(abs(e - f[3].toDouble()), abs(n - f[4].toDouble()))
            if (err > worst) { worst = err; worstAt = "${zone.label} @ ${f[1]}, ${f[2]}" }
        }
        println("SPCS vs PROJ: worst ${"%.3e".format(worst)} m at $worstAt")
        assertTrue("worst error ${"%.4f".format(worst)} m at $worstAt", worst < 0.001)
    }

    // Suggestions come from county-defined zone extents (SpcsZoneLocatorTest covers boundaries, e.g.
    // Philadelphia, which lies inside New Jersey's area-of-use box, and Hazleton).
    @Test fun `zone suggestion picks the zone covering the point`() {
        assertEquals(3702, SpcsZone.suggestFor(39.9526, -75.1652)!!.ngsZone)   // Philadelphia
        assertEquals(3701, SpcsZone.suggestFor(41.3477, -76.0226)!!.ngsZone)   // Wilkes-Barre
        assertEquals(3702, SpcsZone.suggestFor(40.2732, -76.8867)!!.ngsZone)   // Harrisburg
        assertEquals(405, SpcsZone.suggestFor(34.0522, -118.2437)!!.ngsZone)   // Los Angeles → CA 5
        assertEquals(5200, SpcsZone.suggestFor(18.4655, -66.1057)!!.ngsZone)   // San Juan
        assertEquals(null, SpcsZone.suggestFor(51.5, -0.1))
    }
}
