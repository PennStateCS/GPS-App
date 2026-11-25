package app.surrealar.coords

import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.geoid.GeoidModel
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import io.github.pennstatecs.geokit.projection.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 options of [CoordinateConverter]: H (EGM2008) / H (EGM96, fallback) and the ITRF2020 output
 * frame. Expected values come from geokit directly (the models and the frame registry), so the adapter is
 * checked to add nothing of its own.
 */
class CoordinateConverterGlobalGeoidTest {

    private companion object {
        val egm2008: GeoidModel by lazy { GlobalGeoidModels.egm2008() }
        val egm96: GeoidModel by lazy { GlobalGeoidModels.egm96() }
        val geoid18: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }
    }

    /** Records which global models the converter asked for. */
    private val asked = mutableListOf<GeoidModelId>()
    private val converter = CoordinateConverter({ lat, lon -> geoid18.undulation(lat, lon) }) { id ->
        asked += id
        when (id) { GeoidModelId.EGM2008 -> egm2008; GeoidModelId.EGM96 -> egm96; GeoidModelId.GEOID18, GeoidModelId.GEOID2022 -> null }
    }

    private val samples = listOf(
        doubleArrayOf(41.3477, -76.0226, 390.0),    // PA
        doubleArrayOf(34.0522, -118.2437, 60.0),    // CA
        doubleArrayOf(61.2181, -149.9003, 30.0),    // AK
        doubleArrayOf(21.3069, -157.8583, 20.0),    // HI
        doubleArrayOf(18.4655, -66.1057, -30.0),    // PR
    )

    private fun wgs(lat: Double, lon: Double, h: Double?, msl: Double? = null, epoch: Double = 2026.886) =
        SourcePosition(lat, lon, h, msl, isNad83 = false, epochYear = epoch)

    private fun n(model: GeoidModel, lat: Double, lon: Double): Double =
        (model.undulation(lat, lon) as GeoidResult.Value).undulationM

    @Test fun `EGM2008 height is h minus N from the geokit model at the WGS84 position`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.EGM2008, unit = LinearUnit.METERS)
        for ((lat, lon, h) in samples.map { Triple(it[0], it[1], it[2]) }) {
            val p = converter.convert(wgs(lat, lon, h), s)
            assertEquals("$lat,$lon", h - n(egm2008, lat, lon), p.height!!, 0.0)
            assertEquals("EGM2008", p.verticalLabel)
            assertNull(p.heightUnavailableReason)
        }
        assertEquals(setOf(GeoidModelId.EGM2008), asked.toSet())
    }

    @Test fun `EGM96 height is h minus N from the geokit EGM96 model, labelled EGM96`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.EGM96)
        for ((lat, lon, h) in samples.map { Triple(it[0], it[1], it[2]) } + Triple(60.5, 4.5, 100.0)) {
            val p = converter.convert(wgs(lat, lon, h), s)
            assertEquals("$lat,$lon", h - n(egm96, lat, lon), p.height!!, 0.0)
            assertEquals("EGM96", p.verticalLabel)
        }
        assertEquals(setOf(GeoidModelId.EGM96), asked.toSet())
    }

    @Test fun `a NAD83(2011) point is looked up at its WGS84 (ITRF2014) position at its epoch`() {
        val epoch = 2024.25
        val nad = GeodeticPosition(40.0, -77.5, 150.0)
        val w = (FrameTransformationRegistry.DEFAULT.find(ReferenceFrame.NAD83_2011, ReferenceFrame.WGS84_AS_ITRF2014,
            CoordinateEpoch(epoch)) as FrameTransformationLookup.Available).apply(nad)
        val p = converter.convert(SourcePosition(nad.latDeg, nad.lonDeg, nad.heightM, null, isNad83 = true, epochYear = epoch),
            CoordinateSystemSettings(vertical = VerticalReference.EGM2008))
        assertEquals(w.heightM - n(egm2008, w.latDeg, w.lonDeg), p.height!!, 0.0)
    }

    @Test fun `EGM2008 outside the bundled tiles is unavailable, never EGM96`() {
        val p = converter.convert(wgs(60.5, 4.5, 100.0), CoordinateSystemSettings(vertical = VerticalReference.EGM2008))
        assertNull(p.height)
        assertEquals("outside EGM2008 coverage bundled in this app (US regions)", p.heightUnavailableReason)
        assertEquals("EGM2008", p.verticalLabel)
        assertEquals("EGM96 never consulted", listOf(GeoidModelId.EGM2008), asked)
        assertEquals("—", CoordinateFormat.height(p))
    }

    @Test fun `no ellipsoidal height gives no EGM height`() {
        val p = converter.convert(wgs(41.3477, -76.0226, null, msl = 424.0), CoordinateSystemSettings(vertical = VerticalReference.EGM2008))
        assertNull(p.height)
        assertEquals("no ellipsoidal height", p.heightUnavailableReason)
    }

    @Test fun `EGM height in US survey feet`() {
        val p = converter.convert(wgs(41.3477, -76.0226, 390.0),
            CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, vertical = VerticalReference.EGM2008, unit = LinearUnit.US_SURVEY_FEET))
        assertEquals((390.0 - n(egm2008, 41.3477, -76.0226)) / (1200.0 / 3937.0), p.height!!, 1e-9)
        assertTrue(CoordinateFormat.height(p).endsWith("ftUS EGM2008"))
        assertEquals("NAD83(2011) · SPCS 3701 · ftUS · EGM2008", CoordinateFormat.systemSummary(p))
    }

    @Test fun `receiver H is passed through and never gets a second geoid correction`() {
        val src = wgs(41.3477, -76.0226, 390.0, msl = 424.0)
        val rcvr = converter.convert(src, CoordinateSystemSettings(vertical = VerticalReference.RECEIVER_MSL))
        assertEquals(424.0, rcvr.height!!, 0.0)
        // EGM height is computed from h only: the receiver H does not enter it.
        val a = converter.convert(src, CoordinateSystemSettings(vertical = VerticalReference.EGM2008))
        val b = converter.convert(src.copy(receiverMslM = 999.0), CoordinateSystemSettings(vertical = VerticalReference.EGM2008))
        assertEquals(a.height!!, b.height!!, 0.0)
        assertNotEquals(424.0 - n(egm2008, 41.3477, -76.0226), a.height!!, 1e-6)
        assertEquals("only the two EGM2008 conversions asked a global model", listOf(GeoidModelId.EGM2008, GeoidModelId.EGM2008), asked)
    }

    @Test fun `legacy vertical references never touch the global models`() {
        val strict = CoordinateConverter({ lat, lon -> geoid18.undulation(lat, lon) }) { error("global model asked for $it") }
        val src = wgs(41.3477, -76.0226, 390.0, msl = 424.0)
        for (v in listOf(VerticalReference.ELLIPSOIDAL, VerticalReference.NAVD88_GEOID18, VerticalReference.RECEIVER_MSL)) {
            for (d in listOf(OutputDatum.WGS84, OutputDatum.NAD83_2011)) {
                val s = CoordinateSystemSettings(datum = d, vertical = v)
                assertEquals("$v $d", converter.convert(src, s), strict.convert(src, s))
                assertNull(strict.convert(src, s).epochYear)
            }
        }
    }

    @Test fun `ITRF2020 output equals the geokit registry transform at the epoch`() {
        val epoch = 2026.886
        val src = GeodeticPosition(41.3477, -76.0226, 390.0)
        val expected = (FrameTransformationRegistry.DEFAULT.find(ReferenceFrame.WGS84_AS_ITRF2014, ReferenceFrame.ITRF2020,
            CoordinateEpoch(epoch)) as FrameTransformationLookup.Available).apply(src)
        val p = converter.convert(wgs(src.latDeg, src.lonDeg, src.heightM, epoch = epoch),
            CoordinateSystemSettings(datum = OutputDatum.ITRF2020, vertical = VerticalReference.ELLIPSOIDAL))
        assertEquals(expected.latDeg, p.latDeg, 0.0)
        assertEquals(expected.lonDeg, p.lonDeg, 0.0)
        assertEquals(expected.heightM, p.height!!, 0.0)
        assertEquals(OutputDatum.ITRF2020, p.datum)
        assertEquals(epoch, p.epochYear!!, 0.0)
        assertEquals("ITRF2020 epoch 2026.886", CoordinateFormat.datumLabel(p))
        assertEquals("ITRF2020 epoch 2026.886 · m · h", CoordinateFormat.systemSummary(p))
        // The shift is real (mm level) but not zero.
        assertNotEquals(src.heightM, p.height!!, 0.0)

        val utm = converter.convert(wgs(src.latDeg, src.lonDeg, src.heightM, epoch = epoch),
            CoordinateSystemSettings(HorizontalSystem.UTM, datum = OutputDatum.ITRF2020))
        val u = Utm.forward(expected.latDeg, expected.lonDeg)
        assertEquals(u.eastingM, utm.easting!!, 0.0)
        assertEquals(u.northingM, utm.northing!!, 0.0)
    }

    @Test fun `State Plane stays NAD83(2011) even with ITRF2020 chosen`() {
        val p = converter.convert(wgs(41.3477, -76.0226, 390.0),
            CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, datum = OutputDatum.ITRF2020))
        assertEquals(OutputDatum.NAD83_2011, p.datum)
        assertNull(p.epochYear)
    }

    @Test fun `stored epoch - ITRF2020 uses the recorded coordinate epoch, other datums the capture time`() {
        val ts = 1_795_000_000_000L
        val c = Coordinate(id = "a", name = "a", latitude = 41.0, longitude = -76.0, altitude = 1.0, timestamp = ts,
            icon = "", color = 0, coordinateEpoch = 2020.5)
        val legacy = Itrf2014ToNad83.decimalYear(ts)
        assertEquals(2020.5, storedEpochYear(c, CoordinateSystemSettings(datum = OutputDatum.ITRF2020)), 0.0)
        assertEquals(legacy, storedEpochYear(c, CoordinateSystemSettings(datum = OutputDatum.WGS84)), 0.0)
        assertEquals(legacy, storedEpochYear(c, CoordinateSystemSettings(datum = OutputDatum.NAD83_2011)), 0.0)
        assertEquals(legacy, storedEpochYear(c, CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, datum = OutputDatum.ITRF2020)), 0.0)
        assertEquals(legacy, storedEpochYear(c.copy(coordinateEpoch = null), CoordinateSystemSettings(datum = OutputDatum.ITRF2020)), 0.0)
    }

    @Test fun `unavailable reasons have clear text`() {
        assertEquals("outside EGM2008 coverage bundled in this app (US regions)",
            AppGeoidModels.unavailableText(GeoidModelId.EGM2008, io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason.TILE_NOT_AVAILABLE))
        assertEquals("EGM96 data could not be loaded",
            AppGeoidModels.unavailableText(GeoidModelId.EGM96, io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason.LOAD_FAILED))
        assertNotNull(AppGeoidModels.model(GeoidModelId.EGM2008))
        assertNull(AppGeoidModels.model(GeoidModelId.GEOID18))
    }
}
