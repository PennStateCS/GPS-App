package app.surrealar.ui.settings.categories

import io.github.pennstatecs.geokit.projection.SpcsZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State Plane zone picker search through geokit's CrsRegistry (SPCS83, operational entries only). */
class SpcsZoneSearchTest {

    @Test fun `blank query lists all 118 SPCS83 zones by label, same objects and EPSG codes`() {
        val all = SpcsZoneHints.searchZones("")
        assertEquals(118, all.size)
        assertEquals(SpcsZones.all.sortedBy { it.label }.map { it.epsg }, all.map { it.epsg })
        assertTrue(all.all { SpcsZones.byEpsg(it.epsg) === it })
    }

    @Test fun `state names, words and codes filter`() {
        val tex = SpcsZoneHints.searchZones("tex")
        assertEquals(5, tex.size)
        assertTrue(tex.all { it.name.startsWith("Texas") })
        assertTrue(SpcsZoneHints.searchZones("pennsylvania north").map { it.epsg }.contains(6562))
        assertEquals(6562, SpcsZoneHints.searchZones("3701").first().epsg)
        assertEquals(6562, SpcsZoneHints.searchZones("6562").first().epsg)
    }

    @Test fun `the chosen zone's full label still finds it (punctuation ignored)`() {
        val pa = SpcsZones.byEpsg(6562)!!
        assertEquals(pa, SpcsZoneHints.searchZones(pa.label).first())
    }

    @Test fun `SPCS2022 is never offered, with a one-line note`() {
        assertTrue(SpcsZoneHints.searchZones("2022").none { it.name.contains("2022") })
        assertTrue(SpcsZoneHints.searchZones("spcs2022").isEmpty())
        assertNotNull(SpcsZoneHints.spcs2022Note())
        assertEquals("SPCS2022: not yet available.", SpcsZoneHints.spcs2022Note())
    }
}
