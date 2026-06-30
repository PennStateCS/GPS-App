package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests [GlbGeoreferenceReprojector] — rewrites a GLB whose POSITION vertices are baked WGS84
 * degrees into a local ENU metric frame recentred on the model origin. Directly guards the
 * "sub-pixel sliver far from origin → invisible model" bug: ordinary models must be left untouched,
 * georeferenced models must be recentred without corrupting the GLB container.
 *
 * Robolectric provides real org.json + Log; GLBs are crafted minimal byte buffers with a real BIN
 * chunk (no Filament/GL/real models).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    // ── successful reprojection + recentering ─────────────────────────────────────────────────

    @Test fun `georeferenced model is reprojected and returns the local origin`() {
        val verts = georeferencedVertices()
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(verts))

        val origin = GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)
        assertNotNull("a plausibly georeferenced GLB must reproject", origin)
        assertEquals(-76.0, origin!!.longitude, 1e-2)
        assertEquals(41.0, origin.latitude, 1e-2)
        assertEquals(300.0, origin.altitudeMeters!!, 1e-1)
    }

    @Test fun `reprojection recenters geometry near the origin (fixes far-from-origin sliver)`() {
        val verts = georeferencedVertices()
        val before = bboxCenterMagnitude(verts)
        assertTrue("precondition: original model is far from origin", before > 100.0)  // ~312

        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(verts))
        GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)

        val out = GlbTestFixtures.parse(file.readBytes())
        val newVerts = GlbTestFixtures.verticesOf(out.bin!!)
        // The model's bounding-box center is now essentially at the origin.
        assertTrue("recentred near origin (was $before)", bboxCenterMagnitude(newVerts) < 1.0)
    }

    @Test fun `reprojection does not zero out all vertices`() {
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()))
        GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)

        val newVerts = GlbTestFixtures.verticesOf(GlbTestFixtures.parse(file.readBytes()).bin!!)
        // The recentred corners must retain real (metres) extent — not collapse to a point.
        assertTrue("geometry must keep extent", newVerts.any { v -> v.any { abs(it) > 1.0f } })
    }

    @Test fun `reprojection preserves a valid GLB header and chunk structure`() {
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()))
        GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)

        val out = GlbTestFixtures.parse(file.readBytes())
        assertTrue("magic/version intact", out.validHeader)
        assertTrue("total length field matches file size", out.totalLenMatches)
        assertNotNull("JSON chunk present", out.json)
        assertNotNull("BIN chunk present", out.bin)
        // JSON must still parse and the accessor min/max must now be recentred (near 0).
        val root = org.json.JSONObject(out.json!!)
        val acc = root.getJSONArray("accessors").getJSONObject(0)
        val minX = acc.getJSONArray("min").getDouble(0); val maxX = acc.getJSONArray("max").getDouble(0)
        assertTrue("accessor bbox recentred", abs((minX + maxX) / 2.0) < 1.0)
    }

    // ── no-op cases: ordinary / invalid models must be left untouched ──────────────────────────

    @Test fun `model centered on origin (ordinary local model) is left unchanged`() {
        val bytes = GlbTestFixtures.glbGeoreferenced(listOf(floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, 1f, 1f)))
        val file = GlbTestFixtures.tempGlb(bytes)
        val before = file.readBytes()

        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))  // null = no reprojection
        assertTrue("file must be untouched for ordinary models", before.contentEquals(file.readBytes()))
    }

    @Test fun `projected (easting-northing-like) coordinates are a safe no-op`() {
        val bytes = GlbTestFixtures.glbGeoreferenced(
            listOf(floatArrayOf(414449f, 298f, -4577874f), floatArrayOf(414450f, 302f, -4577873f)))
        val file = GlbTestFixtures.tempGlb(bytes)
        val before = file.readBytes()

        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
        assertTrue("unsupported coords must not corrupt the file", before.contentEquals(file.readBytes()))
    }

    @Test fun `missing min and max is a safe no-op`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size}}]}""".trimIndent()
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJsonAndBin(json, bin))
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
    }

    @Test fun `non-float component type is a safe no-op`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        // componentType 5125 (UNSIGNED_INT) instead of 5126 (FLOAT).
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5125,"type":"VEC3","count":2,"bufferView":0,
              "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size}}]}""".trimIndent()
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJsonAndBin(json, bin))
        val before = file.readBytes()
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test fun `external buffer (uri) is a safe no-op`() {
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,
              "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size},"uri":"external.bin"}]}""".trimIndent()
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJsonAndBin(json, bin))
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
    }

    // ── malformed input must fail safely (no crash) ───────────────────────────────────────────

    @Test fun `empty file is a safe no-op`() =
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(GlbTestFixtures.tempGlb(ByteArray(0))))

    @Test fun `truncated bytes do not crash`() =
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(GlbTestFixtures.tempGlb(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0))))

    @Test fun `bad magic is a safe no-op`() =
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(GlbTestFixtures.tempGlb(ByteArray(64) { 0x22 })))

    @Test fun `non-glb extension is skipped`() =
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(
            GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()), suffix = ".gltf")))
}
