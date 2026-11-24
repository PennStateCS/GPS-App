package app.surrealar.data.export

import app.surrealar.coords.CoordinateConverter
import app.surrealar.coords.SourcePosition
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointsCsvExporterTest {

    private fun point(name: String, note: String? = null) = Coordinate(
        id = name, name = name, latitude = 41.3477, longitude = -76.0226, altitude = 390.0,
        timestamp = 1_795_000_000_000L, icon = "", color = 0, note = note, crsEpsg = 6319,   // already NAD83(2011)
    )

    private fun convert(s: CoordinateSystemSettings) = { c: Coordinate ->
        CoordinateConverter { _, _ -> -31.601 }.convert(
            SourcePosition(c.latitude, c.longitude, c.altitude, null, isNad83 = true, epochYear = 2026.9), s)
    }

    @Test fun `PNEZD in PA North US feet with NAVD88`() {
        val s = CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, unit = LinearUnit.US_SURVEY_FEET,
            vertical = VerticalReference.NAVD88_GEOID18)
        val lines = PointsCsvExporter.build(listOf(point("CP1", "iron pin, found")), convert(s)).trim().lines()
        assertEquals("Point,Northing (ftUS),Easting (ftUS),Elevation (ftUS H GEOID18),Description,System", lines[0])
        assertTrue("row names its vertical datum", lines[1].endsWith("NAVD88"))
        val f = lines[1].split(",")
        assertEquals("CP1", f[0])
        assertEquals(435_037.47, f[1].toDouble(), 0.01)       // NCAT reference
        assertEquals(2_442_754.19, f[2].toDouble(), 0.01)
        assertEquals((390.0 + 31.601) / (1200.0 / 3937.0), f[3].toDouble(), 0.01)
        assertTrue("description with a comma is quoted", lines[1].contains("\"iron pin, found\""))
    }

    @Test fun `formula-like text fields are prefixed with a quote`() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, PointsCsvExporter.csv("=HYPERLINK(\"x\")"))
        assertEquals("'+1", PointsCsvExporter.csv("+1"))
        assertEquals("'-2", PointsCsvExporter.csv("-2"))
        assertEquals("'@SUM(A1)", PointsCsvExporter.csv("@SUM(A1)"))
        assertEquals("'\tx", PointsCsvExporter.csv("\tx"))
        assertEquals("\"'\rx\"", PointsCsvExporter.csv("\rx"))
        assertEquals("'=1,2".let { "\"$it\"" }, PointsCsvExporter.csv("=1,2"))
        assertEquals("CP1", PointsCsvExporter.csv("CP1"))
        assertEquals("a=b", PointsCsvExporter.csv("a=b"))
        assertEquals("", PointsCsvExporter.csv(""))
        val csv = PointsCsvExporter.build(listOf(point("=cmd", "-note")), convert(CoordinateSystemSettings()))
        val row = csv.trim().lines()[1]
        assertTrue(row.startsWith("'=cmd,"))
        assertTrue(row.contains(",'-note,"))
    }

    @Test fun `each row names its own UTM zone across the 78W line`() {
        val west = point("W17").copy(longitude = -78.2)    // zone 17
        val east = point("E18").copy(longitude = -77.8)    // zone 18
        val s = CoordinateSystemSettings(HorizontalSystem.UTM, unit = LinearUnit.METERS)
        val rows = PointsCsvExporter.build(listOf(west, east), convert(s)).trim().lines().drop(1)
        assertTrue(rows[0], rows[0].contains("UTM 17N"))
        assertTrue(rows[1], rows[1].contains("UTM 18N"))
        assertTrue(rows[1], !rows[1].contains("UTM 17N"))
    }

    @Test fun `geographic output uses latitude and longitude columns`() {
        val csv = PointsCsvExporter.build(listOf(point("P1")), convert(CoordinateSystemSettings()))
        assertTrue(csv.startsWith("Point,Latitude,Longitude,"))
    }

    @Test fun `GEOID18 header does not claim NAVD88 when rows are PRVD02`() {
        // Puerto Rico first, so the old first-point/NAVD88 header would have been wrong for every row.
        val pr = point("PR1").copy(latitude = 18.4655, longitude = -66.1057)
        val s = CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18)
        val lines = PointsCsvExporter.build(listOf(pr, point("PA1")), convert(s)).trim().lines()
        assertEquals("Point,Latitude,Longitude,Elevation (m H GEOID18),Description,System", lines[0])
        assertTrue(lines[1], lines[1].endsWith("PRVD02"))
        assertTrue(lines[2], lines[2].endsWith("NAVD88"))
        // Column count is unchanged: 6 header fields, 6 fields per row.
        assertEquals(6, lines[0].split(",").size)
        assertEquals(6, lines[2].split(",").size)
    }

    @Test fun `ellipsoidal and receiver height headers use h and H`() {
        val h = PointsCsvExporter.build(listOf(point("P1")),
            convert(CoordinateSystemSettings(vertical = VerticalReference.ELLIPSOIDAL))).lines()[0]
        assertEquals("Point,Latitude,Longitude,Elevation (m h),Description,System", h)
        val rcvr = PointsCsvExporter.build(listOf(point("P1")),
            convert(CoordinateSystemSettings(vertical = VerticalReference.RECEIVER_MSL))).lines()[0]
        assertEquals("Point,Latitude,Longitude,Elevation (m H rcvr),Description,System", rcvr)
    }
}
