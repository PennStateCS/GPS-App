package io.github.pennstatecs.geokit.models.georef

import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceDetector.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [GlbGeoreferenceDetector]: the heuristic that decides whether a GLB has WGS84-like coordinates
 * baked into its POSITION vertices. GLBs are crafted minimal byte buffers.
 *
 * The detector reads the POSITION accessor's min/max from JSON only (Metashape Y-up convention:
 * X=lon, Y=height, Z=-lat). It does NOT parse EPSG/easting/northing: those layouts simply fall through
 * to "no georeference found".
 */
class GlbGeoreferenceDetectorTest {

    private fun detect(json: String) =
        GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson(json)))

    private fun detectBytes(bytes: ByteArray, suffix: String = ".glb") =
        GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(bytes, suffix))

    /** JSON for a single-primitive mesh whose POSITION accessor has the given min/max. */
    private fun meshJson(min: String, max: String) = """
        {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
         "accessors":[{"min":$min,"max":$max}]}
    """.trimIndent()

    private fun assertNot(reason: Result.NotGeoreferenced.Reason, r: Result) {
        assertTrue("expected NotGeoreferenced($reason), was $r", r is Result.NotGeoreferenced)
        assertEquals(reason, (r as Result.NotGeoreferenced).reason)
    }

    private fun assertRejected(reason: Result.Rejected.Reason, r: Result): Result.Rejected {
        assertTrue("expected Rejected($reason), was $r", r is Result.Rejected)
        assertEquals(reason, (r as Result.Rejected).reason)
        return r
    }

    // ── valid georeference ────────────────────────────────────────────────────────────────────

    @Test fun `detects valid embedded WGS84 location with high confidence`() {
        // center X=-76 (lon), Y=300 (alt), Z=-41 (-lat) → lat 41; spans 0.002 → HIGH.
        val r = detect(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]")) as Result.Found
        assertEquals(-76.0, r.origin.longitudeDeg, 1e-3)
        assertEquals(41.0, r.origin.latitudeDeg, 1e-3)
        assertEquals(300.0, r.origin.heightM, 1e-3)
        assertEquals(GeoreferenceConfidence.HIGH, r.confidence)
        assertEquals("GLB_POSITION_WGS_LIKE", r.method)
        assertEquals(0.002, r.span.spanX, 1e-9)
    }

    @Test fun `wider span yields medium confidence`() {
        // spans 0.02 (between 0.005 and 0.05) → MEDIUM.
        val r = detect(meshJson("[-76.01,299.0,-41.01]", "[-75.99,301.0,-40.99]")) as Result.Found
        assertEquals(GeoreferenceConfidence.MEDIUM, r.confidence)
    }

    @Test fun `extra unrelated metadata does not prevent detection`() {
        val json = """
            {"asset":{"generator":"Metashape","version":"2.0"},"extras":{"foo":"bar"},
             "meshes":[{"name":"scan","primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}],
             "accessors":[{"min":[-76.001,299.0,-41.001],"max":[-75.999,301.0,-40.999]},
                          {"min":[0,0,0],"max":[1,1,1]}]}
        """.trimIndent()
        assertTrue(detect(json) is Result.Found)
    }

    // ── no / missing georeference ─────────────────────────────────────────────────────────────

    @Test fun `no meshes returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_MESH, detect("""{"asset":{"version":"2.0"}}"""))

    @Test fun `missing POSITION attribute returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_POSITION,
            detect("""{"meshes":[{"primitives":[{"attributes":{"NORMAL":0}}]}],"accessors":[{}]}"""))

    @Test fun `POSITION accessor without min and max returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_MIN_MAX,
            detect("""{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"accessors":[{}]}"""))

    @Test fun `POSITION index past the accessors returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_ACCESSOR,
            detect("""{"meshes":[{"primitives":[{"attributes":{"POSITION":3}}]}],"accessors":[{}]}"""))

    @Test fun `min array shorter than 3 returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_MIN_MAX, detect(meshJson("[-76.0,300.0]", "[-75.0,301.0,-40.0]")))

    @Test fun `valid GLB but ordinary local-space model (null island) is not georeferenced`() {
        val r = detect(meshJson("[-1.0,-1.0,-1.0]", "[1.0,1.0,1.0]"))
        assertNot(Result.NotGeoreferenced.Reason.NULL_ISLAND, r)
        assertNotNull("span is reported once min/max were read", (r as Result.NotGeoreferenced).span)
    }

    // ── out-of-range / unsupported values ─────────────────────────────────────────────────────

    @Test fun `longitude out of range returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.LONGITUDE_OUT_OF_RANGE,
            detect(meshJson("[199.0,299.0,-41.001]", "[201.0,301.0,-40.999]")))

    @Test fun `latitude out of range returns no georeference`() =
        // center Z=-100 → lat 100 (>90).
        assertNot(Result.NotGeoreferenced.Reason.LATITUDE_OUT_OF_RANGE,
            detect(meshJson("[-76.001,299.0,-100.001]", "[-75.999,301.0,-99.999]")))

    @Test fun `altitude out of range returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.HEIGHT_OUT_OF_RANGE,
            detect(meshJson("[-76.001,9999.0,-41.001]", "[-75.999,10001.0,-40.999]")))

    @Test fun `span too large (not a local scan) returns no georeference`() =
        // spanX = 0.2 > 0.05.
        assertNot(Result.NotGeoreferenced.Reason.SPAN_TOO_LARGE,
            detect(meshJson("[-76.1,299.0,-41.001]", "[-75.9,301.0,-40.999]")))

    @Test fun `projected easting-northing-like coordinates are not treated as WGS84`() =
        // UTM-like magnitudes in X/Z → far outside lon/lat ranges → no false positive.
        assertNot(Result.NotGeoreferenced.Reason.LONGITUDE_OUT_OF_RANGE,
            detect(meshJson("[414449.0,299.0,-4577874.0]", "[414450.0,301.0,-4577873.0]")))

    // ── malformed input must fail safely (no crash) ───────────────────────────────────────────

    @Test fun `malformed numeric min (string not array) returns no georeference`() =
        assertNot(Result.NotGeoreferenced.Reason.NO_MIN_MAX,
            detect("""{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"accessors":[{"min":"oops","max":[1,1,1]}]}"""))

    @Test fun `non-numeric min values fail without throwing`() {
        val r = assertRejected(Result.Rejected.Reason.FAILED, detect(meshJson("""["abc",0,0]""", "[1,1,1]")))
        assertNotNull(r.cause)
    }

    @Test fun `malformed JSON chunk is a parse error`() {
        val r = assertRejected(Result.Rejected.Reason.JSON_PARSE_ERROR, detectBytes(GlbTestFixtures.glbWithJson("{ not valid json")))
        assertNotNull(r.cause)
    }

    @Test fun `empty file is truncated`() {
        assertRejected(Result.Rejected.Reason.TRUNCATED, detectBytes(ByteArray(0)))
    }

    @Test fun `truncated bytes (smaller than header) are truncated`() {
        assertRejected(Result.Rejected.Reason.TRUNCATED, detectBytes(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0)))
    }

    @Test fun `JSON chunk longer than the file is truncated`() {
        val bytes = GlbTestFixtures.glbWithJson(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]"))
        assertRejected(Result.Rejected.Reason.TRUNCATED, detectBytes(bytes.copyOf(30)))
    }

    @Test fun `unsupported version, non-JSON first chunk and bad chunk length are rejected with the value`() {
        val good = GlbTestFixtures.glbWithJson(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]"))
        val v3 = good.copyOf().also { it[4] = 3 }
        assertEquals(3, assertRejected(Result.Rejected.Reason.UNSUPPORTED_VERSION, detectBytes(v3)).value)
        val bin = good.copyOf().also { it[16] = 0x42; it[17] = 0x49; it[18] = 0x4E; it[19] = 0 }
        assertEquals(0x004E4942, assertRejected(Result.Rejected.Reason.FIRST_CHUNK_NOT_JSON, detectBytes(bin)).value)
        val zero = good.copyOf().also { it[12] = 0; it[13] = 0; it[14] = 0; it[15] = 0 }
        assertEquals(0, assertRejected(Result.Rejected.Reason.JSON_CHUNK_LENGTH, detectBytes(zero)).value)
    }

    @Test fun `bad magic is not a GLB`() {
        val r = detectBytes(ByteArray(32) { 0x11 })
        assertEquals(Result.NotGlb(Result.NotGlb.Reason.MAGIC, 0x11111111), r)
    }

    @Test fun `non-glb extension is skipped`() =
        assertEquals(Result.NotGlb(Result.NotGlb.Reason.EXTENSION),
            detectBytes(GlbTestFixtures.glbWithJson(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]")), suffix = ".gltf"))

    @Test fun `missing file is unreadable`() =
        assertEquals(Result.NotGlb(Result.NotGlb.Reason.UNREADABLE),
            GlbGeoreferenceDetector.detect(java.io.File("does-not-exist-${System.nanoTime()}.glb")))
}
