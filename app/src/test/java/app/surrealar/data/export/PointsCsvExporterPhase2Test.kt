package app.surrealar.data.export

import app.surrealar.coords.CoordinateConverter
import app.surrealar.coords.SourcePosition
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV labels for the Phase 2 options: the geoid model in the Elevation header, the frame in System. */
class PointsCsvExporterPhase2Test {

    private companion object {
        val egm2008 by lazy { GlobalGeoidModels.egm2008() }
        val egm96 by lazy { GlobalGeoidModels.egm96() }
    }

    private fun point(name: String, lat: Double = 41.3477, lon: Double = -76.0226) = Coordinate(
        id = name, name = name, latitude = lat, longitude = lon, altitude = 390.0,
        timestamp = 1_795_000_000_000L, icon = "", color = 0, crsEpsg = 4979,
    )

    private val converter = CoordinateConverter({ _, _ -> null }) { id ->
        when (id) { GeoidModelId.EGM2008 -> egm2008; GeoidModelId.EGM96 -> egm96; else -> null }
    }

    private fun convert(s: CoordinateSystemSettings) = { c: Coordinate ->
        converter.convert(SourcePosition(c.latitude, c.longitude, c.altitude, null, isNad83 = false, epochYear = 2026.886), s)
    }

    @Test fun `EGM2008 header and rows name the model, outside the bundled tiles the elevation is blank`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.EGM2008)
        val lines = PointsCsvExporter.build(listOf(point("PA1"), point("NO1", 60.5, 4.5)), convert(s)).trim().lines()
        assertEquals("Point,Latitude,Longitude,Elevation (m H EGM2008),Description,System", lines[0])
        assertEquals("WGS84 (ITRF2014) · m · EGM2008", lines[1].substringAfterLast(','))
        val pa = lines[1].split(",")
        assertTrue(pa[3].isNotEmpty())
        val no = lines[2].split(",")
        assertEquals("no EGM96 substitute", "", no[3])
        assertTrue(lines[2].endsWith("EGM2008"))
        lines.forEach { assertEquals(it, 6, it.split(",").size) }
    }

    @Test fun `EGM96 header and ITRF2020 frame with epoch in the System column`() {
        val s = CoordinateSystemSettings(datum = OutputDatum.ITRF2020, vertical = VerticalReference.EGM96)
        val lines = PointsCsvExporter.build(listOf(point("P1")), convert(s)).trim().lines()
        assertEquals("Point,Latitude,Longitude,Elevation (m H EGM96),Description,System", lines[0])
        assertEquals("ITRF2020 epoch 2026.886 · m · EGM96", lines[1].substringAfterLast(','))
        assertEquals(6, lines[1].split(",").size)
    }

    @Test fun `UTM on ITRF2020 keeps the PNEZD layout`() {
        val s = CoordinateSystemSettings(HorizontalSystem.UTM, datum = OutputDatum.ITRF2020, vertical = VerticalReference.ELLIPSOIDAL)
        val lines = PointsCsvExporter.build(listOf(point("P1")), convert(s)).trim().lines()
        assertEquals("Point,Northing (m),Easting (m),Elevation (m h),Description,System", lines[0])
        assertEquals("ITRF2020 epoch 2026.886 · UTM 18N · m · h", lines[1].substringAfterLast(','))
    }

    @Test fun `GEOID2022 beta - header and System column name beta, dataset version and epoch, columns unchanged`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.GEOID2022, nsrs2022Preview = true)
        val c = CoordinateConverter({ _, _ -> null }, { null },
            { app.surrealar.coords.Geoid2022TimeDependent(io.github.pennstatecs.geokit.geoid.Geoid2022Model.bundled()) },
            io.github.pennstatecs.geokit.datum.FrameTransformationRegistry.DEFAULT)
        val conv = { p: Coordinate ->
            c.convert(SourcePosition(p.latitude, p.longitude, p.altitude, null, isNad83 = false, epochYear = 2026.886), s)
        }
        val lines = PointsCsvExporter.build(listOf(point("PA1"), point("MX1", 19.4326, -99.1332)), conv).trim().lines()
        assertEquals("Point,Latitude,Longitude,Elevation (m H GEOID2022 beta),Description,System", lines[0])
        assertEquals("WGS84 (ITRF2014) · m · NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch 2026.886", lines[1].substringAfterLast(','))
        assertTrue(lines[1].split(",")[3].isNotEmpty())
        assertEquals("outside the bundled GEOID2022 data: blank, never another model", "", lines[2].split(",")[3])
        lines.forEach { assertEquals(it, 6, it.split(",").size) }
        // Preview off with the same saved choice: the pre-Phase-3 default export (receiver H), no beta label.
        val off = PointsCsvExporter.build(listOf(point("PA1")), convert(s.copy(nsrs2022Preview = false))).trim().lines()
        assertEquals(PointsCsvExporter.build(listOf(point("PA1")), convert(CoordinateSystemSettings())).trim().lines(), off)
        assertTrue(off.none { it.contains("GEOID2022") || it.contains("NAPGD2022") })
    }
}
