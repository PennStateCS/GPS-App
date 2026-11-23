package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.projection.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The conversion engine against external references:
 *  - PROJ 9.8.1 "ITRF2014 to NAD83(2011) (1)" at epoch 2026.886 (PA point);
 *  - NGS NCAT for SPCS83 Pennsylvania North and UTM 18N on NAD83(2011);
 *  - NGS geoid service (GEOID18) for N;
 *  - the PROJ SPCS83 (all 118 zones) and UTM vector files used by the projection tests.
 */
class CoordinateConverterTest {

    private val grid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }
    private val withGeoid by lazy { CoordinateConverter(grid) }
    private val noGeoid = CoordinateConverter(GeoidLookup.NONE)
    private val usft = 1200.0 / 3937.0

    private fun wgs(lat: Double, lon: Double, h: Double?, msl: Double? = null, epoch: Double = 2026.886) =
        SourcePosition(lat, lon, h, msl, GeodeticDatum.WGS84_ITRF2014, epoch)

    private fun nad(lat: Double, lon: Double, h: Double?, msl: Double? = null, epoch: Double = 2026.886) =
        SourcePosition(lat, lon, h, msl, GeodeticDatum.NAD83_2011, epoch)

    // ── Datum ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `WGS84 source to NAD83(2011) geographic matches PROJ`() {
        val out = noGeoid.convert(wgs(41.3477, -76.0226, 390.0),
            OutputSystem(datum = GeodeticDatum.NAD83_2011, vertical = VerticalReference.ELLIPSOIDAL))
        assertEquals(GeodeticDatum.NAD83_2011, out.datum)
        assertEquals(41.3476902755, out.latDeg, 2e-9)
        assertEquals(-76.0225917754, out.lonDeg, 2e-9)
        assertEquals(391.2061, out.heightM!!, 0.001)
        assertNull(out.eastingM); assertNull(out.zoneLabel)
    }

    @Test fun `a source already in the output datum is not shifted`() {
        val n = noGeoid.convert(nad(41.3477, -76.0226, 390.0), OutputSystem(datum = GeodeticDatum.NAD83_2011))
        assertEquals(41.3477, n.latDeg, 0.0); assertEquals(-76.0226, n.lonDeg, 0.0); assertEquals(390.0, n.heightM!!, 0.0)
        val w = noGeoid.convert(wgs(41.3477, -76.0226, 390.0), OutputSystem())
        assertEquals(41.3477, w.latDeg, 0.0); assertEquals(390.0, w.heightM!!, 0.0)
    }

    @Test fun `NAD83 source to WGS84 is the inverse Helmert at the epoch`() {
        val out = noGeoid.convert(nad(41.3477, -76.0226, 390.0, epoch = 2015.5), OutputSystem())
        val expected = Itrf2014ToNad83.inverse(GeodeticPosition(41.3477, -76.0226, 390.0), 2015.5)
        assertEquals(expected.latDeg, out.latDeg, 0.0)
        assertEquals(expected.lonDeg, out.lonDeg, 0.0)
        assertEquals(expected.heightM, out.heightM!!, 0.0)
    }

    @Test fun `the epoch is used`() {
        val sys = OutputSystem(datum = GeodeticDatum.NAD83_2011)
        val a = noGeoid.convert(wgs(41.3477, -76.0226, 390.0, epoch = 2010.0), sys)
        val b = noGeoid.convert(wgs(41.3477, -76.0226, 390.0, epoch = 2030.0), sys)
        assertNotEquals(a.latDeg, b.latDeg, 1e-9)
    }

    // ── Grid systems ─────────────────────────────────────────────────────────────────────────────

    @Test fun `NAD83 point in PA North matches NCAT, in metres and US survey feet`() {
        val m = noGeoid.convert(nad(41.3477, -76.0226, 390.0), OutputSystem(TargetSystem.StatePlane(6562)))
        assertEquals(744_552.966, m.eastingM!!, 0.001)
        assertEquals(132_599.685, m.northingM!!, 0.001)
        assertEquals("SPCS 3701", m.zoneLabel)
        assertEquals(6562, m.spcsZone!!.epsg)
        assertFalse(m.outsideZone)
        val ft = noGeoid.convert(nad(41.3477, -76.0226, 390.0),
            OutputSystem(TargetSystem.StatePlane(6562), unit = LengthUnit.US_SURVEY_FOOT))
        assertEquals(m.eastingM!! / usft, ft.easting!!, 0.0)
        assertEquals(m.northingM!! / usft, ft.northing!!, 0.0)
        assertEquals(390.0 / usft, ft.height!!, 0.0)
        assertEquals(m.eastingM!!, ft.eastingM!!, 0.0)   // metres stay metres
    }

    @Test fun `State Plane always uses NAD83(2011)`() {
        val sys = OutputSystem(TargetSystem.StatePlane(6562), datum = GeodeticDatum.WGS84_ITRF2014)
        assertEquals(GeodeticDatum.NAD83_2011, sys.effectiveDatum)
        val fromWgs = noGeoid.convert(wgs(41.3477, -76.0226, 390.0), sys)
        assertEquals(GeodeticDatum.NAD83_2011, fromWgs.datum)
        assertEquals(41.3476902755, fromWgs.latDeg, 2e-9)
    }

    @Test fun `a point outside the selected zone is flagged`() {
        val out = noGeoid.convert(nad(41.3477, -76.0226, 390.0), OutputSystem(TargetSystem.StatePlane(6423)))  // CA zone 5
        assertTrue(out.outsideZone)
        assertEquals("SPCS 0405", out.zoneLabel)
    }

    @Test fun `unknown State Plane zone is rejected`() {
        val e = runCatching { TargetSystem.StatePlane(99999) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun `UTM auto zone matches NCAT`() {
        val out = noGeoid.convert(nad(41.3477, -76.0226, 390.0),
            OutputSystem(TargetSystem.UtmAuto, datum = GeodeticDatum.NAD83_2011))
        assertEquals(414_450.726, out.eastingM!!, 0.002)
        assertEquals(4_577_860.600, out.northingM!!, 0.002)
        assertEquals(18, out.utmZone); assertEquals('N', out.utmHemisphere); assertEquals("UTM 18N", out.zoneLabel)
        assertFalse(out.outsideZone)
        assertNull(out.spcsZone)
    }

    @Test fun `southern hemisphere UTM label`() {
        val out = noGeoid.convert(wgs(-33.8688, 151.2093, 50.0), OutputSystem(TargetSystem.UtmAuto))
        assertEquals("UTM 56S", out.zoneLabel); assertEquals('S', out.utmHemisphere)
    }

    @Test fun `every SPCS83 zone matches the PROJ vectors through the engine`() {
        val rows = File("src/test/resources/coords/spcs83_vectors.csv").readLines().drop(1).filter { it.isNotBlank() }
        assertTrue(rows.size > 1000)
        for (line in rows) {
            val p = line.split(",")
            val epsg = p[0].toInt()
            val out = noGeoid.convert(nad(p[1].toDouble(), p[2].toDouble(), 0.0), OutputSystem(TargetSystem.StatePlane(epsg)))
            assertEquals("E $line", p[3].toDouble(), out.eastingM!!, 1e-6)
            assertEquals("N $line", p[4].toDouble(), out.northingM!!, 1e-6)
        }
    }

    @Test fun `UTM matches the PROJ vectors through the engine`() {
        val rows = File("src/test/resources/utm/utm_vectors.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("zone") }
        var checked = 0
        for (line in rows) {
            val p = line.split(",")
            val zone = p[0].toInt(); val lat = p[2].toDouble(); val lon = p[3].toDouble()
            if (Utm.zoneFor(lon) != zone || (lat >= 0) != (p[1] == "N")) continue   // edge rows forced into a neighbour zone
            val out = noGeoid.convert(wgs(lat, lon, 0.0), OutputSystem(TargetSystem.UtmAuto))
            assertEquals("E $line", p[4].toDouble(), out.eastingM!!, 1e-6)
            assertEquals("N $line", p[5].toDouble(), out.northingM!!, 1e-6)
            checked++
        }
        assertTrue("checked $checked", checked > 500)
    }

    @Test fun `latitude out of range throws for UTM`() {
        val e = runCatching { noGeoid.convert(wgs(91.0, 0.0, 0.0), OutputSystem(TargetSystem.UtmAuto)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    // ── Heights ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `GEOID18 orthometric height in PA matches the NGS geoid service`() {
        val out = withGeoid.convert(nad(41.3477, -76.0226, 390.0),
            OutputSystem(vertical = VerticalReference.GEOID18_ORTHOMETRIC))
        assertEquals(390.0 + 31.601, out.heightM!!, 0.01)
        assertEquals("NAVD88", out.verticalDatumName)
        assertNull(out.heightUnavailableReason)
    }

    @Test fun `GEOID18 heights name PRVD02 and VIVD09`() {
        val sys = OutputSystem(vertical = VerticalReference.GEOID18_ORTHOMETRIC)
        val pr = withGeoid.convert(nad(18.4655, -66.1057, 0.0), sys)
        assertEquals(43.121, pr.heightM!!, 0.005)
        assertEquals("PRVD02", pr.verticalDatumName)
        assertEquals("VIVD09", withGeoid.convert(nad(18.3419, -64.9307, 0.0), sys).verticalDatumName)
    }

    @Test fun `the geoid is looked up at the NAD83 position whatever the output datum`() {
        val seen = ArrayList<Pair<Double, Double>>()
        val conv = CoordinateConverter { lat, lon -> seen += lat to lon; -31.6 }
        val out = conv.convert(wgs(41.3477, -76.0226, 390.0),
            OutputSystem(datum = GeodeticDatum.WGS84_ITRF2014, vertical = VerticalReference.GEOID18_ORTHOMETRIC))
        assertEquals(1, seen.size)
        assertEquals(41.3476902755, seen[0].first, 2e-9)
        assertEquals(-76.0225917754, seen[0].second, 2e-9)
        assertEquals(391.2061 + 31.6, out.heightM!!, 0.001)    // NAD83 h − N
        assertEquals(41.3477, out.latDeg, 0.0)                    // lat/lon still WGS84
    }

    @Test fun `outside GEOID18 coverage there is no height and the reason says so`() {
        val out = withGeoid.convert(wgs(61.2, -149.9, 100.0), OutputSystem(vertical = VerticalReference.GEOID18_ORTHOMETRIC))
        assertNull(out.heightM); assertNull(out.height)
        assertEquals(HeightUnavailableReason.OUTSIDE_GEOID18_COVERAGE, out.heightUnavailableReason)
        assertEquals("outside GEOID18 coverage", out.heightUnavailableReason!!.description)
    }

    @Test fun `unknown ellipsoidal height is never replaced by zero`() {
        for (v in listOf(VerticalReference.ELLIPSOIDAL, VerticalReference.GEOID18_ORTHOMETRIC)) {
            val out = withGeoid.convert(wgs(41.3477, -76.0226, null, msl = 424.0), OutputSystem(vertical = v))
            assertNull(v.name, out.heightM)
            assertEquals(HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT, out.heightUnavailableReason)
        }
        // lat/lon are still converted.
        val n = noGeoid.convert(wgs(41.3477, -76.0226, null), OutputSystem(datum = GeodeticDatum.NAD83_2011))
        assertEquals(41.3476902755, n.latDeg, 2e-9)
    }

    @Test fun `zero is a real height`() {
        val e = noGeoid.convert(nad(41.3477, -76.0226, 0.0), OutputSystem(datum = GeodeticDatum.NAD83_2011))
        assertEquals(0.0, e.heightM!!, 0.0)
        val g = withGeoid.convert(nad(41.3477, -76.0226, 0.0), OutputSystem(vertical = VerticalReference.GEOID18_ORTHOMETRIC))
        assertEquals(31.601, g.heightM!!, 0.01)
        val m = noGeoid.convert(wgs(41.3477, -76.0226, null, msl = 0.0), OutputSystem(vertical = VerticalReference.RECEIVER_MSL))
        assertEquals(0.0, m.heightM!!, 0.0)
    }

    @Test fun `receiver MSL passes through, converted only to the unit`() {
        val out = noGeoid.convert(nad(41.3477, -76.0226, 390.0, msl = 424.1),
            OutputSystem(TargetSystem.StatePlane(6562), unit = LengthUnit.INTERNATIONAL_FOOT, vertical = VerticalReference.RECEIVER_MSL))
        assertEquals(424.1, out.heightM!!, 0.0)
        assertEquals(424.1 / 0.3048, out.height!!, 0.0)
        assertNull(out.verticalDatumName)
        val none = noGeoid.convert(wgs(41.3477, -76.0226, 390.0), OutputSystem(vertical = VerticalReference.RECEIVER_MSL))
        assertNull(none.heightM)
        assertEquals(HeightUnavailableReason.NO_RECEIVER_MSL, none.heightUnavailableReason)
    }

    @Test fun `negative heights are kept`() {
        val out = noGeoid.convert(nad(25.7617, -80.1918, -25.0), OutputSystem(datum = GeodeticDatum.NAD83_2011))
        assertEquals(-25.0, out.heightM!!, 0.0)
    }

    // ── Units, EPSG ──────────────────────────────────────────────────────────────────────────────

    @Test fun `length units`() {
        assertEquals(1200.0 / 3937.0, LengthUnit.US_SURVEY_FOOT.metresPerUnit, 0.0)
        assertEquals(0.3048, LengthUnit.INTERNATIONAL_FOOT.metresPerUnit, 0.0)
        assertEquals(3937.0 / 1200.0, LengthUnit.US_SURVEY_FOOT.fromMetres(1.0), 1e-15)
        assertEquals(1.0, LengthUnit.INTERNATIONAL_FOOT.toMetres(1.0 / 0.3048), 1e-15)
        assertEquals(12.5, LengthUnit.METRE.fromMetres(12.5), 0.0)
    }

    @Test fun `EPSG codes and datum lookup`() {
        assertEquals(GeodeticDatum.NAD83_2011, GeodeticDatum.fromEpsg(6319))
        assertEquals(GeodeticDatum.NAD83_2011, GeodeticDatum.fromEpsg(6318))
        assertEquals(GeodeticDatum.WGS84_ITRF2014, GeodeticDatum.fromEpsg(4979))
        assertEquals(GeodeticDatum.WGS84_ITRF2014, GeodeticDatum.fromEpsg(4326))
        assertNull(GeodeticDatum.fromEpsg(null)); assertNull(GeodeticDatum.fromEpsg(32618))
        assertTrue(Epsg.isNad83_2011(6319)); assertFalse(Epsg.isNad83_2011(4979)); assertFalse(Epsg.isNad83_2011(null))
        assertTrue(Epsg.is2d(4326)); assertTrue(Epsg.is2d(6318)); assertFalse(Epsg.is2d(4979))
        assertEquals(4979, GeodeticDatum.WGS84_ITRF2014.epsg3d); assertEquals(6318, GeodeticDatum.NAD83_2011.epsg2d)
    }

    @Test fun `result reports what was used`() {
        val sys = OutputSystem(TargetSystem.UtmAuto, GeodeticDatum.NAD83_2011, LengthUnit.US_SURVEY_FOOT, VerticalReference.ELLIPSOIDAL)
        val out = noGeoid.convert(wgs(41.3477, -76.0226, 390.0), sys)
        assertSame(sys, out.system)
        assertEquals(LengthUnit.US_SURVEY_FOOT, out.unit)
        assertEquals(VerticalReference.ELLIPSOIDAL, out.vertical)
        assertEquals(GeodeticDatum.NAD83_2011, out.datum)
    }
}
