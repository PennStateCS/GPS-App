package app.surrealar.coords

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.projection.SpcsZones

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks the conversions against independent authorities (values captured 2026-11-19):
 *  - NGS NCAT (geodesy.noaa.gov/api/ncat) for SPCS83 PA North/South and UTM 18N on NAD83(2011);
 *  - PROJ 9.8.1 (pyproj 3.8.0) for "ITRF2014 to NAD83(2011) (1)" at epoch 2026.886;
 *  - NGS geoid service (geodesy.noaa.gov/api/geoid/ght, model GEOID18) for N (the GEOID18 grid
 *    checks themselves live in geokit-core's Geoid18GridTest).
 */
class CoordinateSystemReferenceTest {

    private val usft = 1200.0 / 3937.0

    // ── State Plane / UTM on NAD83(2011) (NCAT, confirmed by PROJ) ───────────────────────────────

    // PA North/South LCC and the LCC inverse moved to geokit-core's LambertConformalConicTest
    // (LambertConformalConic is internal to the module).

    @Test fun `UTM 18N matches NCAT`() {
        val u = io.github.pennstatecs.geokit.projection.Utm.forward(41.3477, -76.0226)
        assertEquals(18, u.zone)
        assertEquals(414_450.726, u.eastingM, 0.002)
        assertEquals(4_577_860.600, u.northingM, 0.002)
    }

    // ── Datum transformation (PROJ) ─────────────────────────────────────────────────────────────

    @Test fun `ITRF2014 to NAD83(2011) matches PROJ at epoch 2026_886`() {
        val out = Itrf2014ToNad83.forward(GeodeticPosition(41.3477, -76.0226, 390.0), 2026.886)
        // 1e-8 degrees ≈ 1.1 mm.
        assertEquals(41.3476902755, out.latDeg, 2e-9)
        assertEquals(-76.0225917754, out.lonDeg, 2e-9)
        assertEquals(391.2061, out.heightM, 0.001)
    }

    @Test fun `inverse transformation round-trips to sub-millimetre`() {
        val src = GeodeticPosition(41.3477, -76.0226, 390.0)
        val back = Itrf2014ToNad83.inverse(Itrf2014ToNad83.forward(src, 2026.886), 2026.886)
        assertEquals(src.latDeg, back.latDeg, 1e-10)
        assertEquals(src.lonDeg, back.lonDeg, 1e-10)
        assertEquals(src.heightM, back.heightM, 0.0005)
    }

    @Test fun `GRS80 geocentric matches NCAT XYZ`() {
        val xyz = Ellipsoid.GRS80.toEcef(GeodeticPosition(41.3477, -76.0226, 390.0))
        assertEquals(1_158_291.825, xyz.xM, 0.001)
        assertEquals(-4_653_473.583, xyz.yM, 0.001)
        assertEquals(4_191_746.616, xyz.zM, 0.001)
    }

    @Test fun `decimal year`() {
        assertEquals(2026.0, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()), 1e-9)
        assertEquals(2026.886, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-11-20T04:00:00Z").toEpochMilli()), 0.001)
    }

    // ── GEOID18 (bundled CONUS + PR/VI tiles) ────────────────────────────────────────────────────

    // The grid ships in geokit-core and is loaded the way the app loads it. The NGS geoid-service
    // points and the 600 raw-grid vectors are checked in geokit-core's Geoid18GridTest.
    private val geoid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }

    @Test fun `outside GEOID18 coverage there is no geoid value`() {
        assertNull(geoid.undulation(61.2, -149.9))   // Anchorage (GEOID12B territory)
        assertNull(geoid.undulation(21.3, -157.8))   // Honolulu
        assertNull(geoid.undulation(51.5, -0.1))     // London
    }

    @Test fun `GEOID18 names the vertical datum by region`() {
        assertEquals("NAVD88", Geoid18Grid.verticalDatumName(41.3, -76.0))
        assertEquals("PRVD02", Geoid18Grid.verticalDatumName(18.4655, -66.1057))
        assertEquals("VIVD09", Geoid18Grid.verticalDatumName(18.3419, -64.9307))
    }

    // ── End to end ──────────────────────────────────────────────────────────────────────────────

    @Test fun `WGS84 receiver point in PA North US feet with NAVD88`() {
        val conv = CoordinateConverter { lat, lon -> geoid.undulation(lat, lon) }
        val src = SourcePosition(41.3477, -76.0226, 390.0, receiverMslM = 424.0, isNad83 = false, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(
            horizontal = HorizontalSystem.STATE_PLANE, unit = LinearUnit.US_SURVEY_FEET,
            vertical = VerticalReference.NAVD88_GEOID18))
        assertEquals(OutputDatum.NAD83_2011, out.datum)                 // State Plane forces NAD83
        // Expected: project PROJ's NAD83 position with the NCAT-verified zone.
        val (e, n) = SpcsZones.byEpsg(6562)!!.projection.forward(41.3476902755, -76.0225917754)   // PA North
        assertEquals(e / usft, out.easting!!, 0.005)
        assertEquals(n / usft, out.northing!!, 0.005)
        // NAVD88 = NAD83 h − N  ≈ 391.206 − (−31.60) ≈ 422.81 m.
        assertEquals((391.2061 + 31.601) / usft, out.height!!, 0.05)
    }

    @Test fun `a NAD83 receiver point is not shifted again`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(41.3477, -76.0226, 390.0, null, isNad83 = true, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(horizontal = HorizontalSystem.STATE_PLANE))
        assertEquals(744_552.966, out.easting!!, 0.001)
        assertEquals(132_599.685, out.northing!!, 0.001)
    }

    @Test fun `defaults reproduce the old display - WGS84 lat lon, metres, receiver MSL`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(41.3477, -76.0226, 390.0, receiverMslM = 424.1, isNad83 = false, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings())
        assertEquals(41.3477, out.latDeg, 0.0)
        assertNull(out.easting)
        assertEquals(424.1, out.height!!, 0.0)
        assertEquals("41.34770000, -76.02260000", CoordinateFormat.horizontal(out))
    }

    @Test fun `NAVD88 outside coverage says why`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(61.2, -149.9, 100.0, null, isNad83 = false, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18))
        assertNull(out.height)
        assertEquals("outside GEOID18 coverage", out.heightUnavailableReason)
    }
}
