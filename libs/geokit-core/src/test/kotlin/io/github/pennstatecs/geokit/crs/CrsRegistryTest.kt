package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.ProjectionMethod
import io.github.pennstatecs.geokit.projection.SpcsZones
import io.github.pennstatecs.geokit.projection.Tm
import io.github.pennstatecs.geokit.projection.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

class CrsRegistryTest {

    private val registry = CrsRegistry()

    private fun found(l: CrsLookup): CrsDefinition {
        assertTrue("expected Found, was $l", l is CrsLookup.Found)
        return (l as CrsLookup.Found).definition
    }

    // ---- SPCS83 ----

    @Test fun `every SPCS83 zone resolves by its EPSG id to the same zone and projection object`() {
        val catalog = registry.catalog(CrsCatalogId.SPCS83)
        assertEquals(118, catalog.entries.size)
        assertEquals(118, catalog.operational.size)
        assertEquals(CrsCatalogStatus.SUPPORTED, catalog.status)
        for (z in SpcsZones.all) {
            val d = found(registry.byEpsg(z.epsg))
            assertEquals("EPSG:${z.epsg}", d.id)
            assertSame(z, d.spcsZone)
            assertSame(z.projection, d.projection)
            assertEquals(z.zoneCode, d.zoneCode)
            assertEquals(z.label, d.label)
            assertEquals(ReferenceFrame.NAD83_2011, d.frame)
            assertEquals(CrsUnit.METRE, d.unit)
            assertEquals(z.projection.method, d.method)
            assertEquals(z.projection.parameters, d.parameters)
            val a = z.areaOfUse
            assertEquals(AreaOfUse(a[0], a[1], a[2], a[3]), d.areaOfUse)
            assertSame(d, found(registry.byId("epsg:${z.epsg}")))
            assertSame(d, found(registry.byId("${z.epsg}")))
        }
        assertEquals("Pennsylvania North (3701)", found(registry.byEpsg(6562)).label)
    }

    @Test fun `spcs83 vectors through the registry equal the direct SpcsZone path exactly and match PROJ`() {
        val lines = File("src/test/resources/coords/spcs83_vectors.csv").readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(118 * 9, lines.size)
        var worst = 0.0
        for (line in lines) {
            val f = line.split(",")
            val epsg = f[0].toInt(); val lat = f[1].toDouble(); val lon = f[2].toDouble()
            val direct = SpcsZones.byEpsg(epsg)!!.projection.forward(lat, lon)
            val viaRegistry = found(registry.byEpsg(epsg)).forward(lat, lon)
            assertEquals("EPSG $epsg @ $lat,$lon", direct, viaRegistry)   // bit-identical
            worst = maxOf(worst, abs(viaRegistry.first - f[3].toDouble()), abs(viaRegistry.second - f[4].toDouble()))
        }
        assertTrue("worst $worst m vs PROJ", worst < 0.001)
    }

    @Test fun `SPCS83 parameters rebuild identical projections through ProjectionMethod`() {
        // The catalog format can express every SPCS83 zone: method + named parameters reproduce the
        // generated table bit for bit.
        val lines = File("src/test/resources/coords/spcs83_vectors.csv").readLines().drop(1).filter { it.isNotBlank() }
        for (line in lines) {
            val f = line.split(",")
            val z = SpcsZones.byEpsg(f[0].toInt())!!
            val rebuilt = z.projection.method.create(z.projection.parameters)
            assertEquals(z.label, z.projection.forward(f[1].toDouble(), f[2].toDouble()), rebuilt.forward(f[1].toDouble(), f[2].toDouble()))
        }
        assertEquals(setOf(ProjectionMethod.TRANSVERSE_MERCATOR, ProjectionMethod.LAMBERT_CONIC_CONFORMAL_2SP,
            ProjectionMethod.HOTINE_OBLIQUE_MERCATOR_A), SpcsZones.all.map { it.projection.method }.toSet())
    }

    // ---- UTM ----

    @Test fun `UTM catalog has zones 1-60 north and south with EPSG ids`() {
        val c = registry.catalog(CrsCatalogId.UTM)
        assertEquals(120, c.operational.size)
        val d = found(registry.byEpsg(32618))
        assertEquals("18N", d.zoneCode)
        assertEquals("WGS 84 / UTM zone 18N", d.name)
        assertEquals(ReferenceFrame.WGS84_AS_ITRF2014, d.frame)
        assertEquals(AreaOfUse(-78.0, 0.0, -72.0, 84.0), d.areaOfUse)
        assertSame(d, registry.utm(18, 'N'))
        assertEquals(AreaOfUse(-78.0, -80.0, -72.0, 0.0), found(registry.byEpsg(32718)).areaOfUse)
        assertEquals(mapOf("lat_0" to 0.0, "lon_0" to -75.0, "k_0" to 0.9996, "x_0" to 500000.0, "y_0" to 0.0), d.parameters)
        assertEquals(10_000_000.0, registry.utm(18, 'S').parameters["y_0"]!!, 0.0)
        for (zone in 1..60) for (h in listOf('N', 'S')) {
            assertSame(Utm.projection(zone, h), registry.utm(zone, h).projection)
        }
    }

    @Test fun `UTM vectors through the registry equal Utm forward exactly`() {
        val vectors = File("src/test/resources/utm/utm_vectors.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("zone") }
        assertEquals(1134, vectors.size)
        var worst = 0.0
        for (l in vectors) {
            val f = l.split(",")
            val zone = f[0].toInt(); val hemi = f[1][0]; val lat = f[2].toDouble(); val lon = f[3].toDouble()
            val direct = Utm.forward(lat, lon, zone)
            assertEquals(hemi, direct.hemisphere)
            val viaRegistry = found(registry.byEpsg((if (hemi == 'N') 32600 else 32700) + zone)).forward(lat, lon)
            assertEquals("$l", direct.eastingM to direct.northingM, viaRegistry)   // bit-identical
            worst = maxOf(worst, abs(viaRegistry.first - f[4].toDouble()), abs(viaRegistry.second - f[5].toDouble()))
        }
        assertTrue("worst $worst m vs PROJ", worst < 0.001)
    }

    // ---- Geographic ----

    @Test fun `geographic CRSs are operational only for frames with verified transformations`() {
        val op = registry.catalog(CrsCatalogId.GEOGRAPHIC).operational.map { it.frame }.toSet()
        assertEquals(setOf(ReferenceFrame.ITRF2014, ReferenceFrame.ITRF2020, ReferenceFrame.WGS84_AS_ITRF2014,
            ReferenceFrame.NAD83_2011), op)
        assertEquals(ReferenceFrame.entries.size, registry.catalog(CrsCatalogId.GEOGRAPHIC).entries.size)
        val nad = found(registry.byId("geographic:nad83-2011"))
        assertEquals(6318, nad.epsg)
        assertEquals(CrsKind.GEOGRAPHIC, nad.kind)
        assertNull(nad.projection)
        val natrf = registry.byId("Geographic:NATRF2022")
        assertTrue(natrf is CrsLookup.Unsupported)
        natrf as CrsLookup.Unsupported
        assertFalse(natrf.definition!!.operational)
        assertTrue(natrf.explanation, natrf.explanation.contains("NATRF2022"))
        assertSame(registry.geographic(ReferenceFrame.ITRF2020), found(registry.byId("geographic:itrf2020")))
    }

    @Test(expected = IllegalStateException::class)
    fun `geographic definitions have no forward projection`() {
        registry.geographic(ReferenceFrame.ITRF2020).forward(41.0, -76.0)
    }

    // ---- Lookups ----

    @Test fun `unknown and malformed ids are not found`() {
        for (id in listOf("EPSG:1", "EPSG:4326", "garbage", "", "epsg:", "geographic:nope", "spcs83:3701")) {
            assertTrue(id, registry.byId(id) is CrsLookup.NotFound)
            assertNull(registry.byId(id).definitionOrNull)
        }
        assertTrue(registry.byEpsg(-5) is CrsLookup.NotFound)
    }

    @Test fun `search finds zones by name, zone code, EPSG code and catalog`() {
        assertEquals(listOf("EPSG:6562", "EPSG:6564"), registry.search("pennsylvania").map { it.id })
        assertEquals(listOf("EPSG:6562"), registry.search("Pennsylvania  NORTH").map { it.id })
        assertEquals("EPSG:6562", registry.search("3701").first().id)
        assertEquals("EPSG:6562", registry.search("6562").first().id)
        assertTrue(registry.search("utm 18").map { it.id }.containsAll(listOf("EPSG:32618", "EPSG:32718")))
        assertEquals(listOf("EPSG:32618"), registry.search("18n", CrsCatalogId.UTM).map { it.id })
        assertEquals(118, registry.search("", CrsCatalogId.SPCS83, limit = 1000).size)
        assertEquals(5, registry.search("", CrsCatalogId.SPCS83, limit = 5).size)
        assertTrue(registry.search("kentucky", CrsCatalogId.UTM).isEmpty())
        assertEquals(3, registry.search("kentucky").size)   // North, South, Single Zone
        assertTrue(registry.search("natrf2022").isEmpty())  // non-operational only
        assertEquals(listOf("geographic:natrf2022"),
            registry.search("natrf2022", CrsCatalogId.GEOGRAPHIC, includeNonOperational = true).map { it.id })
        assertTrue(registry.search("", CrsCatalogId.SPCS2022, includeNonOperational = true).isEmpty())
    }

    @Test fun `filter by position uses the SPCS83 locator, the UTM zone and areas of use`() {
        val wb = 41.3477 to -76.0226   // Wilkes-Barre
        assertEquals("EPSG:6562", registry.atPosition(wb.first, wb.second, CrsCatalogId.SPCS83).first().id)
        assertEquals(listOf("EPSG:32618"), registry.atPosition(wb.first, wb.second, CrsCatalogId.UTM).map { it.id })
        assertEquals(listOf("EPSG:32756"), registry.atPosition(-33.86, 151.21, CrsCatalogId.UTM).map { it.id })
        assertTrue(registry.atPosition(wb.first, wb.second, CrsCatalogId.GEOGRAPHIC).any { it.frame == ReferenceFrame.NAD83_2011 })
        assertFalse(registry.atPosition(51.5, -0.1, CrsCatalogId.GEOGRAPHIC).any { it.frame == ReferenceFrame.NAD83_2011 })
        assertTrue(registry.atPosition(51.5, -0.1, CrsCatalogId.SPCS83).isEmpty())
        assertTrue(registry.atPosition(wb.first, wb.second, CrsCatalogId.SPCS2022).isEmpty())
        assertTrue(registry.atPosition(85.0, 0.0, CrsCatalogId.UTM).isEmpty())
        assertTrue(registry.atPosition(Double.NaN, 0.0, CrsCatalogId.UTM).isEmpty())
        // Kentucky: both legal zones are candidates, smallest first.
        val ky = registry.atPosition(38.2, -84.87, CrsCatalogId.SPCS83).map { it.zoneCode }
        assertEquals("1601", ky.first())
        assertTrue(ky.contains("1600"))
        // Alaska zone 10 crosses the antimeridian.
        assertTrue(registry.atPosition(52.0, 179.0, CrsCatalogId.SPCS83).any { it.zoneCode == "5010" })
    }

    // ---- SPCS2022 ----

    @Test fun `shipped SPCS2022 catalog has no operational entries and every lookup is unsupported`() {
        val c = registry.catalog(CrsCatalogId.SPCS2022)
        assertTrue(c.entries.isEmpty())
        assertTrue(c.operational.isEmpty())
        assertTrue("problems ${c.problems}", c.problems.isEmpty())   // resource found and parsed
        assertEquals(CrsCatalogStatus.UNSUPPORTED, c.status)
        assertEquals(CrsRegistry.SPCS2022_UNSUPPORTED, c.explanation)
        val l = registry.byId("spcs2022:3701")
        assertTrue(l is CrsLookup.Unsupported)
        l as CrsLookup.Unsupported
        assertEquals(CrsCatalogId.SPCS2022, l.catalog)
        assertNull(l.definition)
        assertTrue(l.explanation.contains("PROJ 9.8.1"))
    }

    @Test fun `fixture SPCS2022 catalog only exposes verified entries as operational`() {
        val text = File("src/test/resources/crs/spcs2022_fixture.csv").readText()
        val r = CrsRegistry(spcs2022Source = { CrsCatalogCsv.parse(text, CrsCatalogId.SPCS2022) })
        val c = r.catalog(CrsCatalogId.SPCS2022)
        assertEquals(listOf("FX01", "FX02", "FX03", "FX04", "FX05"), c.entries.map { it.zoneCode })
        assertEquals(listOf("FX01"), c.operational.map { it.zoneCode })
        assertEquals(CrsCatalogStatus.SUPPORTED, c.status)
        assertTrue(c.problems.any { it.contains("EPSG:6562") && it.contains("built-in") })

        val fx1 = found(r.byId("SPCS2022:fx01"))
        assertEquals(ReferenceFrame.NATRF2022, fx1.frame)
        assertEquals("Fixture Verified TM (FX01)", fx1.label)
        assertEquals(Tm(39.0, -77.75, 0.99995, 600000.0, 0.0).forward(41.0, -76.0), fx1.forward(41.0, -76.0))

        for (id in listOf("spcs2022:FX02", "EPSG:99999", "spcs2022:FX04", "spcs2022:FX05")) {
            val l = r.byId(id)
            assertTrue("$id: $l", l is CrsLookup.Unsupported)
            l as CrsLookup.Unsupported
            assertNotNull(l.definition)
            assertNull(l.definition!!.projection)
            assertTrue(l.explanation.startsWith(CrsRegistry.SPCS2022_UNSUPPORTED))
        }
        assertSame(SpcsZones.byEpsg(6562), found(r.byEpsg(6562)).spcsZone)   // built-in wins
        assertTrue(r.byId("spcs2022:FX99") is CrsLookup.Unsupported)
        assertEquals(listOf("FX01"), r.atPosition(41.0, -76.0, CrsCatalogId.SPCS2022).map { it.zoneCode })
        assertEquals(listOf("FX01"), r.search("fixture").map { it.zoneCode })
        assertEquals(5, r.search("fixture", includeNonOperational = true).size)
    }

    @Test fun `concurrent first use builds each catalog once`() {
        val r = CrsRegistry()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..32).map { i ->
                Callable { if (i % 2 == 0) r.byEpsg(6562).definitionOrNull else r.search("3701").first() }
            }).map { it.get() }
            assertTrue(results.all { it != null && it === results[0] })
        } finally {
            pool.shutdown()
        }
    }

    @Test fun `default registry is shared`() {
        assertSame(CrsRegistry.DEFAULT, CrsRegistry.DEFAULT)
        assertEquals(4, CrsRegistry.DEFAULT.catalogs.size)
    }
}
