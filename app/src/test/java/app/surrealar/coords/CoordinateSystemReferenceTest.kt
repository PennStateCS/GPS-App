package app.surrealar.coords

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
 * End-to-end conversions through the app's settings against independent authorities (values captured
 * 2026-11-19): NGS NCAT for SPCS83 PA North on NAD83(2011), PROJ 9.8.1 for "ITRF2014 to NAD83(2011) (1)"
 * at epoch 2026.886, and the NGS geoid service (GEOID18) for N.
 */
class CoordinateSystemReferenceTest {

    private val usft = 1200.0 / 3937.0

    // The pure geokit checks that used to live here (UTM 18N vs NCAT, ITRF2014 → NAD83(2011) vs PROJ,
    // round trip, GRS80 XYZ vs NCAT, decimal year, GEOID18 coverage and datum names) moved to
    // geokit-core's convert/ReferencePointsTest and Geoid18GridTest; the conversion engine itself is
    // tested in convert/CoordinateConverterTest. What stays here goes through the app's settings types.

    private val geoid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }

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
