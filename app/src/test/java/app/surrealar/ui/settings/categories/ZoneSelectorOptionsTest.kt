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
        assertTrue("Phase 4 WP X: the app stores and outputs SPCS2022 zones", z.selectable)
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

    @Test fun `beta registry (preview ON) - all 953 SPCS2022 zones, selectable, own frame, never NAD83, preview OFF none`() {
        val crs = app.surrealar.coords.Nsrs2022Registries.betaCrs
        assertTrue(ZoneSelectorOptions.spcs2022("", preview = false, registry = crs).isEmpty())
        val all = ZoneSelectorOptions.spcs2022("", preview = true, registry = crs)
        assertEquals(953, all.size)
        assertTrue(all.all { it.selectable && it.beta && it.catalog == CrsCatalogId.SPCS2022 && it.spcs83 == null })
        assertTrue(all.none { it.label.contains("NAD83") })
        assertEquals(mapOf("NATRF2022" to 949, "PATRF2022" to 2, "CATRF2022" to 1, "MATRF2022" to 1), all.groupingBy { it.frameLabel }.eachCount())
        assertEquals(all.size, all.map { it.label }.toSet().size)
        val hi = ZoneSelectorOptions.spcs2022("Hawaii", preview = true, registry = crs).single()
        assertEquals("SPCS2022 Hawaii (150001) · PATRF2022 epoch 2020.0 · Oblique Mercator · m · β beta", hi.label)
        assertEquals("CATRF2022", ZoneSelectorOptions.spcs2022("720001", preview = true, registry = crs).single().frameLabel)
        assertEquals("MATRF2022", ZoneSelectorOptions.spcs2022("GUMP", preview = true, registry = crs).single().frameLabel)
        assertEquals(5, ZoneSelectorOptions.spcs2022("Pennsylvania", preview = true, registry = crs).size)
        // The SPCS83 list is unchanged, SPCS2022 entries come after it.
        val both = ZoneSelectorOptions.options("Pennsylvania", preview = true, registry = crs)
        assertEquals(SpcsZoneHints.searchZones("Pennsylvania").map { it.label }, both.take(2).map { it.label })
        assertTrue(both.drop(2).all { it.catalog == CrsCatalogId.SPCS2022 })
        // Stored id → entry.
        assertEquals("421001", ZoneSelectorOptions.spcs2022ById("spcs2022:421001", crs)!!.definition!!.zoneCode)
        assertNull(ZoneSelectorOptions.spcs2022ById("spcs2022:421001", CrsRegistry.DEFAULT))
        assertNull(ZoneSelectorOptions.spcs2022ById("EPSG:6562", crs))
    }

    @Test fun `SPCS2022 zone suggestion - candidates from locateSpcs2022, never one confident answer from envelopes`() {
        val crs = app.surrealar.coords.Nsrs2022Registries.betaCrs
        // State College, PA: statewide + multizone layers overlap legally.
        val (options, loc) = ZoneSelectorOptions.locate(40.7934, -77.86, crs)
        assertEquals(loc.candidates.map { it.id }, options.map { it.definition!!.id })
        assertTrue(options.size >= 2)
        assertTrue(options.any { it.definition!!.zoneCode == "420001" })
        assertTrue(loc.isAmbiguous)
        val hint = ZoneSelectorOptions.suggestionHint(options, loc)!!
        assertTrue(hint, hint.startsWith("SPCS2022 zones possible at your position: ") && hint.contains("Choose the zone your project uses"))
        // Offshore / no zone: no hint.
        val (none, nloc) = ZoneSelectorOptions.locate(0.0, -30.0, crs)
        assertTrue(none.isEmpty()); assertNull(ZoneSelectorOptions.suggestionHint(none, nloc))
        // Envelope-only answer is worded as approximate.
        val box = io.github.pennstatecs.geokit.crs.CrsZoneLocation(options.take(1).map { it.definition!! },
            io.github.pennstatecs.geokit.crs.CrsLocationBasis.AREA_OF_USE_BOX, false)
        assertTrue(ZoneSelectorOptions.suggestionHint(options.take(1), box)!!.contains("approximate"))
    }

    @Test fun `stub registry without a reachable frame - nothing listed`() {
        val crs = CoordinateSystemGroupsTest.stubCrs(frames = io.github.pennstatecs.geokit.datum.FrameTransformationRegistry.DEFAULT)
        assertTrue(ZoneSelectorOptions.spcs2022("", preview = true, registry = crs).isEmpty())
    }
}
