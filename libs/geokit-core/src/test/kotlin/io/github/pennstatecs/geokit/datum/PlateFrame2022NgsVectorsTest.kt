package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsLookup
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.ellipsoid.EcefPosition
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * ITRF2020 ⇄ NATRF/PATRF/CATRF/MATRF2022 against NGS-computed coordinates (Phase 4 WP N).
 *
 * Reference: the NGS beta Geodetic Station Pages service (beta.ngs.noaa.gov/api_m/gsp/coords), which converts
 * an ITRF2020 position at epoch t to the four 2022 frames at the same epoch t. 13 CORS (North American,
 * Pacific, Caribbean and Mariana plates; heights −30…3,750 m) × epochs 2014.5, 2020.0, 2026.9 × 4 frames =
 * 156 pairs, pinned byte for byte in test resources nsrs/ngs-gsp/ by tools/nsrs/fetch_epp2022_gsp.py.
 *
 * Tolerance: NGS returns full double precision and the EPP2022 relation (TR NOS NGS 62 eq. 60) is exact
 * by definition, so we require 1 µm in X/Y/Z and h and 1e-11° (≈ 1 µm) in lat/lon — three orders below
 * the 1 mm surveying tolerance. The literal sign of the beta web page misses by 4 cm … 3 m on these data.
 */
class PlateFrame2022NgsVectorsTest {

    private val grs80 = Ellipsoid.GRS80

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.use { it.readBytes() }

    private class Row(
        val station: String, val epoch: Double, val frame: PlateFrame2022,
        val itrf: EcefPosition, val itrfGeo: GeodeticPosition, val out: EcefPosition, val outGeo: GeodeticPosition,
    )

    private val rows: List<Row> by lazy {
        val lines = String(resource("/nsrs/ngs-gsp/epp2022_gsp_vectors.csv"), Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(
            "station,epoch,frame,itrf_x,itrf_y,itrf_z,itrf_lat,itrf_lon,itrf_h,frame_x,frame_y,frame_z,frame_lat,frame_lon,frame_h,source",
            lines.first(),
        )
        lines.drop(1).map { l ->
            val f = l.split(',')
            val d = f.subList(3, 15).map(String::toDouble)
            Row(
                f[0], f[1].toDouble(), requireNotNull(PlateFrame2022.entries.firstOrNull { it.displayName == f[2] }) { f[2] },
                EcefPosition(d[0], d[1], d[2]), GeodeticPosition(d[3], d[4], d[5]),
                EcefPosition(d[6], d[7], d[8]), GeodeticPosition(d[9], d[10], d[11]),
            )
        }
    }

    private fun maxDiff(a: EcefPosition, b: EcefPosition) = maxOf(abs(a.xM - b.xM), abs(a.yM - b.yM), abs(a.zM - b.zM))

    @Test fun `raw NGS responses match their recorded SHA-256`() {
        val prov = String(resource("/nsrs/ngs-gsp/PROVENANCE.csv"), Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(78, prov.size)
        for (row in prov) {
            val f = row.split(',')
            val bytes = resource("/nsrs/ngs-gsp/${f[0]}")
            assertEquals(f[0], f[2].toInt(), bytes.size)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(f[0], f[3], sha)
            assertTrue(f[4], f[4].startsWith("https://beta.ngs.noaa.gov/api_m/"))
        }
    }

    @Test fun `vector set covers four frames, thirteen stations, three epochs, real heights`() {
        assertEquals(156, rows.size)
        assertEquals(PlateFrame2022.entries.toSet(), rows.map { it.frame }.toSet())
        assertEquals(13, rows.map { it.station }.toSet().size)
        assertEquals(setOf(2014.5, 2020.0, 2026.9), rows.map { it.epoch }.toSet())
        assertTrue(rows.minOf { it.itrfGeo.heightM } < -20 && rows.maxOf { it.itrfGeo.heightM } > 3000)
    }

    @Test fun `forward reproduces NGS in XYZ and geographic coordinates`() {
        var worst = 0.0
        for (r in rows) {
            val x = r.frame.rotation.forward(r.itrf, r.epoch)
            worst = max(worst, maxDiff(x, r.out))
            val g = r.frame.rotation.forward(r.itrfGeo, r.epoch)
            val tag = "${r.station} ${r.epoch} ${r.frame}"
            assertEquals(tag, r.outGeo.latDeg, g.latDeg, 1e-11)
            assertEquals(tag, r.outGeo.lonDeg, g.lonDeg, 1e-11)
            assertEquals(tag, r.outGeo.heightM, g.heightM, 1e-6)
            if (r.epoch == 2020.0) assertEquals(tag, r.itrf, r.out)   // identical at the reference epoch
        }
        assertTrue("worst |dXYZ| $worst m", worst < 1e-6)
    }

    @Test fun `inverse recovers NGS's ITRF2020 input from NGS's frame output`() {
        var worst = 0.0
        for (r in rows) {
            worst = max(worst, maxDiff(r.frame.rotation.inverse(r.out, r.epoch), r.itrf))
            val g = r.frame.rotation.inverse(r.outGeo, r.epoch)
            assertEquals(r.itrfGeo.latDeg, g.latDeg, 1e-11)
            assertEquals(r.itrfGeo.lonDeg, g.lonDeg, 1e-11)
            assertEquals(r.itrfGeo.heightM, g.heightM, 1e-6)
        }
        assertTrue("worst |dXYZ| $worst m", worst < 1e-6)
    }

    @Test fun `NGS output rejects the literal sign of the beta web page`() {
        for (r in rows.filter { it.epoch != 2020.0 }) {
            val f = r.frame.rotation.forward(r.itrf, r.epoch)
            // Literal page reading: X + K·Δt·(Ω × X) = 2·X − forward(X).
            val literal = EcefPosition(2 * r.itrf.xM - f.xM, 2 * r.itrf.yM - f.yM, 2 * r.itrf.zM - f.zM)
            assertTrue("${r.station} ${r.epoch} ${r.frame}", maxDiff(literal, r.out) > 0.01)
        }
    }

    @Test fun `NGS geographic coordinates are GRS80 conversions of the XYZ`() {
        for (r in rows) for ((x, g) in listOf(r.itrf to r.itrfGeo, r.out to r.outGeo)) {
            val p = grs80.fromEcef(x)
            assertEquals(g.latDeg, p.latDeg, 1e-11); assertEquals(g.lonDeg, p.lonDeg, 1e-11); assertEquals(g.heightM, p.heightM, 1e-6)
        }
    }

    // ── opt-in registry ─────────────────────────────────────────────────────────────────────────

    private val e = CoordinateEpoch(2026.9)

    @Test fun `NSRS2022_BETA registers ITRF2020 to NATRF2022 as verified beta, matching NGS`() {
        val edge = FrameTransformationRegistry.ITRF2020_TO_NATRF2022
        assertEquals(ProductStatus.VERIFIED_BETA, edge.status)
        assertEquals(PlateFrame2022.DATASET_VERSION, edge.datasetVersion)
        assertEquals(PlateFrame2022.VERIFICATION, edge.verification)
        val reg = FrameTransformationRegistry.NSRS2022_BETA
        for (r in rows.filter { it.frame == PlateFrame2022.NATRF2022 }) {
            val ep = CoordinateEpoch(r.epoch)
            val fwd = reg.find(ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, ep) as FrameTransformationLookup.Available
            assertEquals(ProductStatus.VERIFIED_BETA, fwd.status)
            assertEquals(listOf(PlateFrame2022.DATASET_VERSION), fwd.transformation.datasetVersions)
            val g = fwd.apply(r.itrfGeo)
            assertEquals(r.outGeo.latDeg, g.latDeg, 1e-11); assertEquals(r.outGeo.lonDeg, g.lonDeg, 1e-11); assertEquals(r.outGeo.heightM, g.heightM, 1e-6)
            val inv = (reg.find("natrf2022", "itrf2020", ep) as FrameTransformationLookup.Available).apply(r.outGeo)
            assertEquals(r.itrfGeo.latDeg, inv.latDeg, 1e-11); assertEquals(r.itrfGeo.lonDeg, inv.lonDeg, 1e-11)
        }
        // Chains through the beta edge are beta; the app's stored frame reaches NATRF2022 via ITRF2014 → ITRF2020.
        val chain = reg.find(ReferenceFrame.WGS84_AS_ITRF2014, ReferenceFrame.NATRF2022, e) as FrameTransformationLookup.Available
        assertEquals(ProductStatus.VERIFIED_BETA, chain.status)
        assertEquals(listOf(FrameTransformationRegistry.ITRF2014_TO_ITRF2020, edge), chain.transformation.steps)
        val fromNad = reg.find(ReferenceFrame.NAD83_2011, ReferenceFrame.NATRF2022, e) as FrameTransformationLookup.Available
        assertEquals(ProductStatus.VERIFIED_BETA, fromNad.status)
        assertEquals(3, fromNad.transformation.steps.size)
    }

    @Test fun `NSRS2022_BETA leaves every legacy path and every other frame as in DEFAULT`() {
        val beta = FrameTransformationRegistry.NSRS2022_BETA
        val def = FrameTransformationRegistry.DEFAULT
        val p = GeodeticPosition(40.8, -77.86, 350.0)
        for (s in ReferenceFrame.entries) for (t in ReferenceFrame.entries) {
            if (s == ReferenceFrame.NATRF2022 || t == ReferenceFrame.NATRF2022) continue
            val a = def.find(s, t, e); val b = beta.find(s, t, e)
            assertEquals("$s→$t", a.status, b.status)
            if (a is FrameTransformationLookup.Available) {
                b as FrameTransformationLookup.Available
                assertEquals("$s→$t", a.transformation.steps, b.transformation.steps)
                assertEquals(a.apply(p), b.apply(p))
            } else {
                assertEquals((a as FrameTransformationLookup.Unavailable).reason, (b as FrameTransformationLookup.Unavailable).reason)
            }
        }
        for (id in listOf("patrf2022", "catrf2022", "matrf2022")) {
            assertTrue(beta.find("itrf2020", id, e) is FrameTransformationLookup.Unavailable)
        }
        // DEFAULT itself is unchanged: no 2022-frame edge.
        assertTrue(def.registeredEdges.none { it.to == ReferenceFrame.NATRF2022 || it.from == ReferenceFrame.NATRF2022 })
        assertSame(FrameTransformationRegistry.ITRF2020_TO_NATRF2022, beta.registeredEdges.last())
    }

    @Test fun `with NSRS2022_BETA the NATRF2022 SPCS2022 zones become operational beta`() {
        val r = CrsRegistry(frameRegistry = FrameTransformationRegistry.NSRS2022_BETA)
        val c = r.catalog(CrsCatalogId.SPCS2022)
        assertEquals(949, c.operational.size)
        assertTrue(c.operational.all { it.status == ProductStatus.VERIFIED_BETA && it.frame == ReferenceFrame.NATRF2022 })
        val pa = (r.byId("spcs2022:420001") as CrsLookup.Found).definition
        assertEquals(ProductStatus.VERIFIED_BETA, pa.status)
        assertTrue(r.byId("SPCS2022:150001") is CrsLookup.Unsupported)   // Hawaii is on PATRF2022, not a GeoKit frame yet
        assertEquals(ProductStatus.VERIFIED_BETA, r.geographic(ReferenceFrame.NATRF2022).status)
        assertTrue(r.geographic(ReferenceFrame.NATRF2022).operational)
        // The default registry is unchanged.
        assertTrue(CrsRegistry.DEFAULT.catalog(CrsCatalogId.SPCS2022).operational.isEmpty())
    }
}
