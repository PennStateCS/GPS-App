package app.surrealar.data.export

import app.surrealar.coords.ConvertedPosition
import app.surrealar.coords.CoordinateConverter
import app.surrealar.coords.SourcePosition
import app.surrealar.coords.Spcs2022ZoneLookup
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import app.surrealar.coords.Nsrs2022Registries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stabilization S2 item 5: one representation per CSV export. The header comes from the settings (or, for the
 * legacy overload, the first point shown in its configured system), every row has the header's column count, and a
 * point without a position in that representation is blank with "unavailable: <reason>" in System — never
 * latitude/longitude under Northing/Easting or grid values under Latitude/Longitude.
 */
class PointsCsvExporterConsistencyTest {

    private fun point(name: String, lat: Double = 40.7934, lon: Double = -77.86) = Coordinate(
        id = name, name = name, latitude = lat, longitude = lon, altitude = 350.0,
        timestamp = 1_795_000_000_000L, icon = "", color = 0, crsEpsg = 4979,
    )

    private fun spcs(unit: LinearUnit = LinearUnit.METERS) = CoordinateSystemSettings(HorizontalSystem.SPCS2022,
        unit = unit, vertical = VerticalReference.ELLIPSOIDAL, nsrs2022Preview = true, spcs2022Zone = "spcs2022:421003")

    /** Points whose name is in [loading] see the SPCS2022 catalog as still loading; [missing] as an unknown zone. */
    private fun converter(loading: Set<String> = emptySet(), missing: Set<String> = emptySet()): (Coordinate, CoordinateSystemSettings) -> ConvertedPosition {
        var current = ""
        val c = CoordinateConverter({ _, _ -> 30.0 }, { null }, { null }, FrameTransformationRegistry.DEFAULT,
            FrameTransformationRegistry.NSRS2022_BETA,
            { id ->
                when (current) {
                    in loading -> Spcs2022ZoneLookup.Loading
                    in missing -> Spcs2022ZoneLookup.Unknown
                    else -> Nsrs2022Registries.spcs2022Zone(id)?.let { Spcs2022ZoneLookup.Found(it) } ?: Spcs2022ZoneLookup.Unknown
                }
            })
        return { p, s ->
            current = p.name
            c.convert(SourcePosition(p.latitude, p.longitude, p.altitude, null, isNad83 = false, epochYear = 2026.886), s)
        }
    }

    private fun assertColumns(lines: List<String>, n: Int) =
        lines.forEach { assertEquals(it, n, it.split(",").size) }

    @Test fun `SPCS2022 loading for the FIRST point - legacy overload no longer switches the file to latitude-longitude`() {
        val conv = converter(loading = setOf("P1"))
        val s = spcs()
        val lines = PointsCsvExporter.build(listOf(point("P1"), point("P2"), point("P3", 40.8)) { conv(it, s) }).trim().lines()
        // Before the fix: header "Latitude,Longitude" from P1's fallback, then P2/P3 grid values under it.
        assertEquals("Point,Northing (m),Easting (m),Elevation (m h),Description,System", lines[0])
        assertEquals("P1,,,,,unavailable: SPCS2022 zone data is loading", lines[1])
        assertTrue(lines[2].split(",")[1].toDouble() > 1000.0)
        assertTrue(lines[3].contains("SPCS2022 421003 (beta)"))
        assertColumns(lines, 6)
    }

    @Test fun `SPCS2022 mixed - loading and missing zone rows are blank with a reason, never lat-lon under N-E`() {
        val conv = converter(loading = setOf("P2"), missing = setOf("P3"))
        val s = spcs()
        val r = PointsCsvExporter.export(listOf(point("P1"), point("P2"), point("P3"), point("P4", 40.8)),
            PointsCsvExporter.Layout.of(s), includePositionReference = false) { conv(it, s) }
        val lines = r.text.trim().lines()
        assertEquals("Point,Northing (m),Easting (m),Elevation (m h),Description,System", lines[0])
        assertEquals("P2,,,,,unavailable: SPCS2022 zone data is loading", lines[2])
        assertEquals("P3,,,,,unavailable: SPCS2022 zone spcs2022:421003 is not available", lines[3])
        // Before the fix the legacy build wrote P2/P3 as "40.79…,-77.86…" under Northing/Easting.
        val legacy = PointsCsvExporter.build(listOf(point("P1"), point("P2"), point("P3"), point("P4", 40.8))) { conv(it, s) }
        assertEquals(r.text, legacy)
        assertEquals(4, r.rowCount)
        assertEquals(2, r.unavailableCount)
        assertColumns(lines, 6)
    }

    @Test fun `SPCS2022 zone missing for every point - grid header from the settings, all rows unavailable`() {
        val conv = converter(missing = setOf("A", "B"))
        val s = spcs(LinearUnit.US_SURVEY_FEET)
        val r = PointsCsvExporter.export(listOf(point("A"), point("B")), PointsCsvExporter.Layout.of(s), false) { conv(it, s) }
        val lines = r.text.trim().lines()
        // SPCS2022 shows US survey feet as international feet (the converter's rule).
        assertEquals("Point,Northing (ft),Easting (ft),Elevation (ft h),Description,System", lines[0])
        assertEquals(2, r.unavailableCount)
        assertColumns(lines, 6)
    }

    @Test fun `Layout of SPCS2022 in US survey feet agrees with the converter's unit`() {
        val s = spcs(LinearUnit.US_SURVEY_FEET)
        val p = converter()(point("P1"), s)
        assertEquals(p.unit, PointsCsvExporter.Layout.of(s).unit)
        assertEquals(LinearUnit.INTERNATIONAL_FEET, p.unit)
    }

    @Test fun `a failed conversion is that row's reason, not the whole export's`() {
        val s = CoordinateSystemSettings(HorizontalSystem.UTM)
        val ok = converter()
        val r = PointsCsvExporter.export(listOf(point("P1"), point("BAD"), point("P3")), PointsCsvExporter.Layout.of(s), true) {
            if (it.name == "BAD") throw IllegalStateException("no verified transformation, test") else ok(it, s)
        }
        val lines = r.text.trim().lines()
        assertEquals("BAD,,,,,\"unavailable: conversion failed: no verified transformation, test\",", lines[2])
        assertEquals(1, r.unavailableCount)
        // 7 columns everywhere (the comma inside the quoted reason is not a separator).
        assertEquals(7, lines[0].split(",").size)
        assertEquals(7, lines[1].split(",").size)
        assertEquals(7, lines[3].split(",").size)
    }

    @Test fun `incomplete model - a grid layout row without easting-northing and a geographic layout row with them`() {
        val s = CoordinateSystemSettings(HorizontalSystem.UTM)
        val good = converter()(point("P1"), s)
        val gridLayout = PointsCsvExporter.Layout.of(s)
        assertEquals("no grid coordinates for this point",
            PointsCsvExporter.unavailableReason(good.copy(easting = null), gridLayout))
        assertEquals(null, PointsCsvExporter.unavailableReason(good, gridLayout))
        val geoLayout = PointsCsvExporter.Layout.of(CoordinateSystemSettings())
        assertEquals("converted to grid coordinates, file is latitude/longitude",
            PointsCsvExporter.unavailableReason(good, geoLayout))
        assertEquals("converted in ftUS, file is m",
            PointsCsvExporter.unavailableReason(good.copy(unit = LinearUnit.US_SURVEY_FEET), gridLayout))
        // Written: same columns, blank position and elevation.
        val r = PointsCsvExporter.export(listOf(point("P1"), point("P2")), gridLayout, false) {
            if (it.name == "P2") good.copy(easting = null) else good
        }
        val lines = r.text.trim().lines()
        assertEquals("P2,,,,,unavailable: no grid coordinates for this point", lines[2])
        assertColumns(lines, 6)
    }

    @Test fun `normal exports are byte-identical to the legacy overload`() {
        val conv = converter()
        val pts = listOf(point("P1"), point("P2", 41.3477, -76.0226), point("W17", 40.0, -78.2))
        for (s in listOf(CoordinateSystemSettings(), CoordinateSystemSettings(HorizontalSystem.UTM),
                CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, unit = LinearUnit.US_SURVEY_FEET), spcs())) {
            for (ref in listOf(false, true)) {
                val r = PointsCsvExporter.export(pts, PointsCsvExporter.Layout.of(s), ref) { conv(it, s) }
                assertEquals("$s ref=$ref", PointsCsvExporter.build(pts, ref) { conv(it, s) }, r.text)
                assertEquals(0, r.unavailableCount)
            }
        }
    }

    @Test fun `empty export header follows the settings, with and without the position reference column`() {
        val s = CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, unit = LinearUnit.US_SURVEY_FEET,
            vertical = VerticalReference.NAVD88_GEOID18)
        val six = PointsCsvExporter.export(emptyList(), PointsCsvExporter.Layout.of(s), false) { error("no points") }
        assertEquals("Point,Northing (ftUS),Easting (ftUS),Elevation (ftUS H GEOID18),Description,System\n", six.text)
        val seven = PointsCsvExporter.export(emptyList(), PointsCsvExporter.Layout.of(s), true) { error("no points") }
        assertEquals("Point,Northing (ftUS),Easting (ftUS),Elevation (ftUS H GEOID18),Description,System,Position reference\n", seven.text)
        assertEquals(0, seven.rowCount)
    }
}
