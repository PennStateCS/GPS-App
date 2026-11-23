package app.surrealar.util

import app.surrealar.domain.model.ModelLocationConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app wrapper around geokit-models' GLB georeference detector: maps its result to
 * EmbeddedModelLocation and writes the diagnostics lines (never coordinates). The heuristic itself is
 * tested in libs/geokit-models (GlbGeoreferenceDetectorTest). Robolectric for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlbGeoreferenceDetectorTest {

    private fun detect(json: String) =
        GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson(json)))

    private fun meshJson(min: String, max: String) = """
        {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
         "accessors":[{"min":$min,"max":$max}]}
    """.trimIndent()

    @Test fun `maps a detected location to the domain model`() {
        val loc = detect(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]"))
        assertNotNull(loc)
        assertEquals(-76.0, loc!!.longitude, 1e-3)
        assertEquals(41.0, loc.latitude, 1e-3)
        assertEquals(300.0, loc.altitudeMeters!!, 1e-3)
        assertEquals(ModelLocationConfidence.HIGH, loc.confidence)
        assertEquals("GLB_POSITION_WGS_LIKE", loc.source)
    }

    @Test fun `wider span maps to medium confidence`() {
        assertEquals(ModelLocationConfidence.MEDIUM, detect(meshJson("[-76.01,299.0,-41.01]", "[-75.99,301.0,-40.99]"))!!.confidence)
    }

    @Test fun `anything but a found location is null`() {
        assertNull(detect(meshJson("[-1.0,-1.0,-1.0]", "[1.0,1.0,1.0]")))
        assertNull(detect("""{"asset":{"version":"2.0"}}"""))
        assertNull(detect(meshJson("""["abc",0,0]""", "[1,1,1]")))
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson("{ not valid json"))))
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(ByteArray(0))))
        assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(ByteArray(32) { 0x11 })))
        assertNull(GlbGeoreferenceDetector.detect(
            GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]")), suffix = ".gltf")))
        assertNull(GlbGeoreferenceDetector.detect(java.io.File("does-not-exist-${System.nanoTime()}.glb")))
    }

    @Test fun `detection log lines keep their text and have no coordinates`() {
        val dir = java.nio.file.Files.createTempDirectory("diaglog").toFile()
        DiagnosticsLogger.initForTest(dir)
        try {
            assertNotNull(detect(meshJson("[-76.001,299.0,-41.001]", "[-75.999,301.0,-40.999]")))
            assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(GlbTestFixtures.glbWithJson("{ not valid json"))))
            assertNull(detect(meshJson("""["abc",0,0]""", "[1,1,1]")))
            val notJson = GlbTestFixtures.glbWithJson("{}").also { it[16] = 0x42; it[17] = 0x49; it[18] = 0x4E; it[19] = 0 }
            assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(notJson)))
            val zeroLen = GlbTestFixtures.glbWithJson("{}").also { it[12] = 0; it[13] = 0; it[14] = 0; it[15] = 0 }
            assertNull(GlbGeoreferenceDetector.detect(GlbTestFixtures.tempGlb(zeroLen)))
            DiagnosticsLogger.flush()
            val log = java.io.File(dir, "app-log-current.txt").readText()
            assertTrue(log.contains("georeferenced=true"))
            assertTrue(log.contains("Model georeference detected file="))
            assertTrue(log.contains("method=GLB_POSITION_WGS_LIKE confidence=HIGH"))
            assertTrue(log.contains("Model georeference detection failed: JSON parse error file="))
            assertTrue(log.contains("Model georeference detection failed file="))
            assertTrue(log.contains("Model georeference detection skipped: first chunk is not JSON file="))
            assertTrue(log.contains("chunkType=0x4e4942"))
            assertTrue(log.contains("Model georeference detection skipped: JSON chunk length out of safe range file="))
            assertTrue(log.contains("chunkBytes=0"))
            // No longitude (-76…) or lat=/lon=/alt= fields (a timestamp could contain "41.0", so not checked).
            assertFalse(log.contains("-76") || log.contains("lat=") || log.contains("lon=") || log.contains("alt="))
        } finally {
            dir.deleteRecursively()
        }
    }
}
