package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.projection.ProjectionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrsCatalogCsvTest {

    private val fixture = File("src/test/resources/crs/spcs2022_fixture.csv").readText()

    @Test fun `fixture parses, unverified or invalid entries load as non-operational`() {
        val r = CrsCatalogCsv.parse(fixture, CrsCatalogId.SPCS2022)
        val byZone = r.entries.associateBy { it.zoneCode }
        assertEquals(listOf("FX01", "FX02", "FX03", "FX04", "FX05", "FX08", "FX09"), r.entries.map { it.zoneCode })

        val fx1 = byZone.getValue("FX01")
        assertTrue(fx1.operational)
        assertEquals("spcs2022:FX01", fx1.id)
        assertEquals(ProjectionMethod.TRANSVERSE_MERCATOR, fx1.method)
        assertEquals("fixture v0", fx1.dataset)
        assertEquals(AreaOfUse(-80.53, 39.71, -74.68, 42.27), fx1.areaOfUse)

        val fx2 = byZone.getValue("FX02")
        assertFalse(fx2.operational)
        assertNull(fx2.projection)
        assertTrue(fx2.statusNote!!.contains("no verified test vectors"))

        val fx3 = byZone.getValue("FX03")
        assertEquals("EPSG:99999", fx3.id)
        assertEquals("Fixture, quoted name", fx3.name)
        assertEquals(ProjectionMethod.LAMBERT_CONIC_CONFORMAL_2SP, fx3.method)   // by EPSG method code
        assertFalse(fx3.operational)

        assertFalse(byZone.getValue("FX04").operational)
        assertTrue(byZone.getValue("FX04").statusNote!!.contains("missing parameter k_0"))
        assertFalse(byZone.getValue("FX05").operational)
        assertNull(byZone.getValue("FX05").method)
        assertTrue(byZone.getValue("FX05").statusNote!!.contains("unknown projection method"))

        assertEquals(3, r.problems.size)
        assertTrue(r.problems[0], r.problems[0].contains("unknown frame"))
        assertTrue(r.problems[1], r.problems[1].contains("columns"))
        assertTrue(r.problems[2], r.problems[2].contains("duplicate id spcs2022:FX01"))
    }

    @Test fun `wrong header loads nothing, BOM and comments are ignored`() {
        val bad = CrsCatalogCsv.parse("zone,epsg\nFX01,1\n", CrsCatalogId.SPCS2022)
        assertTrue(bad.entries.isEmpty())
        assertEquals(1, bad.problems.size)
        val empty = CrsCatalogCsv.parse("﻿# comment\n\n${CrsCatalogCsv.HEADER}\n", CrsCatalogId.SPCS2022)
        assertTrue(empty.entries.isEmpty())
        assertTrue(empty.problems.isEmpty())
    }

    @Test fun `extra parameters, bad numbers, bad unit and wrong ellipsoid are handled`() {
        val h = CrsCatalogCsv.HEADER
        val r = CrsCatalogCsv.parse("""
            $h
            A1,,Extra,natrf2022,tm,lat_0=0;lon_0=0;k_0=1;x_0=0;y_0=0;foo=1,metre,-1,-1,1,1,d,v.csv
            A2,,Bad number,natrf2022,tm,lat_0=x,metre,-1,-1,1,1,d,v.csv
            A3,,Feet,natrf2022,tm,lat_0=0;lon_0=0;k_0=1;x_0=0;y_0=0,us-ft,-1,-1,1,1,d,v.csv
            A4,,LCC on WGS84,wgs84,lcc2sp,lat_0=40;lon_0=-77;lat_1=41;lat_2=39;x_0=0;y_0=0,metre,-1,-1,1,1,d,v.csv
            A5,,Bad box,natrf2022,tm,lat_0=0;lon_0=0;k_0=1;x_0=0;y_0=0,metre,-1,5,1,1,d,v.csv
            A6,abc,Bad epsg,natrf2022,tm,lat_0=0;lon_0=0;k_0=1;x_0=0;y_0=0,metre,-1,-1,1,1,d,v.csv
        """.trimIndent(), CrsCatalogId.SPCS2022)
        assertEquals(listOf("A1", "A3", "A4"), r.entries.map { it.zoneCode })
        assertTrue(r.entries.none { it.operational })
        assertTrue(r.entries[0].statusNote!!.contains("unknown parameters foo"))
        assertTrue(r.entries[1].statusNote!!.contains("unsupported unit"))
        assertTrue(r.entries[2].statusNote!!.contains("GRS80"))
        assertEquals(3, r.problems.size)
    }

    @Test fun `missing resource reports a problem`() {
        val r = CrsCatalogCsv.fromClasspath("crs/does-not-exist.csv", CrsCatalogId.SPCS2022)
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.problems.size)
    }

    @Test fun `csv splitting handles quotes`() {
        assertEquals(listOf("a", "b, c", "d\"e", ""), CrsCatalogCsv.splitCsv("a,\"b, c\",\"d\"\"e\","))
        assertNull(CrsCatalogCsv.splitCsv("a,\"b"))
    }
}
