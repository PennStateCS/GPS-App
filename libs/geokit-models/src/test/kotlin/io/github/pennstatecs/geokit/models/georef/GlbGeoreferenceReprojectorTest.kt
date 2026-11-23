package io.github.pennstatecs.geokit.models.georef

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceReprojector.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests [GlbGeoreferenceReprojector]: rewrites a GLB whose POSITION vertices are baked WGS84 degrees
 * into a local ENU metric frame recentred on the model origin. Guards the "sub-pixel sliver far from
 * origin → invisible model" bug: ordinary models must be left untouched, georeferenced models must be
 * recentred without corrupting the GLB container. GLBs are crafted minimal byte buffers with a real BIN.
 */
class GlbGeoreferenceReprojectorTest {

    /** A georeferenced model at lat≈41, lon≈-76, alt≈300 (spans ~0.004°). */
    private fun georeferencedVertices() = listOf(
        floatArrayOf(-76.002f, 298f, -41.002f),
        floatArrayOf(-75.998f, 302f, -40.998f),
        floatArrayOf(-76.000f, 300f, -41.000f),
    )

    private fun bboxCenterMagnitude(verts: List<FloatArray>): Double {
        val cx = (verts.minOf { it[0] } + verts.maxOf { it[0] }) / 2.0
        val cy = (verts.minOf { it[1] } + verts.maxOf { it[1] }) / 2.0
        val cz = (verts.minOf { it[2] } + verts.maxOf { it[2] }) / 2.0
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    private fun reprojected(bytes: ByteArray): Result.Reprojected {
        val r = GlbGeoreferenceReprojector.reproject(bytes)
        assertTrue("expected Reprojected, was $r", r is Result.Reprojected)
        return r as Result.Reprojected
    }

    private fun assertRejected(reason: Result.Rejected.Reason, bytes: ByteArray) {
        val r = GlbGeoreferenceReprojector.reproject(bytes)
        assertTrue("expected Rejected($reason), was $r", r is Result.Rejected)
        assertEquals(reason, (r as Result.Rejected).reason)
        assertNotNull(r.precision)
    }

    // ── successful reprojection + recentering ─────────────────────────────────────────────────

    @Test fun `georeferenced model is reprojected and returns the local origin`() {
        val r = reprojected(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()))
        assertEquals(-76.0, r.origin.longitudeDeg, 1e-2)
        assertEquals(41.0, r.origin.latitudeDeg, 1e-2)
        assertEquals(300.0, r.origin.heightM, 1e-1)
        assertEquals(GeoreferenceConfidence.HIGH, r.confidence)
        // float32 degrees at 41 N: about a metre of resolution.
        assertTrue(r.precision.latResM in 0.1..2.0 && r.precision.lonResM in 0.1..2.0)
    }

    @Test fun `reprojection recenters geometry near the origin (fixes far-from-origin sliver)`() {
        val verts = georeferencedVertices()
        val before = bboxCenterMagnitude(verts)
        assertTrue("precondition: original model is far from origin", before > 100.0)  // ~312

        val out = GlbTestFixtures.parse(reprojected(GlbTestFixtures.glbGeoreferenced(verts)).bytes)
        val newVerts = GlbTestFixtures.verticesOf(out.bin!!)
        assertTrue("recentred near origin (was $before)", bboxCenterMagnitude(newVerts) < 1.0)
    }

    @Test fun `reprojection does not zero out all vertices`() {
        val out = reprojected(GlbTestFixtures.glbGeoreferenced(georeferencedVertices())).bytes
        val newVerts = GlbTestFixtures.verticesOf(GlbTestFixtures.parse(out).bin!!)
        assertTrue("geometry must keep extent", newVerts.any { v -> v.any { abs(it) > 1.0f } })
    }

    @Test fun `reprojection preserves a valid GLB header and chunk structure`() {
        val out = GlbTestFixtures.parse(reprojected(GlbTestFixtures.glbGeoreferenced(georeferencedVertices())).bytes)
        assertTrue("magic/version intact", out.validHeader)
        assertTrue("total length field matches file size", out.totalLenMatches)
        assertNotNull("JSON chunk present", out.json)
        assertNotNull("BIN chunk present", out.bin)
        val acc = JSONObject(out.json!!).getJSONArray("accessors").getJSONObject(0)
        val minX = acc.getJSONArray("min").getDouble(0); val maxX = acc.getJSONArray("max").getDouble(0)
        assertTrue("accessor bbox recentred", abs((minX + maxX) / 2.0) < 1.0)
    }

    // ── no-op cases: ordinary / invalid models are not reprojected ──────────────────────────────

    @Test fun `model centered on origin (ordinary local model) is not georeferenced`() {
        val bytes = GlbTestFixtures.glbGeoreferenced(listOf(floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, 1f, 1f)))
        val before = bytes.copyOf()
        assertEquals(Result.NotGeoreferenced(Result.NotGeoreferenced.Reason.NULL_ISLAND), GlbGeoreferenceReprojector.reproject(bytes))
        assertTrue("input untouched before the origin is accepted", before.contentEquals(bytes))
    }

    @Test fun `projected (easting-northing-like) coordinates are not georeferenced`() {
        val bytes = GlbTestFixtures.glbGeoreferenced(
            listOf(floatArrayOf(414449f, 298f, -4577874f), floatArrayOf(414450f, 302f, -4577873f)))
        assertEquals(Result.NotGeoreferenced(Result.NotGeoreferenced.Reason.LONGITUDE_OUT_OF_RANGE),
            GlbGeoreferenceReprojector.reproject(bytes))
    }

    @Test fun `missing min and max is not georeferenced`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size}}]}""".trimIndent()
        assertEquals(Result.NotGeoreferenced(Result.NotGeoreferenced.Reason.NO_MIN_MAX),
            GlbGeoreferenceReprojector.reproject(GlbTestFixtures.glbWithJsonAndBin(json, bin)))
    }

    @Test fun `non-float component type is rejected`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5125,"type":"VEC3","count":2,"bufferView":0,
              "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size}}]}""".trimIndent()
        assertRejected(Result.Rejected.Reason.NOT_FLOAT, GlbTestFixtures.glbWithJsonAndBin(json, bin))
    }

    @Test fun `external buffer (uri) is rejected`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,
              "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size},"uri":"external.bin"}]}""".trimIndent()
        assertRejected(Result.Rejected.Reason.EXTERNAL_BUFFER, GlbTestFixtures.glbWithJsonAndBin(json, bin))
    }

    // ── malformed input fails safely (no throw) ───────────────────────────────────────────────

    @Test fun `empty, truncated and bad-magic bytes are not GLBs`() {
        assertEquals(Result.NotGlb, GlbGeoreferenceReprojector.reproject(ByteArray(0)))
        assertEquals(Result.NotGlb, GlbGeoreferenceReprojector.reproject(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0)))
        assertEquals(Result.NotGlb, GlbGeoreferenceReprojector.reproject(ByteArray(64) { 0x22 }))
        assertEquals(Result.NotGlb, GlbGeoreferenceReprojector.reproject(GlbTestFixtures.glbWithJson("{}")))   // no BIN
    }

    @Test fun `malformed JSON is a failure, not an exception`() {
        val r = GlbGeoreferenceReprojector.reproject(GlbTestFixtures.glbWithJsonAndBin("{ nope", ByteArray(4)))
        assertTrue(r is Result.Failed)
        assertEquals(null, r.precision)
    }

    @Test fun `only glb files within the size cap are candidates`() {
        assertTrue(GlbGeoreferenceReprojector.isCandidate("a.GLB", 10))
        assertFalse(GlbGeoreferenceReprojector.isCandidate("a.gltf", 10))
        assertFalse(GlbGeoreferenceReprojector.isCandidate("a.glb", 0))
        assertTrue(GlbGeoreferenceReprojector.isCandidate("a.glb", 128L * 1024 * 1024))
        assertFalse(GlbGeoreferenceReprojector.isCandidate("a.glb", 128L * 1024 * 1024 + 1))
    }

    // ── scale + orientation of the local frame ────────────────────────────────────────────────

    private val lat0 = 41.0
    private val alt0 = 300.0
    private val d = 1.0 / 1024.0          // exactly representable in float32

    /** North, south, east, west of (41° N, 76° W, 300 m): bbox centre = origin. */
    private fun compassVertices() = listOf(
        floatArrayOf(-76f, alt0.toFloat(), -(lat0 + d).toFloat()),
        floatArrayOf(-76f, alt0.toFloat(), -(lat0 - d).toFloat()),
        floatArrayOf((-76.0 + d).toFloat(), alt0.toFloat(), -lat0.toFloat()),
        floatArrayOf((-76.0 - d).toFloat(), alt0.toFloat(), -lat0.toFloat()),
    )

    private fun reprojectedCompass(): List<FloatArray> =
        GlbTestFixtures.verticesOf(GlbTestFixtures.parse(reprojected(GlbTestFixtures.glbGeoreferenced(compassVertices())).bytes).bin!!)

    @Test fun `distances use the WGS84 ellipsoid, not a 111,320 m per degree sphere`() {
        val v = reprojectedCompass()
        val northSouth = Math.hypot((v[0][0] - v[1][0]).toDouble(), (v[0][2] - v[1][2]).toDouble())
        val eastWest = Math.hypot((v[2][0] - v[3][0]).toDouble(), (v[2][2] - v[3][2]).toDouble())
        // Metric scale at height h is (M + h) and (N + h)·cos φ per radian: the model sits 300 m up.
        val mLat = Math.toRadians(Ellipsoid.WGS84.meridianRadiusM(lat0) + alt0)
        val mLon = Math.toRadians((Ellipsoid.WGS84.primeVerticalRadiusM(lat0) + alt0) * Math.cos(Math.toRadians(lat0)))
        assertEquals(2 * d * mLat, northSouth, 0.002)   // sphere was +0.52 m
        assertEquals(2 * d * mLon, eastWest, 0.002)     // sphere was −0.23 m
    }

    @Test fun `axis convention is east to minus X, north to plus Z, up to plus Y`() {
        val v = reprojectedCompass()
        val north = v[0]; val east = v[2]
        assertTrue("north vertex on +Z, was ${north.toList()}", north[2] > 100f)
        assertEquals(0f, north[0], 0.01f)
        assertTrue("east vertex on -X, was ${east.toList()}", east[0] < -80f)
        assertEquals(0f, east[2], 0.01f)
        assertTrue(v.all { abs(it[1]) < 0.01f })     // same height, ~0 after recentring (curvature is mm)
    }

    @Test fun `reprojected file carries the convention marker`() {
        val root = JSONObject(GlbTestFixtures.parse(reprojected(GlbTestFixtures.glbGeoreferenced(compassVertices())).bytes).json!!)
        assertEquals("wgs84-enu-north+z-v2", GlbGeoreferenceReprojector.REPROJECTION_MARKER_VALUE)
        assertEquals(GlbGeoreferenceReprojector.REPROJECTION_MARKER_VALUE,
            root.getJSONObject("asset").getJSONObject("extras").getString(GlbGeoreferenceReprojector.REPROJECTION_MARKER_KEY))
    }
}
