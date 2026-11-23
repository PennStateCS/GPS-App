package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app wrapper around geokit-models' GLB georeference reprojector: rewrites the file in place,
 * leaves ordinary models untouched, maps the origin to EmbeddedModelLocation. The rewrite itself is
 * tested in libs/geokit-models (GlbGeoreferenceReprojectorTest, GlbGeoreferenceGoldenTest); this test
 * also checks that the output lands right after the AR renderer's model correction.
 * Robolectric for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlbGeoreferenceReprojectorTest {

    private fun georeferencedVertices() = listOf(
        floatArrayOf(-76.002f, 298f, -41.002f),
        floatArrayOf(-75.998f, 302f, -40.998f),
        floatArrayOf(-76.000f, 300f, -41.000f),
    )

    @Test fun `georeferenced model is rewritten in place and returns the local origin`() {
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()))
        val before = file.readBytes()

        val origin = GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)
        assertNotNull("a plausibly georeferenced GLB must reproject", origin)
        assertEquals(-76.0, origin!!.longitude, 1e-2)
        assertEquals(41.0, origin.latitude, 1e-2)
        assertEquals(300.0, origin.altitudeMeters!!, 1e-1)
        assertEquals("GLB_POSITION_WGS_LIKE", origin.source)
        assertTrue("file rewritten", !before.contentEquals(file.readBytes()))
        assertTrue(GlbTestFixtures.parse(file.readBytes()).totalLenMatches)
    }

    @Test fun `ordinary, unsupported and malformed models are left untouched`() {
        val ordinary = GlbTestFixtures.glbGeoreferenced(listOf(floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, 1f, 1f)))
        val bin = GlbTestFixtures.floatBin(listOf(floatArrayOf(-76.002f, 298f, -41.002f), floatArrayOf(-75.998f, 302f, -40.998f)))
        val nonFloat = GlbTestFixtures.glbWithJsonAndBin("""{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5125,"type":"VEC3","count":2,"bufferView":0,
              "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size}}]}""", bin)
        for (bytes in listOf(ordinary, nonFloat, ByteArray(0), byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0), ByteArray(64) { 0x22 })) {
            val file = GlbTestFixtures.tempGlb(bytes)
            assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
            assertTrue("file must be untouched", bytes.contentEquals(file.readBytes()))
        }
    }

    @Test fun `non-glb extension and missing file are skipped`() {
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(
            GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(georeferencedVertices()), suffix = ".gltf")))
        assertNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(java.io.File("does-not-exist-${System.nanoTime()}.glb")))
    }

    // ── orientation after the AR renderer's correction ────────────────────────────────────────

    private val lat0 = 41.0
    private val alt0 = 300.0
    private val d = 1.0 / 1024.0          // exactly representable in float32

    /** North, south, east, west of (41° N, 76° W, 300 m) — bbox centre = origin. */
    private fun compassVertices() = listOf(
        floatArrayOf(-76f, alt0.toFloat(), -(lat0 + d).toFloat()),
        floatArrayOf(-76f, alt0.toFloat(), -(lat0 - d).toFloat()),
        floatArrayOf((-76.0 + d).toFloat(), alt0.toFloat(), -lat0.toFloat()),
        floatArrayOf((-76.0 - d).toFloat(), alt0.toFloat(), -lat0.toFloat()),
    )

    private fun reprojectedCompass(): List<FloatArray> {
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(compassVertices()))
        assertNotNull(GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file))
        return GlbTestFixtures.verticesOf(GlbTestFixtures.parse(file.readBytes()).bin!!)
    }

    @Test fun `after the renderer's correction, north is world north and east is world east`() {
        val v = reprojectedCompass()
        val cm = io.github.pennstatecs.geokit.ar.model.ModelCorrectionMatrix.base(1f, 0f, 0f, 0f)
        val north = io.github.pennstatecs.geokit.ar.model.ModelCorrectionMatrix.transformPoint(cm, v[0][0], v[0][1], v[0][2])
        val east = io.github.pennstatecs.geokit.ar.model.ModelCorrectionMatrix.transformPoint(cm, v[2][0], v[2][1], v[2][2])
        // ARCore Earth anchors are east-up-south: north = −Z, east = +X.
        assertTrue("north vertex must land north (z < 0), was ${north.toList()}", north[2] < -100f)
        assertEquals(0f, north[0], 0.01f)
        assertTrue("east vertex must land east (x > 0), was ${east.toList()}", east[0] > 80f)
        assertEquals(0f, east[2], 0.01f)
        // Handedness preserved (no mirror): east × north = up (+Y) in a right-handed EUS frame.
        val crossY = east[2] * north[0] - east[0] * north[2]
        assertTrue("east × north must point up", crossY > 0f)
    }

    @Test fun `reprojected file carries the convention marker`() {
        val file = GlbTestFixtures.tempGlb(GlbTestFixtures.glbGeoreferenced(compassVertices()))
        GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)
        val root = org.json.JSONObject(GlbTestFixtures.parse(file.readBytes()).json!!)
        assertEquals("wgs84-enu-north+z-v2", GlbGeoreferenceReprojector.REPROJECTION_MARKER_VALUE)
        assertEquals(GlbGeoreferenceReprojector.REPROJECTION_MARKER_VALUE,
            root.getJSONObject("asset").getJSONObject("extras").getString(GlbGeoreferenceReprojector.REPROJECTION_MARKER_KEY))
    }
}
