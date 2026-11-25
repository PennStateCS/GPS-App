package app.surrealar.coords

import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid2022Model
import io.github.pennstatecs.geokit.geoid.Geoid2022VelocityResult
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 3 GEOID2022 (beta) heights in the app adapter. Expected values come from geokit directly — the
 * verified frame registry (→ ITRF2020 at the coordinate epoch) and [Geoid2022Model.undulationAt] — so the
 * adapter is checked to add nothing of its own. Preview OFF must be the pre-Phase-3 behaviour exactly.
 */
class Geoid2022HeightTest {

    private companion object {
        val model: Geoid2022Model by lazy { Geoid2022Model.bundled() }
    }

    private var geoid18Calls = 0
    private val askedGlobal = mutableListOf<GeoidModelId>()
    private var geoid2022Resolved = 0
    private val converter = CoordinateConverter(
        { _, _ -> geoid18Calls++; 33.0 },
        { id -> askedGlobal += id; null },
        { geoid2022Resolved++; Geoid2022TimeDependent(model) },
        FrameTransformationRegistry.DEFAULT,
    )

    /** 2026-11-20T12:00Z: a known calendar day for N(t). */
    private val millis = LocalDate.of(2026, 11, 20).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val epoch = Itrf2014ToNad83.decimalYear(millis)

    private val on = CoordinateSystemSettings(vertical = VerticalReference.GEOID2022, nsrs2022Preview = true)

    private val samples = listOf(
        doubleArrayOf(41.3477, -76.0226, 390.0),     // PA
        doubleArrayOf(34.0522, -118.2437, 60.0),     // CA
        doubleArrayOf(61.2181, -149.9003, 30.0),     // AK
        doubleArrayOf(21.3069, -157.8583, 20.0),     // HI
        doubleArrayOf(18.4655, -66.1057, -30.0),     // PR
        doubleArrayOf(13.4443, 144.7937, 80.0),      // Guam
        doubleArrayOf(-14.2756, -170.7020, 10.0),    // American Samoa
    )

    private fun src(lat: Double, lon: Double, h: Double?, datum: GeodeticDatum = GeodeticDatum.WGS84_ITRF2014, e: Double = epoch) =
        SourcePosition(lat, lon, h, 12.5, isNad83 = datum == GeodeticDatum.NAD83_2011, epochYear = e, datum = datum)

    /** h(ITRF2020) − N(GEOID2022, day) straight from geokit. */
    private fun expected(lat: Double, lon: Double, h: Double, datum: GeodeticDatum, e: Double, day: LocalDate): Double {
        val t = FrameTransformationRegistry.DEFAULT.find(datum.frame, ReferenceFrame.ITRF2020, CoordinateEpoch(e))
            as FrameTransformationLookup.Available
        val p = t.apply(GeodeticPosition(lat, lon, h))
        val n = model.undulationAt(p.latDeg, p.lonDeg, day.year, day.monthValue, day.dayOfMonth) as GeoidResult.Value
        assertEquals(GeoidModelId.GEOID2022, n.model)
        return p.heightM - n.undulationM
    }

    @Test fun `preview ON - GEOID2022 height is h(ITRF2020) minus N(GEOID2022) at the coordinate epoch`() {
        for ((lat, lon, h) in samples.map { Triple(it[0], it[1], it[2]) }) {
            val p = converter.convert(src(lat, lon, h), on)
            assertNull("$lat,$lon ${p.heightUnavailableReason}", p.heightUnavailableReason)
            assertEquals("$lat,$lon", expected(lat, lon, h, GeodeticDatum.WGS84_ITRF2014, epoch, LocalDate.of(2026, 11, 20)), p.height!!, 0.0)
            assertEquals(VerticalReference.GEOID2022, p.vertical)
            assertEquals("NAPGD2022 beta", p.verticalLabel)
            assertEquals("GEOID2022 (beta beta_v0a) epoch 2026.886", p.heightModelNote)
            // The horizontal output is the same as for any other height choice.
            val plain = converter.convert(src(lat, lon, h), CoordinateSystemSettings(vertical = VerticalReference.ELLIPSOIDAL))
            assertEquals(plain.latDeg, p.latDeg, 0.0)
            assertEquals(plain.lonDeg, p.lonDeg, 0.0)
        }
        // Never another model: no EGM, no GEOID18.
        assertTrue(askedGlobal.isEmpty())
        assertEquals(0, geoid18Calls)
    }

    @Test fun `NAD83(2011) and ITRF2020 sources go through the registry (ITRF2020 as is)`() {
        val (lat, lon, h) = Triple(40.79, -77.86, 350.0)
        val nad = converter.convert(src(lat, lon, h, GeodeticDatum.NAD83_2011), on)
        assertEquals(expected(lat, lon, h, GeodeticDatum.NAD83_2011, epoch, LocalDate.of(2026, 11, 20)), nad.height!!, 0.0)
        val itrf = converter.convert(src(lat, lon, h, GeodeticDatum.ITRF2020), on)
        val n = model.undulationAt(lat, lon, 2026, 11, 20) as GeoidResult.Value
        assertEquals(h - n.undulationM, itrf.height!!, 0.0)
        // NAD83(2011) and ITRF2020 differ by about a metre horizontally and in h: the frame step is real.
        assertTrue(kotlin.math.abs(nad.height!! - itrf.height!!) > 0.1)
    }

    @Test fun `N is time dependent - static SGEOID at 2020-0, plus DGEOID times the elapsed time`() {
        val (lat, lon, h) = Triple(61.2181, -149.9003, 30.0)   // Alaska: a large geoid rate
        val static = (model.undulation(lat, lon) as GeoidResult.Value).undulationM
        val rate = (model.velocity(lat, lon) as Geoid2022VelocityResult.Value).metresPerYear
        val at2020 = converter.convert(src(lat, lon, h, GeodeticDatum.ITRF2020, 2020.0), on)
        assertEquals(h - static, at2020.height!!, 0.0)
        assertEquals("GEOID2022 (beta beta_v0a) epoch 2020.000", at2020.heightModelNote)
        val at2030 = converter.convert(src(lat, lon, h, GeodeticDatum.ITRF2020, 2030.0), on)
        val days = Geoid2022Model.modifiedJulianDay(2030, 1, 1) - Geoid2022Model.REFERENCE_MJD
        assertEquals(-rate * days / 365.25, at2030.height!! - at2020.height!!, 1e-12)
        assertNotEquals(at2020.height!!, at2030.height!!, 0.0)
    }

    @Test fun `outside the bundled GEOID2022 data the height is unavailable with a reason, never another model`() {
        val mexico = converter.convert(src(19.4326, -99.1332, 2240.0), on)
        assertNull(mexico.height)
        assertEquals(AppGeoidModels.unavailableText(GeoidModelId.GEOID2022, GeoidUnavailableReason.TILE_NOT_AVAILABLE), mexico.heightUnavailableReason)
        assertTrue(mexico.heightUnavailableReason!!.contains("GEOID2022 (beta)"))
        val paris = converter.convert(src(48.8566, 2.3522, 100.0), on)
        assertNull(paris.height)
        assertEquals("outside GEOID2022 coverage", paris.heightUnavailableReason)
        assertEquals("NAPGD2022 beta", paris.verticalLabel)
        val noH = converter.convert(src(41.3477, -76.0226, null), on)
        assertNull(noH.height)
        assertTrue(noH.heightUnavailableReason!!.isNotEmpty())
        assertTrue(askedGlobal.isEmpty())
        assertEquals(0, geoid18Calls)
    }

    @Test fun `preview OFF - a saved GEOID2022 choice shows the default height and never touches GEOID2022`() {
        val off = on.copy(nsrs2022Preview = false)
        assertEquals(VerticalReference.RECEIVER_MSL, off.effectiveVertical)
        assertTrue(off.betaVerticalSuspended)
        for (h in HorizontalSystem.entries) for (d in OutputDatum.entries) for (u in LinearUnit.entries) {
            val s = off.copy(horizontal = h, datum = d, unit = u)
            for ((lat, lon, hh) in samples.take(2).map { Triple(it[0], it[1], it[2]) }) {
                val p = converter.convert(src(lat, lon, hh), s)
                assertEquals(converter.convert(src(lat, lon, hh), s.copy(vertical = VerticalReference.RECEIVER_MSL)), p)
                assertEquals(VerticalReference.RECEIVER_MSL, p.vertical)
                assertNull(p.heightModelNote)
            }
        }
        assertEquals("GEOID2022 never resolved while the preview is off", 0, geoid2022Resolved)
    }

    @Test fun `preview ON changes nothing for the existing height choices`() {
        for (v in VerticalReference.entries.filter { !it.isBeta }) for (h in HorizontalSystem.entries.filter { !it.isBeta }) for (d in OutputDatum.entries.filter { !it.isBeta }) {   // existing (non-beta) choices; Phase 4 WP X beta ones: Nsrs2022OutputTest
            val s = CoordinateSystemSettings(horizontal = h, datum = d, vertical = v)
            for ((lat, lon, hh) in samples.take(2).map { Triple(it[0], it[1], it[2]) }) {
                assertEquals("$v $h $d", converter.convert(src(lat, lon, hh), s), converter.convert(src(lat, lon, hh), s.copy(nsrs2022Preview = true)))
            }
        }
        assertEquals(0, geoid2022Resolved)
    }

    @Test fun `main thread never loads GEOID2022 - data loading until the background load finished`() {
        var queued = 0
        val fresh = Geoid2022Model.bundled()
        val nb = NonBlockingGeoidModel(fresh, isMainThread = { true }, loadInBackground = { queued++ })
        val lookup = Geoid2022TimeDependent(fresh) { nb.guarded(it) }
        val r = lookup.undulationAt(41.3477, -76.0226, epoch)
        assertEquals(GeoidUnavailableReason.DATA_LOADING, (r as GeoidResult.Unavailable).reason)
        assertEquals(GeoidModelId.GEOID2022, r.model)
        assertEquals(1, queued)
        val c = CoordinateConverter({ _, _ -> null }, { null }, { lookup }, FrameTransformationRegistry.DEFAULT)
        val p = c.convert(src(41.3477, -76.0226, 390.0), on)
        assertNull(p.height)
        assertEquals("GEOID2022 data is loading", p.heightUnavailableReason)
    }

    @Test fun `coordinate epoch to UTC day is the inverse of the app's decimal year`() {
        assertEquals(LocalDate.of(2020, 1, 1), Geoid2022Epoch.utcDate(2020.0))
        var d = LocalDate.of(2024, 1, 1)   // leap year
        while (d.year < 2027) {
            for (hour in listOf(0, 12, 23)) {
                val ms = d.atTime(hour, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
                assertEquals("$d $hour", d, Geoid2022Epoch.utcDate(Itrf2014ToNad83.decimalYear(ms)))
            }
            d = d.plusDays(1)
        }
    }

    @Test fun `stored points use their coordinate epoch for GEOID2022, the capture time otherwise`() {
        val ts = 1_763_900_000_123L
        val c = Coordinate(id = "p", name = "p", latitude = 41.3477, longitude = -76.0226, altitude = 390.0, timestamp = ts,
            icon = "", color = 0, crsEpsg = 4979, coordinateEpoch = 2025.5)
        assertEquals(2025.5, storedEpochYear(c, on), 0.0)
        assertEquals(Itrf2014ToNad83.decimalYear(ts), storedEpochYear(c, on.copy(nsrs2022Preview = false)), 0.0)
        assertEquals(Itrf2014ToNad83.decimalYear(ts), storedEpochYear(c, CoordinateSystemSettings()), 0.0)
        val p = converter.convert(storedSourcePosition(c, on), on)
        assertEquals("GEOID2022 (beta beta_v0a) epoch 2025.500", p.heightModelNote)
        assertEquals(expected(41.3477, -76.0226, 390.0, GeodeticDatum.WGS84_ITRF2014, 2025.5, Geoid2022Epoch.utcDate(2025.5)), p.height!!, 0.0)
    }

    @Test fun `system summary carries the beta label, dataset version and epoch`() {
        val p = converter.convert(src(41.3477, -76.0226, 390.0), on)
        assertEquals("WGS84 (ITRF2014) · m · NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch 2026.886", CoordinateFormat.systemSummary(p))
        assertTrue(CoordinateFormat.height(p).endsWith(" m NAPGD2022 beta"))
    }
}
