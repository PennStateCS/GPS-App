package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import app.surrealar.ui.toolbar.GnssToolbarStateMapper
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.geoid.Geoid2022Model
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidModelInfo
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 4 WP U: the height parts shown on Details and in the Settings preview (h, H, N, model + version,
 * vertical reference, epoch, beta) agree with the converted height, and a loading model shows a loading
 * state, never a value. convert() itself is untouched (golden tests).
 */
class ElevationDetailTest {

    private companion object {
        val g2022: Geoid2022Model by lazy { Geoid2022Model.bundled() }
        val egm2008 by lazy { GlobalGeoidModels.egm2008() }
    }

    private val geoid18N = 33.25
    private val converter = CoordinateConverter(
        { _, _ -> geoid18N },
        { id -> if (id == GeoidModelId.EGM2008) egm2008 else null },
        { Geoid2022TimeDependent(g2022) },
        FrameTransformationRegistry.DEFAULT,
    )

    private val millis = LocalDate.of(2026, 11, 20).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val epoch = Itrf2014ToNad83.decimalYear(millis)
    private val src = SourcePosition(41.3477, -76.0226, 390.0, 421.5, isNad83 = false, epochYear = epoch)

    private fun settings(v: VerticalReference, preview: Boolean = false, unit: LinearUnit = LinearUnit.METERS) =
        CoordinateSystemSettings(vertical = v, nsrs2022Preview = preview, unit = unit)

    @Test fun `GEOID2022 beta - h(ITRF2020), N(epoch), H = h - N, model, version, NAPGD2022, epoch, beta`() {
        val s = settings(VerticalReference.GEOID2022, preview = true)
        val p = converter.convert(src, s)
        val e = converter.elevation(src, s)
        assertEquals(p.height!!, e.orthometricHeight!!, 0.0)
        assertEquals(e.ellipsoidalHeight!! - e.undulation!!, e.orthometricHeight!!, 1e-9)
        assertEquals("GEOID2022 (beta)", e.geoidModel)
        assertEquals("beta_v0a", e.geoidVersion)
        assertEquals("NAPGD2022", e.verticalReference)
        assertEquals(epoch, e.epochYear!!, 0.0)
        assertTrue(e.beta)
        assertFalse(e.loading)
        assertEquals("ITRF2020 epoch 2026.886", e.ellipsoidalFrame)
        val rows = ElevationFormat.rows(e)
        assertEquals(listOf("h (ellipsoidal) · ITRF2020 epoch 2026.886", "H (orthometric) · NAPGD2022 (beta)",
            "N (geoid undulation)", "Geoid model", "Vertical reference", "Height epoch", "Status"), rows.map { it.label })
        assertEquals("GEOID2022 (beta) · beta_v0a", rows[3].value)
        assertEquals("NAPGD2022 (beta)", rows[4].value)
        assertEquals("2026.886", rows[5].value)
        assertEquals(ElevationFormat.STATUS_BETA, rows[6].value)
        // Never presented as NAVD88 or MSL.
        for (r in rows) assertFalse(r.toString(), r.value.contains("NAVD88") || r.label.contains("NAVD88") || r.value.contains("MSL"))
        val line = ElevationFormat.previewLine(e)
        assertTrue(line, line.startsWith("h ") && line.contains(" · N ") && line.contains("(GEOID2022 (beta) beta_v0a)") &&
            line.endsWith("NAPGD2022 (beta)"))
    }

    @Test fun `GEOID18 - h in NAD83(2011), N = h - H equals the grid value, NAVD88, no epoch, verified`() {
        val s = settings(VerticalReference.NAVD88_GEOID18)
        val e = converter.elevation(src, s)
        assertEquals(geoid18N, e.undulation!!, 1e-9)
        assertEquals(converter.convert(src, s).height!!, e.orthometricHeight!!, 0.0)
        assertEquals("NAD83(2011)", e.ellipsoidalFrame)
        assertEquals("GEOID18", e.geoidModel)
        assertEquals("g2018u0+g2018p0", e.geoidVersion)
        assertEquals("NAVD88", e.verticalReference)
        assertNull(e.epochYear)
        assertFalse(e.beta)
        val labels = ElevationFormat.rows(e).map { it.label }
        assertFalse(labels.contains("Height epoch"))
        assertEquals(ElevationFormat.STATUS_VERIFIED, ElevationFormat.rows(e).last().value)
    }

    @Test fun `EGM2008 - h in WGS84 (ITRF2014), N from the model, units follow the setting`() {
        val s = settings(VerticalReference.EGM2008, unit = LinearUnit.US_SURVEY_FEET)
        val e = converter.elevation(src, s)
        val n = (egm2008.undulation(src.latDeg, src.lonDeg) as GeoidResult.Value).undulationM
        assertEquals(LinearUnit.US_SURVEY_FEET.fromMeters(390.0), e.ellipsoidalHeight!!, 1e-9)
        assertEquals(LinearUnit.US_SURVEY_FEET.fromMeters(n), e.undulation!!, 1e-9)
        assertEquals(converter.convert(src, s).height!!, e.orthometricHeight!!, 0.0)
        assertEquals("EGM2008", e.verticalReference)
        assertEquals("WGS84 (ITRF2014)", e.ellipsoidalFrame)
        assertTrue(ElevationFormat.rows(e)[0].value.endsWith("ftUS"))
    }

    @Test fun `ellipsoidal and receiver heights - no N row, no invented model`() {
        val h = converter.elevation(src, settings(VerticalReference.ELLIPSOIDAL))
        assertEquals(listOf("h (ellipsoidal) · WGS84 (ITRF2014)", "Vertical reference", "Status"), ElevationFormat.rows(h).map { it.label })
        assertEquals(ElevationDetail.ELLIPSOID, h.verticalReference)
        val r = converter.elevation(src, settings(VerticalReference.RECEIVER_MSL))
        assertEquals(421.5, r.orthometricHeight!!, 0.0)
        assertEquals(390.0, r.ellipsoidalHeight!!, 0.0)
        assertNull(r.undulation)
        assertFalse(ElevationFormat.rows(r).any { it.label.startsWith("N ") })
        assertEquals("receiver geoid (model unknown)", r.verticalReference)
    }

    @Test fun `ITRF2020 output carries its epoch in the elevation too`() {
        val s = CoordinateSystemSettings(datum = OutputDatum.ITRF2020, vertical = VerticalReference.ELLIPSOIDAL)
        val e = converter.elevation(src.copy(datum = GeodeticDatum.WGS84_ITRF2014), s)
        assertEquals(epoch, e.epochYear!!, 0.0)
        assertEquals("ITRF2020 epoch 2026.886", e.ellipsoidalFrame)
    }

    @Test fun `preview OFF with GEOID2022 saved - default receiver height, not beta, convert unchanged`() {
        val off = settings(VerticalReference.GEOID2022, preview = false)
        val e = converter.elevation(src, off)
        assertEquals(VerticalReference.RECEIVER_MSL, e.vertical)
        assertFalse(e.beta)
        assertEquals(converter.convert(src, settings(VerticalReference.RECEIVER_MSL)), converter.convert(src, off))
    }

    /** GEOID2022 whose data is still loading (as on the main thread before the background load). */
    private val loadingConverter = CoordinateConverter(
        { _, _ -> null }, { null },
        {
            object : TimeDependentGeoid {
                override val info: GeoidModelInfo get() = Geoid2022Model.INFO
                override fun undulationAt(latDeg: Double, lonDeg: Double, epochYear: Double): GeoidResult =
                    GeoidResult.Unavailable(GeoidUnavailableReason.DATA_LOADING, GeoidModelId.GEOID2022, "loading")
            }
        },
        FrameTransformationRegistry.DEFAULT,
    )

    @Test fun `loading - no value anywhere, a clear loading state on Details, preview and toolbar`() {
        val s = settings(VerticalReference.GEOID2022, preview = true)
        val p = loadingConverter.convert(src, s)
        assertNull(p.height)
        assertTrue(p.heightLoading)
        assertEquals("GEOID2022 data is loading", p.heightUnavailableReason)
        assertEquals("GEOID2022 data is loading", CoordinateFormat.heightOrLoading(p))
        assertEquals("—", CoordinateFormat.height(p))
        val e = loadingConverter.elevation(src, s, p)
        assertTrue(e.loading)
        assertNull(e.orthometricHeight)
        assertNull(e.undulation)
        val rows = ElevationFormat.rows(e).associate { it.label to it.value }
        assertEquals("Loading GEOID2022 (beta) data…", rows.getValue("H (orthometric) · NAPGD2022 (beta)"))
        assertEquals("Loading GEOID2022 (beta) data…", rows.getValue("N (geoid undulation)"))
        assertTrue(ElevationFormat.previewLine(e).contains("H NAPGD2022 (beta): Loading GEOID2022 (beta) data…"))
        // A model that has no data at the point is "unavailable", not "loading".
        val outside = converter.convert(src.copy(latDeg = 19.43, lonDeg = -99.13), s)
        assertFalse(outside.heightLoading)
        assertEquals("—", CoordinateFormat.heightOrLoading(outside))
    }

    @Test fun `GEOID18 grid still loading reads as loading, not outside coverage - identity otherwise`() {
        val noGrid = CoordinateConverter({ _, _ -> null }, { null })
        val s = settings(VerticalReference.NAVD88_GEOID18)
        val p = noGrid.convert(src, s)
        assertEquals("outside GEOID18 coverage", p.heightUnavailableReason)
        val loading = markGeoid18Loading(p, s, gridLoading = true)
        assertTrue(loading.heightLoading)
        assertEquals(GEOID18_LOADING, loading.heightUnavailableReason)
        assertEquals(p, markGeoid18Loading(p, s, gridLoading = false))       // loaded or failed: unchanged
        val other = settings(VerticalReference.ELLIPSOIDAL)
        val q = noGrid.convert(src, other)
        assertEquals(q, markGeoid18Loading(q, other, gridLoading = true))
        assertEquals("Loading GEOID18 data…", ElevationFormat.rows(noGrid.elevation(src, s, loading))
            .first { it.label.startsWith("H ") }.value)
    }

    @Test fun `toolbar - concise height labels for every reference, GEOID2022 is NAPGD2022 beta-marked`() {
        val labels = VerticalReference.entries.map { v ->
            GnssToolbarStateMapper.toolbarHeightLabel(converter.convert(src, settings(v, preview = true)))
        } + listOf("PRVD02", "VIVD09")
        for (l in labels) assertTrue("'$l' too long", l.length <= GnssToolbarStateMapper.MAX_HEIGHT_LABEL)
        val g = GnssToolbarStateMapper.toolbarHeightLabel(converter.convert(src, settings(VerticalReference.GEOID2022, preview = true)))
        assertEquals("NAPGD2022β", g)
        assertEquals(Geoid2022Labels.TOOLBAR_LABEL, g)
        assertEquals(listOf("h", "NAVD88", "H rcvr", "EGM2008", "EGM96", "NAPGD2022β"), labels.dropLast(2))
    }
}
