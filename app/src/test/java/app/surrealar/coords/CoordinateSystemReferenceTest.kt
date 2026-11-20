package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Checks the conversions against independent authorities (values captured 2026-11-19):
 *  - NGS NCAT (geodesy.noaa.gov/api/ncat) for SPCS83 PA North/South and UTM 18N on NAD83(2011);
 *  - PROJ 9.8.1 (pyproj 3.8.0) for "ITRF2014 to NAD83(2011) (1)" at epoch 2026.886;
 *  - NGS geoid service (geodesy.noaa.gov/api/geoid/ght, model GEOID18) for N.
 */
class CoordinateSystemReferenceTest {

    private val usft = 1200.0 / 3937.0

    // ── State Plane / UTM on NAD83(2011) (NCAT, confirmed by PROJ) ───────────────────────────────

    @Test fun `PA North matches NCAT`() {
        val (e, n) = LambertConformalConic.PA_NORTH.forward(41.3477, -76.0226)
        assertEquals(744_552.966, e, 0.001)
        assertEquals(132_599.685, n, 0.001)
        assertEquals(2_442_754.191, e / usft, 0.002)    // NCAT ftUS
        assertEquals(435_037.467, n / usft, 0.002)
        assertEquals(2_442_759.076, e / 0.3048, 0.002)  // NCAT international ft
    }

    @Test fun `PA South matches NCAT`() {
        val (e, n) = LambertConformalConic.PA_SOUTH.forward(39.9526, -75.1652)
        assertEquals(820_846.396, e, 0.001)
        assertEquals(71_992.354, n, 0.001)
    }

    @Test fun `LCC inverse round-trips to sub-millimetre`() {
        val (e, n) = LambertConformalConic.PA_NORTH.forward(41.3477, -76.0226)
        val (lat, lon) = LambertConformalConic.PA_NORTH.inverse(e, n)
        assertEquals(41.3477, lat, 1e-9)
        assertEquals(-76.0226, lon, 1e-9)
    }

    @Test fun `UTM 18N matches NCAT`() {
        val u = app.surrealar.util.UtmConverter.latLonToUtm(41.3477, -76.0226)
        assertEquals(18, u.zone)
        assertEquals(414_450.726, u.easting, 0.002)
        assertEquals(4_577_860.600, u.northing, 0.002)
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
        val xyz = Grs80.toEcef(GeodeticPosition(41.3477, -76.0226, 390.0))
        assertEquals(1_158_291.825, xyz[0], 0.001)
        assertEquals(-4_653_473.583, xyz[1], 0.001)
        assertEquals(4_191_746.616, xyz[2], 0.001)
    }

    @Test fun `decimal year`() {
        assertEquals(2026.0, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()), 1e-9)
        assertEquals(2026.886, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-11-20T04:00:00Z").toEpochMilli()), 0.001)
    }

    // ── GEOID18 (bundled PA subgrid vs NGS service) ─────────────────────────────────────────────

    private val geoid: Geoid18Grid by lazy {
        Geoid18Grid(File("src/main/assets/${Geoid18Grid.ASSET_PATH}").readBytes())
    }

    @Test fun `GEOID18 subgrid matches the NGS geoid service`() {
        assertEquals(-31.601, geoid.undulation(41.3477, -76.0226)!!, 0.01)
        assertEquals(-33.298, geoid.undulation(40.7934, -77.8600)!!, 0.01)
        assertEquals(-32.957, geoid.undulation(39.9526, -75.1652)!!, 0.01)
        assertEquals(-34.197, geoid.undulation(41.9000, -80.1000)!!, 0.01)
    }

    @Test fun `outside the bundled grid there is no geoid value`() {
        assertNull(geoid.undulation(35.0, -80.0))
        assertNull(geoid.undulation(41.0, -90.0))
    }

    // ── End to end ──────────────────────────────────────────────────────────────────────────────

    @Test fun `WGS84 receiver point in PA North US feet with NAVD88`() {
        val conv = CoordinateConverter { lat, lon -> geoid.undulation(lat, lon) }
        val src = SourcePosition(41.3477, -76.0226, 390.0, receiverMslM = 424.0, isNad83 = false, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(
            horizontal = HorizontalSystem.SPCS_PA_NORTH, unit = LinearUnit.US_SURVEY_FEET,
            vertical = VerticalReference.NAVD88_GEOID18))
        assertEquals(OutputDatum.NAD83_2011, out.datum)                 // State Plane forces NAD83
        // Expected: project PROJ's NAD83 position with the NCAT-verified zone.
        val (e, n) = LambertConformalConic.PA_NORTH.forward(41.3476902755, -76.0225917754)
        assertEquals(e / usft, out.easting!!, 0.005)
        assertEquals(n / usft, out.northing!!, 0.005)
        // NAVD88 = NAD83 h − N  ≈ 391.206 − (−31.60) ≈ 422.81 m.
        assertEquals((391.2061 + 31.601) / usft, out.height!!, 0.05)
    }

    @Test fun `a NAD83 receiver point is not shifted again`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(41.3477, -76.0226, 390.0, null, isNad83 = true, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(horizontal = HorizontalSystem.SPCS_PA_NORTH))
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
        val src = SourcePosition(35.0, -80.0, 100.0, null, isNad83 = false, epochYear = 2026.886)
        val out = conv.convert(src, CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18))
        assertNull(out.height)
        assertEquals("outside GEOID18 coverage", out.heightUnavailableReason)
    }
}
