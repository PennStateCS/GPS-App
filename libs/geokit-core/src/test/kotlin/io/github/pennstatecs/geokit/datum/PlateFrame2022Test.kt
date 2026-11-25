package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.EcefPosition
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * The NGS beta EPP2022 plate frames, the Euler-pole rotation and the registry's status reporting.
 *
 * No NGS reference coordinates for ITRF2020 ⇄ NATRF/PATRF/CATRF/MATRF2022 exist yet (2026-11-25; see
 * docs/geodesy/nsrs2022-sources-frames.md), so the frames stay UNAVAILABLE and these tests check only:
 * the parameters against NGS's own CSV (pinned in test resources by tools/nsrs/fetch_epp2022.py), the
 * algebra (identity at 2020.0, exact inverse, epoch dependence) and the defining property from NOAA TR
 * NOS NGS 62 (a point riding the rigid plate keeps constant plate-fixed coordinates).
 */
class PlateFrame2022Test {

    private val grs80 = Ellipsoid.GRS80

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.use { it.readBytes() }

    private fun dist(a: EcefPosition, b: EcefPosition): Double {
        val dx = a.xM - b.xM; val dy = a.yM - b.yM; val dz = a.zM - b.zM
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Sample points per plate area (CONUS east/west, Alaska, Hawaii, Puerto Rico, Guam) at several heights. */
    private val points = listOf(
        GeodeticPosition(41.3477, -76.0226, 390.0), GeodeticPosition(37.7749, -122.4194, -30.0),
        GeodeticPosition(61.2181, -149.9003, 40.0), GeodeticPosition(19.8968, -155.5828, 4200.0),
        GeodeticPosition(18.2208, -66.5901, 0.0), GeodeticPosition(13.4443, 144.7937, 120.0),
    )
    private val epochs = listOf(1990.0, 2010.0, 2020.0, 2026.9, 2030.0, 2100.0)

    // ── NGS parameters ──────────────────────────────────────────────────────────────────────────

    @Test fun `EPP constants equal the NGS beta CSV`() {
        val lines = String(resource("/nsrs/raw/epp2022-beta-values.csv"), Charsets.US_ASCII).lines().filter { it.isNotBlank() }
        assertEquals("Plate,Omega X (mas/yr),Omega Y (mas/yr),Omega Z (mas/yr)", lines.first())
        val rows = lines.drop(1).associate { l -> l.split(',').let { it[0] to it.drop(1).map(String::toDouble) } }
        assertEquals(PlateFrame2022.entries.map { it.displayName }.toSet(), rows.keys)
        for (f in PlateFrame2022.entries) {
            val r = rows.getValue(f.displayName)
            assertEquals(f.name, r, listOf(f.epp.omegaXMasPerYr, f.epp.omegaYMasPerYr, f.epp.omegaZMasPerYr))
        }
    }

    @Test fun `raw NGS files match their recorded SHA-256`() {
        val prov = String(resource("/nsrs/raw/PROVENANCE.csv"), Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(4, prov.size)
        for (row in prov) {
            val f = row.split(',')
            val bytes = resource("/nsrs/raw/${f[0]}")
            assertEquals(f[0], f[2].toInt(), bytes.size)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(f[0], f[3], sha)
        }
    }

    @Test fun `frame metadata`() {
        for (f in PlateFrame2022.entries) {
            assertEquals(2020.0, f.referenceEpoch, 0.0)
            assertSame(f, PlateFrame2022.fromId(f.id))
            assertSame(f, PlateFrame2022.forPlate(f.plate))
            assertEquals(ProductStatus.UNAVAILABLE, f.status)
            assertTrue(f.unavailableReason, f.unavailableReason.startsWith(f.displayName) && f.unavailableReason.contains("ballpark"))
            assertFalse(f.plate.areaNote.isBlank())
        }
        assertEquals(listOf("natrf2022", "patrf2022", "catrf2022", "matrf2022"), PlateFrame2022.entries.map { it.id })
        assertSame(ReferenceFrame.NATRF2022, PlateFrame2022.NATRF2022.referenceFrame)
        assertEquals(ReferenceFrame.NATRF2022.epsg3d, PlateFrame2022.NATRF2022.epsg3d)
        assertEquals(ReferenceFrame.NATRF2022.id, PlateFrame2022.NATRF2022.id)
        assertEquals(ReferenceFrame.NATRF2022.referenceEpoch!!, PlateFrame2022.NATRF2022.referenceEpoch, 0.0)
        for (f in listOf(PlateFrame2022.PATRF2022, PlateFrame2022.CATRF2022, PlateFrame2022.MATRF2022)) {
            assertNull(f.referenceFrame); assertNull(f.epsg3d); assertNull(ReferenceFrame.fromId(f.id))
        }
        assertEquals(TectonicPlate.PACIFIC, PlateFrame2022.PATRF2022.plate)
        assertEquals(TectonicPlate.CARIBBEAN, PlateFrame2022.CATRF2022.plate)
        assertEquals(TectonicPlate.MARIANA, PlateFrame2022.MATRF2022.plate)
        assertNull(PlateFrame2022.fromId("NATRF2022")); assertNull(PlateFrame2022.fromId(null))
        // Plate exposed on ReferenceFrame so callers can warn about off-plate points.
        assertEquals(TectonicPlate.NORTH_AMERICA, ReferenceFrame.NATRF2022.plate)
        assertEquals(TectonicPlate.NORTH_AMERICA, ReferenceFrame.NAD83_2011.plate)
        for (g in ReferenceFrame.entries.filter { it.kind == FrameKind.GLOBAL }) assertNull(g.plate)
    }

    // ── Rotation algebra ────────────────────────────────────────────────────────────────────────

    @Test fun `frames coincide with ITRF2020 at 2020_0 exactly`() {
        for (f in PlateFrame2022.entries) for (p in points) {
            val x = grs80.toEcef(p)
            assertEquals(x, f.rotation.forward(x, 2020.0))
            assertEquals(x, f.rotation.inverse(x, 2020.0))
        }
    }

    @Test fun `inverse is exact`() {
        var worst = 0.0
        for (f in PlateFrame2022.entries) for (p in points) for (t in epochs) {
            val x = grs80.toEcef(p)
            worst = maxOf(worst, dist(x, f.rotation.inverse(f.rotation.forward(x, t), t)))
            worst = maxOf(worst, dist(x, f.rotation.forward(f.rotation.inverse(x, t), t)))
            val g = f.rotation.inverse(f.rotation.forward(p, t), t)
            assertEquals(p.latDeg, g.latDeg, 1e-11); assertEquals(p.lonDeg, g.lonDeg, 1e-11); assertEquals(p.heightM, g.heightM, 1e-6)
        }
        assertTrue("worst ECEF round trip $worst m", worst < 1e-8)
    }

    @Test fun `shift is linear in the epoch and antisymmetric about 2020_0`() {
        for (f in PlateFrame2022.entries) for (p in points) {
            val x = grs80.toEcef(p)
            val a = f.rotation.forward(x, 2010.0); val b = f.rotation.forward(x, 2030.0)
            assertEquals(0.0, (a.xM - x.xM) + (b.xM - x.xM), 1e-8)
            assertEquals(0.0, (a.yM - x.yM) + (b.yM - x.yM), 1e-8)
            assertEquals(0.0, (a.zM - x.zM) + (b.zM - x.zM), 1e-8)
            val c = f.rotation.forward(x, 2040.0)
            assertEquals(2 * (b.xM - x.xM), c.xM - x.xM, 1e-8)
            // A rotation: the geocentric distance is unchanged to first order (|Δr| ~ |w|² r).
            assertEquals(sqrt(x.xM * x.xM + x.yM * x.yM + x.zM * x.zM), sqrt(c.xM * c.xM + c.yM * c.yM + c.zM * c.zM), 1e-4)
        }
    }

    /**
     * NOAA TR NOS NGS 62 eqs. 17–18: with no intra-frame motion, the plate-fixed coordinates of a point
     * on the rigid plate (global velocity Ω × X) stay at their 2020.0 values. The opposite sign (the beta
     * web page's equations read literally) would instead double the plate motion.
     */
    @Test fun `a point riding the rigid plate keeps its plate-fixed coordinates`() {
        val k = EulerPoleRotation.MAS_TO_RAD
        for (f in PlateFrame2022.entries) for (p in points) for (t in listOf(2010.0, 2026.9, 2030.0)) {
            val x0 = grs80.toEcef(p)
            val o = f.epp
            val dt = t - 2020.0
            val v = EcefPosition(   // Ω × X0 in m/yr
                k * (o.omegaYMasPerYr * x0.zM - o.omegaZMasPerYr * x0.yM),
                k * (o.omegaZMasPerYr * x0.xM - o.omegaXMasPerYr * x0.zM),
                k * (o.omegaXMasPerYr * x0.yM - o.omegaYMasPerYr * x0.xM),
            )
            val xt = EcefPosition(x0.xM + v.xM * dt, x0.yM + v.yM * dt, x0.zM + v.zM * dt)
            val fixed = f.rotation.forward(xt, t)
            assertTrue("${f.name} $p $t: ${dist(fixed, x0)} m", dist(fixed, x0) < 1e-5)   // second order |w|² r ≤ 2 µm
            val wrongSign = EcefPosition(2 * xt.xM - fixed.xM, 2 * xt.yM - fixed.yM, 2 * xt.zM - fixed.zM)
            val speed = sqrt(v.xM * v.xM + v.yM * v.yM + v.zM * v.zM)
            assertEquals(2 * speed * kotlin.math.abs(dt), dist(wrongSign, x0), 1e-5)
        }
    }

    @Test fun `a point off the frame's plate is still computed, metadata lets callers warn`() {
        val conus = GeodeticPosition(40.0, -100.0, 500.0)
        val out = PlateFrame2022.PATRF2022.rotation.forward(conus, 2026.9)
        assertTrue(out.latDeg.isFinite() && out.lonDeg.isFinite() && out.heightM.isFinite())
        assertEquals(TectonicPlate.PACIFIC, PlateFrame2022.PATRF2022.plate)
        assertTrue(PlateFrame2022.PATRF2022.plate.areaNote.contains("Hawaii"))
    }

    @Test fun `invalid inputs`() {
        val r = PlateFrame2022.NATRF2022.rotation
        assertTrue(runCatching { r.forward(EcefPosition(1.0, 2.0, 3.0), Double.NaN) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { EulerPoleParameters(Double.NaN, 0.0, 0.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { EulerPoleRotation(EulerPoleParameters(0.0, 0.0, 0.0), Double.POSITIVE_INFINITY) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ── Registry status ─────────────────────────────────────────────────────────────────────────

    private val e2026 = CoordinateEpoch(2026.9)
    private val legacy = listOf(FrameTransformationRegistry.ITRF2014_TO_NAD83_2011, FrameTransformationRegistry.ITRF2014_TO_ITRF2020)

    private fun betaNatrfEdge(): FrameEdge {
        val rot = PlateFrame2022.NATRF2022.rotation
        return FrameEdge(ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "test-only EPP2022 beta", null, "test only",
            ProductStatus.VERIFIED_BETA, PlateFrame2022.DATASET_VERSION, rot::forward, rot::inverse)
    }

    @Test fun `default registry has no 2022-frame edge and reports UNAVAILABLE`() {
        val reg = FrameTransformationRegistry.DEFAULT
        assertTrue(reg.registeredEdges.none { it.from == ReferenceFrame.NATRF2022 || it.to == ReferenceFrame.NATRF2022 })
        for (s in listOf(ReferenceFrame.ITRF2020, ReferenceFrame.ITRF2014, ReferenceFrame.NAD83_2011, ReferenceFrame.WGS84_AS_ITRF2014)) {
            val u = reg.find(s, ReferenceFrame.NATRF2022, e2026) as FrameTransformationLookup.Unavailable
            assertEquals(FrameUnavailableReason.NOT_VERIFIED, u.reason)
            assertEquals(ProductStatus.UNAVAILABLE, u.status)
        }
        for (id in listOf("patrf2022", "catrf2022", "matrf2022")) for ((s, t) in listOf("itrf2020" to id, id to "nad83-2011")) {
            val u = reg.find(s, t, e2026) as FrameTransformationLookup.Unavailable
            assertEquals(FrameUnavailableReason.NOT_VERIFIED, u.reason)
            assertEquals(s, u.source); assertEquals(t, u.target)
            assertTrue(u.explanation, u.explanation.contains(id.uppercase()))
            assertEquals(ProductStatus.UNAVAILABLE, u.status)
        }
        val unknown = reg.find("xatrf2022", "itrf2020", e2026) as FrameTransformationLookup.Unavailable
        assertEquals(FrameUnavailableReason.UNKNOWN_FRAME, unknown.reason)
        assertEquals(ProductStatus.UNAVAILABLE, unknown.status)
    }

    @Test fun `existing edges and chains report VERIFIED_LEGACY`() {
        for (e in FrameTransformationRegistry.DEFAULT.registeredEdges) {
            assertEquals(e.name, ProductStatus.VERIFIED_LEGACY, e.status); assertNull(e.datasetVersion)
        }
        val reg = FrameTransformationRegistry.DEFAULT
        for ((s, t) in listOf(ReferenceFrame.ITRF2020 to ReferenceFrame.NAD83_2011, ReferenceFrame.ITRF2014 to ReferenceFrame.ITRF2020,
                              ReferenceFrame.ITRF2020 to ReferenceFrame.ITRF2020, ReferenceFrame.ITRF2014 to ReferenceFrame.WGS84_AS_ITRF2014)) {
            val a = reg.find(s, t, e2026) as FrameTransformationLookup.Available
            assertEquals("$s→$t", ProductStatus.VERIFIED_LEGACY, a.status)
            assertEquals(ProductStatus.VERIFIED_LEGACY, a.transformation.status)
            assertTrue(a.transformation.datasetVersions.isEmpty())
        }
    }

    @Test fun `chains through a beta edge are beta`() {
        val beta = betaNatrfEdge()
        val reg = FrameTransformationRegistry(legacy + beta)
        val p = GeodeticPosition(41.3477, -76.0226, 390.0)
        val direct = reg.find(ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, e2026) as FrameTransformationLookup.Available
        assertEquals(ProductStatus.VERIFIED_BETA, direct.status)
        assertEquals(listOf(PlateFrame2022.DATASET_VERSION), direct.transformation.datasetVersions)
        assertEquals(PlateFrame2022.NATRF2022.rotation.forward(p, 2026.9), direct.apply(p))

        val fromNad = reg.find(ReferenceFrame.NAD83_2011, ReferenceFrame.NATRF2022, e2026) as FrameTransformationLookup.Available
        assertEquals("NAD83(2011) → ITRF2014 → ITRF2020 → NATRF2022", fromNad.transformation.description)
        assertEquals(ProductStatus.VERIFIED_BETA, fromNad.status)
        val viaSteps = PlateFrame2022.NATRF2022.rotation.forward(Itrf2014ToItrf2020.forward(Itrf2014ToNad83.inverse(p, 2026.9), 2026.9), 2026.9)
        assertEquals(viaSteps, fromNad.apply(p))

        val back = reg.find(ReferenceFrame.NATRF2022, ReferenceFrame.WGS84_AS_ITRF2014, e2026) as FrameTransformationLookup.Available
        assertEquals(ProductStatus.VERIFIED_BETA, back.status)
        // Legacy-only routes stay legacy in the same registry.
        assertEquals(ProductStatus.VERIFIED_LEGACY, reg.find(ReferenceFrame.ITRF2020, ReferenceFrame.NAD83_2011, e2026).status)
        // A frame listed as unavailable is refused even with a beta edge.
        val blocked = FrameTransformationRegistry(legacy + beta, FrameTransformationRegistry.DEFAULT_UNAVAILABLE)
        assertEquals(ProductStatus.UNAVAILABLE, blocked.find(ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, e2026).status)
    }

    @Test fun `legacy chains are preferred over a shorter beta edge`() {
        val id: (GeodeticPosition, Double) -> GeodeticPosition = { q, _ -> q }
        val shortcut = FrameEdge(ReferenceFrame.ITRF2020, ReferenceFrame.NAD83_2011, "test beta shortcut", null, "test",
            ProductStatus.VERIFIED_BETA, "test", id, id)
        val reg = FrameTransformationRegistry(listOf(shortcut) + legacy)
        val a = reg.find(ReferenceFrame.ITRF2020, ReferenceFrame.NAD83_2011, e2026) as FrameTransformationLookup.Available
        assertEquals(2, a.transformation.steps.size)
        assertEquals(ProductStatus.VERIFIED_LEGACY, a.status)
        // Without the legacy route, the beta edge is used and labelled.
        val only = FrameTransformationRegistry(listOf(shortcut))
        val b = only.find(ReferenceFrame.NAD83_2011, ReferenceFrame.ITRF2020, e2026) as FrameTransformationLookup.Available
        assertEquals(listOf(shortcut), b.transformation.steps)
        assertEquals(ProductStatus.VERIFIED_BETA, b.status)
    }

    @Test fun `an unverified edge cannot be constructed`() {
        val id: (GeodeticPosition, Double) -> GeodeticPosition = { q, _ -> q }
        assertTrue(runCatching {
            FrameEdge(ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "x", null, "", ProductStatus.UNAVAILABLE, null, id, id)
        }.exceptionOrNull() is IllegalArgumentException)
        // The Phase 2 constructor still works and means verified-legacy.
        assertEquals(ProductStatus.VERIFIED_LEGACY, FrameEdge(ReferenceFrame.ITRF2020, ReferenceFrame.NAD83_2011, "x", null, "", id, id).status)
    }
}
