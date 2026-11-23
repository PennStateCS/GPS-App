package io.github.pennstatecs.geokit.smoke

import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceDetector
import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceReprojector
import io.github.pennstatecs.geokit.models.gltf.GltfFactsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * geokit-models from a plain JVM with only its declared runtime dependencies (this suite depends on
 * nothing else). Every test goes through org.json; without `runtimeOnly(org.json:json)` in
 * geokit-models they fail with NoClassDefFoundError (GltfFactsReader: a parseError naming it).
 */
class ModelsJvmSmokeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `org json on the runtime classpath is org json json, not android-json`() {
        val source = Class.forName("org.json.JSONObject").protectionDomain.codeSource.location.toString()
        assertTrue("org.json comes from $source", source.contains("json-20240303"))
        assertFalse(source.contains("android-json"))
    }

    /** A georeferenced triangle near 41 N 76 W (Metashape Y-up: X = lon, Y = height, Z = −lat). */
    private fun georeferencedGlb(): ByteArray {
        val bin = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(-76.002f, 298f, -41.002f, -75.998f, 302f, -40.998f, -76.000f, 300f, -41.000f).forEach { bin.putFloat(it) }
        var json = ("""{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],""" +
            """"accessors":[{"componentType":5126,"type":"VEC3","count":3,"bufferView":0,""" +
            """"min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":36}],"buffers":[{"byteLength":36}]}""").toByteArray()
        while (json.size % 4 != 0) json += ' '.code.toByte()
        val total = 12 + 8 + json.size + 8 + 36
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(json.size); putInt(0x4E4F534A); put(json)
            putInt(36); putInt(0x004E4942); put(bin.array())
        }.array()
    }

    private fun write(name: String, bytes: ByteArray): File = File(tmp.root, name).also { it.writeBytes(bytes) }

    @Test fun `GltfFactsReader reads a GLB`() {
        val facts = GltfFactsReader.read(write("tri.glb", georeferencedGlb()))
        assertNull("parseError: ${facts.parseError}", facts.parseError)
        assertEquals(1L, facts.triangleCount)
        assertEquals(3L, facts.vertexCount)
        assertNotNull(facts.bounds)
    }

    @Test fun `detector finds the embedded location`() {
        val r = GlbGeoreferenceDetector.detect(write("tri.glb", georeferencedGlb()))
        assertTrue("$r", r is GlbGeoreferenceDetector.Result.Found)
        assertEquals(41.0, (r as GlbGeoreferenceDetector.Result.Found).origin.latitudeDeg, 1e-6)
    }

    @Test fun `reprojector rewrites the GLB and the result reads back`() {
        val r = GlbGeoreferenceReprojector.reproject(georeferencedGlb())
        assertTrue("$r", r is GlbGeoreferenceReprojector.Result.Reprojected)
        val out = write("out.glb", (r as GlbGeoreferenceReprojector.Result.Reprojected).bytes)
        assertTrue(String(out.readBytes(), Charsets.UTF_8).contains(GlbGeoreferenceReprojector.REPROJECTION_MARKER_VALUE))
        assertNull(GltfFactsReader.read(out).parseError)
        assertTrue(GlbGeoreferenceDetector.detect(out) is GlbGeoreferenceDetector.Result.NotGeoreferenced)
    }
}
