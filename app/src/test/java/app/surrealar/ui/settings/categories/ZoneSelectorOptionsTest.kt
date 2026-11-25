package app.surrealar.ui.settings.categories

import app.surrealar.coords.CoordinateSystemGroupsTest
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 WP U: the State Plane zone selector is driven by CrsRegistry operational status. With the shipped
 * registry (0 operational SPCS2022 zones) it is exactly the SPCS83 list; with a stub registry where NATRF2022
 * is reachable, SPCS2022 zones appear (preview ON only) labelled with their own frame, epoch, projection,
 * unit and beta badge, never as NAD83, and are not selectable while the app cannot output them.
 */
class ZoneSelectorOptionsTest {

    @Test fun `shipped registry - SPCS83 list unchanged, no SPCS2022 entry with the preview on or off`() {
        for (q in listOf("", "Penn", "420001", "PA_NE", "north")) {
            for (preview in listOf(false, true)) {
                val options = ZoneSelectorOptions.options(q, preview)
                assertEquals("$q/$preview", SpcsZoneHints.searchZones(q).map { it.label }, options.map { it.label })
                assertTrue(options.all { it.catalog == CrsCatalogId.SPCS83 && it.selectable && !it.beta && it.spcs83 != null })
            }
        }
        assertTrue(ZoneSelectorOptions.spcs2022("", preview = true).isEmpty())
        assertEquals(0, CrsRegistry.DEFAULT.catalog(CrsCatalogId.SPCS2022).operational.size)
    }

    @Test fun `stub registry - SPCS2022 zone appears with its own frame, epoch, projection, unit and beta badge`() {
        val crs = CoordinateSystemGroupsTest.stubCrs()
        assertTrue("preview OFF: none", ZoneSelectorOptions.spcs2022("", preview = false, registry = crs).isEmpty())
        val z = ZoneSelectorOptions.spcs2022("", preview = true, registry = crs).single()
        assertEquals("SPCS2022 Pennsylvania North (3701) · NATRF2022 epoch 2020.0 · LCC 1SP · m · β beta", z.label)
        assertEquals(CrsCatalogId.SPCS2022, z.catalog)
        assertEquals("NATRF2022", z.frameLabel)
        assertEquals("epoch 2020.0", z.epochLabel)
        assertEquals("LCC 1SP", z.projection)
        assertEquals("m", z.unit)
        assertTrue(z.beta)
        assertFalse("no output path in this app version", z.selectable)
        assertNull(z.spcs83)
        assertFalse("never NAD83", z.label.contains("NAD83"))
        // The NAD83-frame row of the stub catalog is not an SPCS2022 zone: never listed.
        assertTrue(ZoneSelectorOptions.spcs2022("NAD83", preview = true, registry = crs).isEmpty())
        // Distinct from the SPCS83 zone of the same name, which keeps its label and NAD83(2011).
        val both = ZoneSelectorOptions.options("Pennsylvania North", preview = true, registry = crs)
        assertEquals(listOf("Pennsylvania North (3701)", z.label), both.map { it.label })
        assertEquals("NAD83(2011)", both.first().frameLabel)
        assertEquals(both.size, both.map { it.label }.toSet().size)
    }

    @Test fun `stub registry - searchable by name, code and alias, selectable only once output is supported`() {
        val crs = CoordinateSystemGroupsTest.stubCrs()
        for (q in listOf("Pennsylvania", "3701", "PA_N")) {
            assertEquals(q, 1, ZoneSelectorOptions.spcs2022(q, preview = true, registry = crs).size)
        }
        assertTrue(ZoneSelectorOptions.spcs2022("Texas", preview = true, registry = crs).isEmpty())
        assertTrue(ZoneSelectorOptions.spcs2022("", preview = true, registry = crs, outputSupported = true).single().selectable)
    }

    @Test fun `stub registry without a reachable frame - nothing listed`() {
        val crs = CoordinateSystemGroupsTest.stubCrs(frames = io.github.pennstatecs.geokit.datum.FrameTransformationRegistry.DEFAULT)
        assertTrue(ZoneSelectorOptions.spcs2022("", preview = true, registry = crs).isEmpty())
    }
}
