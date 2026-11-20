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
        val s = CoordinateSystemSettings(HorizontalSystem.SPCS_PA_NORTH, unit = LinearUnit.US_SURVEY_FEET,
            vertical = VerticalReference.NAVD88_GEOID18)
        val lines = PointsCsvExporter.build(listOf(point("CP1", "iron pin, found")), convert(s)).trim().lines()
        assertEquals("Point,Northing (ftUS),Easting (ftUS),Elevation (ftUS NAVD88),Description,System", lines[0])
        val f = lines[1].split(",")
        assertEquals("CP1", f[0])
        assertEquals(435_037.47, f[1].toDouble(), 0.01)       // NCAT reference
        assertEquals(2_442_754.19, f[2].toDouble(), 0.01)
        assertEquals((390.0 + 31.601) / (1200.0 / 3937.0), f[3].toDouble(), 0.01)
        assertTrue("description with a comma is quoted", lines[1].contains("\"iron pin, found\""))
    }

    @Test fun `geographic output uses latitude and longitude columns`() {
        val csv = PointsCsvExporter.build(listOf(point("P1")), convert(CoordinateSystemSettings()))
        assertTrue(csv.startsWith("Point,Latitude,Longitude,"))
    }
}
