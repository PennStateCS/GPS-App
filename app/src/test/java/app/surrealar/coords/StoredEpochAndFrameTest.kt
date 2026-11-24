package app.surrealar.coords

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.validCoordinateEpochOrNull
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.convert.GeoidLookup
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.pennstatecs.geokit.convert.CoordinateConverter as GeoConverter
import io.github.pennstatecs.geokit.convert.SourcePosition as GeoSourcePosition

/**
 * Stored coordinate epoch guard (review LOW-3) and points stored in ITRF2020, EPSG 9989/9990 (LOW-5):
 * converted from ITRF2020 through the verified registry, never read as WGS84.
 */
class StoredEpochAndFrameTest {

    private val ts = 1_763_900_000_123L
    private val legacy = Itrf2014ToNad83.decimalYear(ts)

    private fun point(crs: Int? = 4979, epoch: Double? = null) = Coordinate(
        id = "p", name = "p", latitude = 41.3477, longitude = -76.0226, altitude = 390.0, timestamp = ts,
        icon = "", color = 0, crsEpsg = crs, coordinateEpoch = epoch,
    )

    private val itrf2020Out = CoordinateSystemSettings(datum = OutputDatum.ITRF2020)

    @Test fun `only finite epochs within 1980 to 2100 are valid`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -2026.5, 1979.999, 2100.001, 1e300)) {
            assertNull("$bad", validCoordinateEpochOrNull(bad))
        }
        assertNull(validCoordinateEpochOrNull(null))
        for (ok in listOf(1980.0, 2026.886, 2100.0)) assertEquals(ok, validCoordinateEpochOrNull(ok)!!, 0.0)
    }

    @Test fun `an invalid stored epoch falls back to the capture time instead of throwing or shifting`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 3000.0)) {
            assertEquals("$bad", legacy, storedEpochYear(point(epoch = bad), itrf2020Out), 0.0)
        }
        assertEquals(2020.5, storedEpochYear(point(epoch = 2020.5), itrf2020Out), 0.0)
        // The converter path does not throw for a damaged epoch.
        val converter = CoordinateConverter({ _, _ -> null }) { null }
        val p = converter.convert(storedSourcePosition(point(epoch = Double.NaN), itrf2020Out), itrf2020Out)
        val good = converter.convert(storedSourcePosition(point(epoch = null), itrf2020Out), itrf2020Out)
        assertEquals(good, p)
        assertEquals(legacy, p.epochYear!!, 0.0)
    }

    @Test fun `stored datum mapping keeps every historical code`() {
        assertEquals(GeodeticDatum.WGS84_ITRF2014, StoredDatum.geodeticDatum(null))
        assertEquals(GeodeticDatum.WGS84_ITRF2014, StoredDatum.geodeticDatum(4326))
        assertEquals(GeodeticDatum.WGS84_ITRF2014, StoredDatum.geodeticDatum(4979))
        assertEquals(GeodeticDatum.WGS84_ITRF2014, StoredDatum.geodeticDatum(32618))
        assertEquals(GeodeticDatum.NAD83_2011, StoredDatum.geodeticDatum(6318))
        assertEquals(GeodeticDatum.NAD83_2011, StoredDatum.geodeticDatum(6319))
        assertEquals(GeodeticDatum.ITRF2020, StoredDatum.geodeticDatum(9989))
        assertEquals(GeodeticDatum.ITRF2020, StoredDatum.geodeticDatum(9990))
        assertEquals("ITRF2020", StoredDatum.label(9989))
        assertEquals("NAD83", StoredDatum.label(6319))
        assertEquals("WGS84", StoredDatum.label(4326))
        assertEquals("WGS84", StoredDatum.label(null))
    }

    @Test fun `source datum defaults from isNad83 and contradictions are rejected`() {
        assertEquals(GeodeticDatum.NAD83_2011, SourcePosition(1.0, 2.0, null, null, isNad83 = true, epochYear = 2020.0).datum)
        assertEquals(GeodeticDatum.WGS84_ITRF2014, SourcePosition(1.0, 2.0, null, null, isNad83 = false, epochYear = 2020.0).datum)
        try {
            SourcePosition(1.0, 2.0, null, null, isNad83 = true, epochYear = 2020.0, datum = GeodeticDatum.ITRF2020)
            fail("contradiction accepted")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `an ITRF2020 point converts from ITRF2020 at its coordinate epoch, not as WGS84`() {
        val c = point(crs = 9989, epoch = 2025.5)
        val geoid = Geoid18Grid.fromClasspath()
        val converter = CoordinateConverter({ lat, lon -> geoid.undulation(lat, lon) }) { null }
        val engine = GeoConverter(GeoidLookup.of(geoid))
        for (s in listOf(
            CoordinateSystemSettings(datum = OutputDatum.WGS84, unit = LinearUnit.METERS),
            CoordinateSystemSettings(datum = OutputDatum.NAD83_2011, unit = LinearUnit.METERS, vertical = VerticalReference.NAVD88_GEOID18),
            CoordinateSystemSettings(HorizontalSystem.UTM, datum = OutputDatum.ITRF2020, unit = LinearUnit.METERS),
        )) {
            val src = storedSourcePosition(c, s)
            assertEquals(GeodeticDatum.ITRF2020, src.datum)
            assertEquals("coordinate epoch used for an ITRF2020 source", 2025.5, src.epochYear, 0.0)
            val p = converter.convert(src, s)
            val expected = engine.convert(GeoSourcePosition(c.latitude, c.longitude, c.altitude, null,
                GeodeticDatum.ITRF2020, 2025.5), s.toOutputSystem())
            assertEquals(expected.latDeg, p.latDeg, 0.0)
            assertEquals(expected.lonDeg, p.lonDeg, 0.0)
            assertEquals(expected.heightM, p.height)
            assertEquals(expected.eastingM, p.easting)
        }
        // ITRF2020 out of an ITRF2020 point is the stored numbers (no shift); WGS84 out differs by the Helmert (mm).
        val same = converter.convert(storedSourcePosition(c, itrf2020Out), itrf2020Out)
        assertEquals(c.latitude, same.latDeg, 0.0)
        assertEquals(c.longitude, same.lonDeg, 0.0)
        val wgs = converter.convert(storedSourcePosition(c, CoordinateSystemSettings()), CoordinateSystemSettings())
        assertNotEquals(c.latitude, wgs.latDeg, 0.0)
        assertTrue("mm-level frame difference", kotlin.math.abs(wgs.latDeg - c.latitude) * 111_000 < 0.01)
    }

    @Test fun `WGS84 and NAD83 points are converted exactly as before`() {
        for (crs in listOf(null, 4326, 4979, 6318, 6319)) {
            for (s in listOf(CoordinateSystemSettings(), itrf2020Out, CoordinateSystemSettings(datum = OutputDatum.NAD83_2011))) {
                val c = point(crs = crs, epoch = 2024.0)
                val src = storedSourcePosition(c, s)
                val expectedEpoch = if (s.effectiveDatum == OutputDatum.ITRF2020) 2024.0 else legacy
                assertEquals(SourcePosition(c.latitude, c.longitude, c.altitude, null,
                    isNad83 = crs == 6318 || crs == 6319, epochYear = expectedEpoch), src)
            }
        }
    }

    @Test fun `Maps and AR plotting keeps ITRF2020 points as they are`() {
        val c = point(crs = 9989)
        assertSame(c, StoredDatum.toWgs84(c))
    }
}
