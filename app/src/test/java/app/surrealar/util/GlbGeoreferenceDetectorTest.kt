package app.surrealar.util

import app.surrealar.domain.model.ModelLocationConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [GlbGeoreferenceDetector] — the heuristic that decides whether a GLB has WGS84-like
 * coordinates baked into its POSITION vertices. Getting this wrong is part of the "labels show but
 * models don't" class of bug. Robolectric provides real org.json + android.util.Log; GLBs are
 * crafted minimal byte buffers (no Filament/GL/real models).
 *
 * The detector reads the POSITION accessor's min/max from JSON only (Metashape Y-up convention:
 * X=lon, Y=alt, Z=-lat). It does NOT parse EPSG/easting/northing — those layouts simply fall through
 * to "no georeference found".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlbGeoreferenceDetectorTest {

    private fun detect(json: String) =
        GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson(json)))

    /** JSON for a single-primitive mesh whose POSITION accessor has the given min/max. */
    private fun meshJson(min: String, max: String) = """
        {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
         "accessors":[{"min":$min,"max":$max}]}
    """.trimIndent()

    // ── valid georeference ────────────────────────────────────────────────────────────────────

    @Test fun `detects valid embedded WGS84 location with high confidence`() {
        // center X=-76 (lon), Y=300 (alt), Z=-41 (-lat) → lat 41; spans 0.002 → HIGH.
        val loc = detect(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]"))
        assertNotNull(loc)
        assertEquals(-76.0, loc!!.longitude, 1e-3)
        assertEquals(41.0, loc.latitude, 1e-3)
        assertEquals(300.0, loc.altitudeMeters!!, 1e-3)
        assertEquals(ModelLocationConfidence.HIGH, loc.confidence)
        assertEquals("GLB_POSITION_WGS_LIKE", loc.source)
    }

    @Test fun `wider span yields medium confidence`() {
        // spans 0.02 (between 0.005 and 0.05) → MEDIUM.
        val loc = detect(meshJson("[-76.01,299.0,-41.01]", "[-75.99,301.0,-40.99]"))
        assertNotNull(loc)
        assertEquals(ModelLocationConfidence.MEDIUM, loc!!.confidence)
    }

    @Test fun `extra unrelated metadata does not prevent detection`() {
        val json = """
            {"asset":{"generator":"Metashape","version":"2.0"},"extras":{"foo":"bar"},
             "meshes":[{"name":"scan","primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}],
             "accessors":[{"min":[-76.001,299.0,-41.001],"max":[-75.999,301.0,-40.999]},
                          {"min":[0,0,0],"max":[1,1,1]}]}
        """.trimIndent()
        assertNotNull(detect(json))
    }

    // ── no / missing georeference ─────────────────────────────────────────────────────────────

    @Test fun `no meshes returns no georeference`() = assertNull(detect("""{"asset":{"version":"2.0"}}"""))

    @Test fun `missing POSITION attribute returns no georeference`() =
        assertNull(detect("""{"meshes":[{"primitives":[{"attributes":{"NORMAL":0}}]}],"accessors":[{}]}"""))

    @Test fun `POSITION accessor without min and max returns no georeference`() =
        assertNull(detect("""{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"accessors":[{}]}"""))

    @Test fun `min array shorter than 3 returns no georeference`() =
        assertNull(detect(meshJson("[-76.0,300.0]", "[-75.0,301.0,-40.0]")))

    @Test fun `valid GLB but ordinary local-space model (null island) is not georeferenced`() {
        // Local model centred on origin → (0,0) null-island guard rejects it.
        assertNull(detect(meshJson("[-1.0,-1.0,-1.0]", "[1.0,1.0,1.0]")))
    }

    // ── out-of-range / unsupported values ─────────────────────────────────────────────────────

    @Test fun `longitude out of range returns no georeference`() =
        assertNull(detect(meshJson("[199.0,299.0,-41.001]", "[201.0,301.0,-40.999]")))

    @Test fun `latitude out of range returns no georeference`() =
        // center Z=-100 → lat 100 (>90).
        assertNull(detect(meshJson("[-76.001,299.0,-100.001]", "[-75.999,301.0,-99.999]")))

    @Test fun `altitude out of range returns no georeference`() =
        assertNull(detect(meshJson("[-76.001,9999.0,-41.001]", "[-75.999,10001.0,-40.999]")))

    @Test fun `span too large (not a local scan) returns no georeference`() =
        // spanX = 0.2 > 0.05.
        assertNull(detect(meshJson("[-76.1,299.0,-41.001]", "[-75.9,301.0,-40.999]")))

    @Test fun `projected easting-northing-like coordinates are not treated as WGS84`() =
        // UTM-like magnitudes in X/Z → far outside lon/lat ranges → no false positive.
        assertNull(detect(meshJson("[414449.0,299.0,-4577874.0]", "[414450.0,301.0,-4577873.0]")))

    // ── malformed input must fail safely (no crash) ───────────────────────────────────────────

    @Test fun `malformed numeric min (string not array) returns null`() =
        assertNull(detect("""{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"accessors":[{"min":"oops","max":[1,1,1]}]}"""))

    @Test fun `non-numeric min values do not crash`() =
        assertNull(detect(meshJson("""["abc",0,0]""", "[1,1,1]")))

    @Test fun `malformed JSON chunk returns null`() =
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson("{ not valid json"))))

    @Test fun `empty file returns null`() =
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(ByteArray(0))))

    @Test fun `truncated bytes (smaller than header) returns null`() =
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0))))

    @Test fun `bad magic returns null`() =
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(ByteArray(32) { 0x11 })))

    @Test fun `non-glb extension is skipped`() =
        assertNull(GlbGeoreferenceDetector.detect(
            GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]")), suffix = ".gltf")))
}
