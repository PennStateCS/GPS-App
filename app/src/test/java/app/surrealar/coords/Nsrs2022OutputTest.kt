package app.surrealar.coords

import app.surrealar.data.export.PointsCsvExporter
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsDefinition
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 4 WP X: NSRS 2022 beta output (2022 frames, SPCS2022) in the app adapter. Expected values come from
 * geokit directly (the NSRS2022_BETA registry and CrsDefinition.forward), so the adapter is checked to add
 * nothing of its own; with the preview OFF every beta choice gives exactly the legacy default output.
 */
class Nsrs2022OutputTest {

    private val converter = CoordinateConverter({ _, _ -> 30.0 }) { null }
    private val beta = FrameTransformationRegistry.NSRS2022_BETA
    private val crs = Nsrs2022Registries.betaCrs

    private val epochs = listOf(2014.5, 2020.0, 2026.886)
    private val samples = listOf(
        doubleArrayOf(40.7934, -77.8600, 350.0),     // State College, PA
        doubleArrayOf(39.96, -83.0, 199.0),          // Columbus, OH
        doubleArrayOf(29.7625, -95.3625, -18.0),     // Houston, TX
        doubleArrayOf(21.3075, -157.8575, 34.0),     // Honolulu, HI (Pacific plate)
        doubleArrayOf(18.4675, -66.105, -37.0),      // San Juan, PR (Caribbean plate)
        doubleArrayOf(13.515, 144.8375, 152.0),      // Dededo, Guam (Mariana plate)
    )

    private fun src(p: DoubleArray, epoch: Double, datum: GeodeticDatum = GeodeticDatum.WGS84_ITRF2014) =
        SourcePosition(p[0], p[1], p[2], p[2] - 30.0, isNad83 = datum == GeodeticDatum.NAD83_2011, epochYear = epoch, datum = datum)

    private fun expectedFrame(s: SourcePosition, target: ReferenceFrame): GeodeticPosition =
        (beta.find(s.datum.frame, target, CoordinateEpoch(s.epochYear)) as FrameTransformationLookup.Available)
            .apply(GeodeticPosition(s.latDeg, s.lonDeg, s.ellipsoidalHeightM!!))

    private fun zone(code: String): CrsDefinition = requireNotNull(Nsrs2022Registries.spcs2022Zone("spcs2022:$code")) { code }

    // ── Registries ────────────────────────────────────────────────────────────────────────────

    @Test fun `preview OFF uses geokit's DEFAULT registries, ON the NSRS 2022 beta ones`() {
        assertSame(FrameTransformationRegistry.DEFAULT, Nsrs2022Registries.frames(false))
        assertSame(CrsRegistry.DEFAULT, Nsrs2022Registries.crs(false))
        assertSame(FrameTransformationRegistry.NSRS2022_BETA, Nsrs2022Registries.frames(true))
        assertSame(FrameTransformationRegistry.NSRS2022_BETA, Nsrs2022Registries.crs(true).frameRegistry)
        // DEFAULT itself has no 2022 edge and no operational SPCS2022 zone.
        assertTrue(FrameTransformationRegistry.DEFAULT.registeredEdges.none { Nsrs2022Registries.isFrame2022(it.to) || Nsrs2022Registries.isFrame2022(it.from) })
        assertTrue(CrsRegistry.DEFAULT.catalog(CrsCatalogId.SPCS2022).operational.isEmpty())
        assertEquals(953, crs.catalog(CrsCatalogId.SPCS2022).operational.size)
    }

    // ── Preview OFF: identical ──────────────────────────────────────────────────────────────────

    @Test fun `preview OFF - saved beta choices give exactly the legacy default output`() {
        for (p in samples) for (e in epochs) for (v in VerticalReference.entries.filter { !it.isBeta }) {
            val s = src(p, e)
            for (d in OutputDatum.entries.filter { it.isBeta }) {
                val saved = CoordinateSystemSettings(datum = d, vertical = v)
                assertEquals("$d $v", converter.convert(s, CoordinateSystemSettings(vertical = v)), converter.convert(s, saved))
                val utm = CoordinateSystemSettings(HorizontalSystem.UTM, datum = d, vertical = v)
                assertEquals(converter.convert(s, utm.copy(datum = OutputDatum.WGS84)), converter.convert(s, utm))
            }
            val spcs = CoordinateSystemSettings(HorizontalSystem.SPCS2022, vertical = v, spcs2022Zone = "spcs2022:421001")
            assertEquals(converter.convert(s, CoordinateSystemSettings(vertical = v)), converter.convert(s, spcs))
        }
    }

    @Test fun `legacy settings convert the same with the preview ON (the beta path is only for beta choices)`() {
        for (p in samples) for (h in listOf(HorizontalSystem.GEOGRAPHIC, HorizontalSystem.UTM, HorizontalSystem.STATE_PLANE))
            for (d in OutputDatum.entries.filter { !it.isBeta }) for (v in VerticalReference.entries.filter { !it.isBeta }) {
                val off = CoordinateSystemSettings(h, datum = d, vertical = v)
                assertSame(off, off.forConversion())
                assertEquals("$h $d $v", converter.convert(src(p, 2026.886), off), converter.convert(src(p, 2026.886), off.copy(nsrs2022Preview = true)))
            }
    }

    // ── 2022 frames, geographic ─────────────────────────────────────────────────────────────────

    @Test fun `2022 datum output equals the beta registry transform exactly, at the coordinate epoch`() {
        for ((d, frame) in listOf(OutputDatum.NATRF2022 to ReferenceFrame.NATRF2022, OutputDatum.PATRF2022 to ReferenceFrame.PATRF2022,
                OutputDatum.CATRF2022 to ReferenceFrame.CATRF2022, OutputDatum.MATRF2022 to ReferenceFrame.MATRF2022)) {
            assertSame(frame, d.frame2022); assertEquals(d, outputDatumOf2022(frame))
            for (p in samples) for (e in epochs) for (sd in listOf(GeodeticDatum.WGS84_ITRF2014, GeodeticDatum.NAD83_2011, GeodeticDatum.ITRF2020)) {
                val s = src(p, e, sd)
                val want = expectedFrame(s, frame)
                val out = converter.convert(s, CoordinateSystemSettings(datum = d, vertical = VerticalReference.ELLIPSOIDAL, nsrs2022Preview = true))
                assertEquals(want.latDeg, out.latDeg, 0.0); assertEquals(want.lonDeg, out.lonDeg, 0.0)
                assertEquals("ellipsoidal height in the 2022 frame", want.heightM, out.height!!, 0.0)
                assertEquals(d, out.datum); assertEquals(HorizontalSystem.GEOGRAPHIC, out.horizontal)
                assertNull(out.easting); assertNull(out.zoneLabel)
                assertEquals(e, out.epochYear!!, 0.0)
                assertEquals(Nsrs2022FrameLabels.DATASET, out.frameNote)
            }
        }
        // At 2020.0 NATRF2022 equals ITRF2020 (frames coincide), at other epochs it differs by plate motion.
        val pa = src(samples[0], 2020.0)
        val itrf = converter.convert(pa, CoordinateSystemSettings(datum = OutputDatum.ITRF2020))
        val natrf = converter.convert(pa, CoordinateSystemSettings(datum = OutputDatum.NATRF2022, nsrs2022Preview = true))
        assertEquals(itrf.latDeg, natrf.latDeg, 1e-12); assertEquals(itrf.lonDeg, natrf.lonDeg, 1e-12)
        val later = converter.convert(src(samples[0], 2026.886), CoordinateSystemSettings(datum = OutputDatum.NATRF2022, nsrs2022Preview = true))
        val laterItrf = converter.convert(src(samples[0], 2026.886), CoordinateSystemSettings(datum = OutputDatum.ITRF2020))
        assertTrue("plate motion over 6.9 yr is ~10 cm", abs(later.lonDeg - laterItrf.lonDeg) * 84_000 > 0.05)
    }

    @Test fun `orthometric heights are the vertical choice's, unchanged by the 2022 frame`() {
        for (v in listOf(VerticalReference.RECEIVER_MSL, VerticalReference.NAVD88_GEOID18)) {
            val s = src(samples[0], 2026.886)
            val legacy = converter.convert(s, CoordinateSystemSettings(vertical = v))
            val natrf = converter.convert(s, CoordinateSystemSettings(datum = OutputDatum.NATRF2022, vertical = v, nsrs2022Preview = true))
            assertEquals(legacy.height, natrf.height); assertEquals(legacy.verticalLabel, natrf.verticalLabel)
        }
    }

    @Test fun `2022 datum labels carry beta, the epoch and the dataset version`() {
        val out = converter.convert(src(samples[0], 2026.886), CoordinateSystemSettings(datum = OutputDatum.NATRF2022, nsrs2022Preview = true))
        assertEquals("NATRF2022 (beta) epoch 2026.886", CoordinateFormat.datumLabel(out))
        assertEquals("NATRF2022 (beta) epoch 2026.886 · m · H rcvr · EPP2022 beta 2025-06-16", CoordinateFormat.systemSummary(out))
        assertTrue(Nsrs2022FrameLabels.CAVEAT.contains("NGS beta Geodetic Station Page"))
        assertTrue(Nsrs2022FrameLabels.CAVEAT.contains("undocumented"))
        assertTrue(Nsrs2022FrameLabels.CAVEAT.contains("not authoritative"))
    }

    @Test fun `UTM is not offered on a 2022 frame - a saved 2022 datum gives the WGS84 UTM output`() {
        val s = CoordinateSystemSettings(HorizontalSystem.UTM, datum = OutputDatum.NATRF2022, nsrs2022Preview = true)
        assertTrue(s.betaDatumSuspended)
        assertEquals(OutputDatum.WGS84, s.effectiveDatum)
        assertTrue(s.selectableDatums.none { it.isBeta })
        val p = src(samples[0], 2026.886)
        assertEquals(converter.convert(p, s.copy(datum = OutputDatum.WGS84)), converter.convert(p, s))
        assertTrue(Nsrs2022OutputNotes.datumNote(s)!!.contains("no UTM on a 2022 frame"))
    }

    // ── SPCS2022 ────────────────────────────────────────────────────────────────────────────────

    private val zones = listOf(
        "420001" to ReferenceFrame.NATRF2022, "421001" to ReferenceFrame.NATRF2022, "421003" to ReferenceFrame.NATRF2022,
        "390001" to ReferenceFrame.NATRF2022, "480001" to ReferenceFrame.NATRF2022, "150001" to ReferenceFrame.PATRF2022,
        "720001" to ReferenceFrame.CATRF2022, "660001" to ReferenceFrame.MATRF2022,
    )

    private fun spcs(code: String, unit: LinearUnit = LinearUnit.METERS, v: VerticalReference = VerticalReference.RECEIVER_MSL) =
        CoordinateSystemSettings(HorizontalSystem.SPCS2022, unit = unit, vertical = v, nsrs2022Preview = true, spcs2022Zone = "spcs2022:$code")

    @Test fun `SPCS2022 output equals CrsDefinition forward of the beta-registry frame coordinates`() {
        for ((code, frame) in zones) {
            val z = zone(code)
            assertSame(code, frame, z.frame)
            assertEquals(ProductStatus.VERIFIED_BETA, z.status)
            for (p in samples) for (e in epochs) {
                val s = src(p, e)
                val f = expectedFrame(s, frame)
                val (eM, nM) = z.forward(f.latDeg, f.lonDeg)
                val out = converter.convert(s, spcs(code))
                assertEquals("$code E", eM, out.easting!!, 0.0); assertEquals("$code N", nM, out.northing!!, 0.0)
                assertEquals(f.latDeg, out.latDeg, 0.0); assertEquals(f.lonDeg, out.lonDeg, 0.0)
                assertEquals(HorizontalSystem.SPCS2022, out.horizontal)
                assertEquals(outputDatumOf2022(frame), out.datum)
                assertEquals("SPCS2022 $code (beta)", out.zoneLabel)
                assertEquals(e, out.epochYear!!, 0.0)
                assertEquals(!z.areaOfUse.contains(f.latDeg, f.lonDeg), out.outsideZone)
            }
        }
    }

    /** NGS-published example points (crs/spcs2022/ngs_example_coordinates.csv in geokit-core): frame lat/lon → E/N. */
    private val ngs = listOf(
        // code, lat, lon, h, northing, easting
        listOf("420001", 39.9525, -75.165, -22.0, 196701.284, 961525.027),
        listOf("421001", 41.41, -75.6625, 194.0, 159067.568, 388316.243),
        listOf("390001", 39.96, -83.0, 199.0, 439522.152, 888770.725),
        listOf("480001", 29.7625, -95.3625, -18.0, 574225.695, 2505028.036),
        listOf("150001", 21.3075, -157.8575, 34.0, 426159.640, 801106.375),
        listOf("720001", 18.4675, -66.105, -37.0, 225648.367, 510615.481),
        listOf("660001", 13.515, 144.8375, 152.0, 167638.070, 1022144.208),
    )

    @Test fun `SPCS2022 output reproduces the NGS example points (source built from the zone frame position)`() {
        for (row in ngs) {
            val code = row[0] as String
            val z = zone(code)
            val framePos = GeodeticPosition(row[1] as Double, row[2] as Double, row[3] as Double)
            for (e in epochs) {
                // The WGS84 (ITRF2014) position whose zone-frame coordinates at epoch e are NGS's point.
                val w = (beta.find(z.frame!!, ReferenceFrame.WGS84_AS_ITRF2014, CoordinateEpoch(e)) as FrameTransformationLookup.Available).apply(framePos)
                val out = converter.convert(SourcePosition(w.latDeg, w.lonDeg, w.heightM, null, isNad83 = false, epochYear = e), spcs(code))
                assertEquals("$code $e N", row[4] as Double, out.northing!!, 0.0015)
                assertEquals("$code $e E", row[5] as Double, out.easting!!, 0.0015)
            }
        }
    }

    @Test fun `SPCS2022 units - metres, international feet, US survey feet show as international feet`() {
        val s = src(samples[0], 2026.886)
        val m = converter.convert(s, spcs("421003"))
        val ft = converter.convert(s, spcs("421003", LinearUnit.INTERNATIONAL_FEET))
        assertEquals(LinearUnit.INTERNATIONAL_FEET, ft.unit)
        assertEquals(m.easting!! / 0.3048, ft.easting!!, 0.0); assertEquals(m.northing!! / 0.3048, ft.northing!!, 0.0)
        val us = converter.convert(s, spcs("421003", LinearUnit.US_SURVEY_FEET))
        assertEquals("never US survey feet for SPCS2022", LinearUnit.INTERNATIONAL_FEET, us.unit)
        assertEquals(ft.easting, us.easting); assertEquals(ft.northing, us.northing)
        // The height follows the same unit (receiver H in ift, from the legacy ftUS value).
        assertEquals(m.height!! / 0.3048, us.height!!, 1e-9)
        assertTrue(Nsrs2022OutputNotes.horizontalNote(spcs("421003", LinearUnit.US_SURVEY_FEET), zone("421003"))!!.contains("US survey foot"))
        // The Elevation block uses the OUTPUT unit too (review M2: it was labelled ftUS while showing ift).
        val e = converter.elevation(s, spcs("421003", LinearUnit.US_SURVEY_FEET))
        assertEquals(LinearUnit.INTERNATIONAL_FEET, e.unit)
    }

    @Test fun `SPCS2022 summary names the zone, frame and epoch, beta datasets, and heights stay separate`() {
        val out = converter.convert(src(samples[0], 2026.886), spcs("420001", v = VerticalReference.NAVD88_GEOID18))
        val legacy = converter.convert(src(samples[0], 2026.886), CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18))
        assertEquals("NAVD88", legacy.verticalLabel)
        assertEquals("SPCS2022 420001 (beta) · NATRF2022 epoch 2026.886 · m · NAVD88 · EPP2022 beta 2025-06-16 · NGS beta zones 2026-05-14",
            CoordinateFormat.systemSummary(out))
        assertEquals(legacy.height, out.height)
        val hi = converter.convert(src(samples[3], 2026.886), spcs("150001"))
        assertTrue(CoordinateFormat.systemSummary(hi).startsWith("SPCS2022 150001 (beta) · PATRF2022 epoch 2026.886"))
        // State College lies outside the Pennsylvania North East envelope: flagged, never hidden.
        assertTrue(CoordinateFormat.systemSummary(converter.convert(src(samples[0], 2026.886), spcs("421001"))).contains("outside zone"))
        assertTrue(CoordinateFormat.copyText(out).contains("SPCS2022 420001 (beta)"))
    }

    @Test fun `SPCS2022 fallbacks - no zone, unknown zone, loading, preview off`() {
        val s = src(samples[0], 2026.886)
        val default = converter.convert(s, CoordinateSystemSettings())
        val noZone = CoordinateSystemSettings(HorizontalSystem.SPCS2022, nsrs2022Preview = true)
        assertEquals(HorizontalSystem.GEOGRAPHIC, noZone.effectiveHorizontal)
        assertTrue(noZone.betaHorizontalSuspended)
        assertEquals(default, converter.convert(s, noZone))
        assertTrue(Nsrs2022OutputNotes.horizontalNote(noZone)!!.startsWith("Choose an SPCS2022 zone"))
        val unknown = converter.convert(s, spcs("999999"))
        assertEquals(default.copy(systemNote = "SPCS2022 zone spcs2022:999999 is not available"), unknown)
        assertTrue(CoordinateFormat.systemSummary(unknown).endsWith("SPCS2022 zone spcs2022:999999 is not available"))
        val loading = CoordinateConverter({ _, _ -> 30.0 }, { null }, { null }, FrameTransformationRegistry.DEFAULT,
            spcs2022Zone = { Spcs2022ZoneLookup.Loading })
        assertEquals(default.copy(systemNote = "SPCS2022 zone data is loading"), loading.convert(s, spcs("421001")))
        val off = spcs("421001").copy(nsrs2022Preview = false)
        assertEquals(default, converter.convert(s, off))
        assertEquals("spcs2022_beta", off.horizontalPrefValue)
        assertEquals("spcs2022:421001", off.spcs2022Zone)
        assertTrue(Nsrs2022OutputNotes.horizontalNote(off)!!.contains("preview is off"))
        // An SPCS83 zone id is never an SPCS2022 zone (never NAD83).
        assertNull(Nsrs2022Registries.spcs2022Zone("EPSG:6562"))
        assertNull(Nsrs2022Registries.spcs2022Zone("spcs2022:421001", CrsRegistry.DEFAULT))
    }

    @Test fun `coordinate epoch - stored points use their recorded epoch for 2022 outputs`() {
        val ts = 1_763_900_000_123L
        val c = Coordinate(id = "p", name = "p", latitude = 40.7934, longitude = -77.86, altitude = 350.0, timestamp = ts,
            icon = "", color = 0, crsEpsg = 4979, coordinateEpoch = 2025.5)
        val legacy = Itrf2014ToNad83.decimalYear(ts)
        assertEquals(2025.5, storedEpochYear(c, CoordinateSystemSettings(datum = OutputDatum.NATRF2022, nsrs2022Preview = true)), 0.0)
        assertEquals(2025.5, storedEpochYear(c, spcs("421001")), 0.0)
        // Preview OFF: the saved beta choice is not used, the capture time as before.
        assertEquals(legacy, storedEpochYear(c, CoordinateSystemSettings(datum = OutputDatum.NATRF2022)), 0.0)
        assertEquals(legacy, storedEpochYear(c, spcs("421001").copy(nsrs2022Preview = false)), 0.0)
        val out = converter.convert(storedSourcePosition(c, spcs("421001")), spcs("421001"))
        assertEquals(2025.5, out.epochYear!!, 0.0)
        assertTrue(CoordinateFormat.systemSummary(out).contains("NATRF2022 epoch 2025.500"))
    }

    @Test fun `CSV - SPCS2022 keeps the PNEZD columns, the System column names zone, frame, epoch and beta`() {
        val c = Coordinate(id = "P1", name = "P1", latitude = 40.7934, longitude = -77.86, altitude = 350.0,
            timestamp = 1_795_000_000_000L, icon = "", color = 0, crsEpsg = 4979)
        val conv = { p: Coordinate -> converter.convert(SourcePosition(p.latitude, p.longitude, p.altitude, null, isNad83 = false, epochYear = 2026.886), spcs("421003", v = VerticalReference.ELLIPSOIDAL)) }
        val lines = PointsCsvExporter.build(listOf(c), conv).trim().lines()
        assertEquals("Point,Northing (m),Easting (m),Elevation (m h),Description,System", lines[0])
        assertEquals("SPCS2022 421003 (beta) · NATRF2022 epoch 2026.886 · m · h · EPP2022 beta 2025-06-16 · NGS beta zones 2026-05-14",
            lines[1].substringAfterLast(','))
        assertEquals(6, lines[1].split(",").size)
        val geo = PointsCsvExporter.build(listOf(c)) { p ->
            converter.convert(SourcePosition(p.latitude, p.longitude, p.altitude, null, isNad83 = false, epochYear = 2026.886),
                CoordinateSystemSettings(datum = OutputDatum.NATRF2022, nsrs2022Preview = true))
        }.trim().lines()
        assertEquals("Point,Latitude,Longitude,Elevation (m H rcvr),Description,System", geo[0])
        assertEquals("NATRF2022 (beta) epoch 2026.886 · m · H rcvr · EPP2022 beta 2025-06-16", geo[1].substringAfterLast(','))
    }

    @Test fun `elevation block - ellipsoidal height is in the 2022 frame with its epoch`() {
        val s = src(samples[0], 2026.886)
        val settings = CoordinateSystemSettings(datum = OutputDatum.NATRF2022, vertical = VerticalReference.ELLIPSOIDAL, nsrs2022Preview = true)
        val e = converter.elevation(s, settings)
        assertEquals("NATRF2022 (beta) epoch 2026.886", e.ellipsoidalFrame)
        assertEquals(expectedFrame(s, ReferenceFrame.NATRF2022).heightM, e.ellipsoidalHeight!!, 0.0)
        assertNotNull(converter.elevation(s, spcs("421001")))
        assertFalse(e.loading)
    }
}
