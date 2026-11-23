package io.github.pennstatecs.geokit.models.gltf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfFactsReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val eps = 1e-4

    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("models/$name") ?: error("missing fixture $name")
        return File(url.toURI())
    }

    private fun assertBounds(b: GltfBounds?, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) {
        assertNotNull("bounds", b)
        assertEquals(minX, b!!.minX, eps); assertEquals(minY, b.minY, eps); assertEquals(minZ, b.minZ, eps)
        assertEquals(maxX, b.maxX, eps); assertEquals(maxY, b.maxY, eps); assertEquals(maxZ, b.maxZ, eps)
    }

    // ------------------------------------------------------------ builders

    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    private fun glb(json: String, bin: ByteArray? = null, version: Int = 2): ByteArray {
        var jb = json.toByteArray(Charsets.UTF_8)
        while (jb.size % 4 != 0) jb += ' '.code.toByte()
        var bb = bin ?: ByteArray(0)
        while (bb.size % 4 != 0) bb += 0
        val total = 12 + 8 + jb.size + (if (bin != null) 8 + bb.size else 0)
        var out = "glTF".toByteArray() + le(version) + le(total) + le(jb.size) + "JSON".toByteArray() + jb
        if (bin != null) out += le(bb.size) + byteArrayOf('B'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 0) + bb
        return out
    }

    /** PNG signature + IHDR chunk header with the given size (enough for the reader). */
    private fun pngHeader(w: Int, h: Int): ByteArray {
        val bb = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
        bb.put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        bb.putInt(13); bb.put("IHDR".toByteArray()); bb.putInt(w); bb.putInt(h)
        bb.put(byteArrayOf(8, 6, 0, 0, 0)); bb.putInt(0)
        return bb.array()
    }

    /** SOI + APP0 (JFIF) + SOF0 with the given size. */
    private fun jpegHeader(w: Int, h: Int): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3) +
            ByteArray(9)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof
    }

    private fun write(name: String, bytes: ByteArray): File = File(tmp.root, name).also { it.writeBytes(bytes) }
    private fun write(name: String, text: String): File = File(tmp.root, name).also { it.writeText(text) }

    // ------------------------------------------------------------ the 1 m test cube

    @Test
    fun `test cube fixture reads as a 1 m textured cube`() {
        val f = GltfFactsReader.read(fixture("surreal-test-cube-1m.glb"))
        assertNull(f.parseError)
        assertEquals(122276L, f.fileSizeBytes)
        assertBounds(f.bounds, -0.5, 0.0, -0.5, 0.5, 1.0, 0.5)
        assertEquals(12L, f.triangleCount)
        assertEquals(24L, f.vertexCount)
        assertEquals(1, f.meshCount)
        assertEquals(1, f.textureCount)
        assertEquals(2048, f.maxTextureSidePx)
        assertEquals(2048L * 1024 * 4 * 4 / 3, f.estimatedTextureBytes)
        assertTrue(f.extensionsRequired.isEmpty())
        assertTrue(f.extensionsUsed.isEmpty())
        assertTrue(f.missingResources.isEmpty())
    }

    // ------------------------------------------------------------ synthetic files

    private val quadAccessors = """
        "accessors":[{"componentType":5126,"count":4,"type":"VEC3","min":[-1,0,-1],"max":[1,1,1]},
                     {"componentType":5123,"count":6,"type":"SCALAR"}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]
    """.trimIndent()

    @Test
    fun `node translation and scale are applied and instanced meshes count per node`() {
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
            "nodes":[{"mesh":0,"translation":[10,0,5],"scale":[2,2,2]},
                     {"mesh":0,"matrix":[1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-20,1]}],
            $quadAccessors,
            "images":[{"bufferView":0,"mimeType":"image/png"}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":33}],
            "buffers":[{"byteLength":36}]}"""
        val f = GltfFactsReader.read(write("instanced.glb", glb(json, pngHeader(300, 200))))
        assertNull(f.parseError)
        // node 0: x 8..12, y 0..2, z 3..7; node 1: x -1..1, y 0..1, z -21..-19
        assertBounds(f.bounds, -1.0, 0.0, -21.0, 12.0, 2.0, 7.0)
        assertEquals(4L, f.triangleCount)
        assertEquals(8L, f.vertexCount)
        assertEquals(1, f.meshCount)
        assertEquals(1, f.textureCount)
        assertEquals(300, f.maxTextureSidePx)
        assertEquals(300L * 200 * 4 * 4 / 3, f.estimatedTextureBytes)
    }

    @Test
    fun `parent rotation is applied to child geometry`() {
        // 90 degrees about Z: (x, y) -> (-y, x)
        val s = Math.sqrt(0.5)
        val json = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],
            "nodes":[{"rotation":[0,0,$s,$s],"children":[1]},{"mesh":0}],
            "accessors":[{"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,2,3]}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}"""
        val f = GltfFactsReader.read(write("rotated.glb", glb(json)))
        assertNull(f.parseError)
        assertBounds(f.bounds, -2.0, 0.0, 0.0, 0.0, 1.0, 3.0)
        assertEquals(1L, f.triangleCount)   // non-indexed: 3 positions / 3
        assertEquals(0, f.textureCount)
        assertEquals(0, f.maxTextureSidePx)
    }

    @Test
    fun `gltf with missing buffer, escaping uri, external jpeg and unknown required extension`() {
        write("tex.jpg", jpegHeader(640, 480))
        val json = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
            $quadAccessors,
            "extensionsUsed":["EXT_made_up","KHR_materials_unlit"],
            "extensionsRequired":["EXT_made_up"],
            "images":[{"uri":"tex.jpg"},{"uri":"gone.png"}],
            "buffers":[{"uri":"geo.bin","byteLength":100},{"uri":"../escape.bin","byteLength":4}]}"""
        val f = GltfFactsReader.read(write("scene.gltf", json))
        assertNull(f.parseError)
        assertBounds(f.bounds, -1.0, 0.0, -1.0, 1.0, 1.0, 1.0)
        assertEquals(listOf("EXT_made_up"), f.extensionsRequired)
        assertEquals(listOf("EXT_made_up", "KHR_materials_unlit"), f.extensionsUsed)
        assertEquals(listOf("geo.bin", "../escape.bin (outside the model's folder)", "gone.png"), f.missingResources)
        assertEquals(2, f.textureCount)
        assertEquals(640, f.maxTextureSidePx)       // the missing image is unknown, not zero
    }

    @Test
    fun `data uri image size is read`() {
        val b64 = Base64.getEncoder().encodeToString(pngHeader(4096, 512))
        val json = """{"asset":{"version":"2.0"},"images":[{"uri":"data:image/png;base64,$b64"}]}"""
        val f = GltfFactsReader.read(write("datauri.gltf", json))
        assertNull(f.parseError)
        assertNull(f.bounds)
        assertEquals(4096, f.maxTextureSidePx)
    }

    // ------------------------------------------------------------ failures never throw

    @Test
    fun `corrupt and unsupported files set parseError without throwing`() {
        val cases = mapOf(
            "garbage.glb" to ByteArray(64) { (it * 37).toByte() },
            "empty.glb" to ByteArray(0),
            "v1.glb" to glb("""{"asset":{"version":"1.0"}}""", version = 1),
            "truncated.glb" to glb("""{"asset":{"version":"2.0"}}""").copyOf(24),
            "badjson.glb" to glb("""{"asset":{"version":"2.0"""),
            "badjson.gltf" to "{ not json".toByteArray(),
        )
        for ((name, bytes) in cases) {
            val f = GltfFactsReader.read(write(name, bytes))
            assertNotNull("$name should fail", f.parseError)
            assertNull(f.bounds)
        }
        assertNotNull(GltfFactsReader.read(File(tmp.root, "does-not-exist.glb")).parseError)
    }

    private fun setInt(b: ByteArray, at: Int, v: Int) = b.also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, v) }

    @Test
    fun `malformed GLB headers and chunk lengths set parseError`() {
        val good = glb("""{"asset":{"version":"2.0"}}""", ByteArray(8))
        val jsonLen = GlbContainerLen.of(good)
        val binLenAt = 20 + jsonLen
        val cases = mapOf(
            "declared-too-long.glb" to setInt(good.copyOf(), 8, good.size + 4),
            "declared-negative.glb" to setInt(good.copyOf(), 8, -1),
            "declared-tiny.glb" to setInt(good.copyOf(), 8, 8),
            "json-past-declared.glb" to setInt(good.copyOf(), 8, 24),
            "json-negative.glb" to setInt(good.copyOf(), 12, -4),
            "json-huge.glb" to setInt(good.copyOf(), 12, Int.MAX_VALUE),
            "bin-past-end.glb" to setInt(good.copyOf(), binLenAt, 64),
            "bin-negative.glb" to setInt(good.copyOf(), binLenAt, -8),
        )
        for ((name, bytes) in cases) {
            val f = GltfFactsReader.read(write(name, bytes))
            assertNotNull("$name should fail", f.parseError)
        }
    }

    @Test
    fun `bufferView outside the BIN chunk or with huge offsets is not read`() {
        for (bv in listOf(
            """{"buffer":0,"byteOffset":0,"byteLength":64}""",                    // longer than BIN
            """{"buffer":0,"byteOffset":32,"byteLength":4}""",                    // starts past BIN
            """{"buffer":0,"byteOffset":-4,"byteLength":4}""",
            """{"buffer":0,"byteOffset":9223372036854775807,"byteLength":33}""",
            """{"buffer":0,"byteOffset":4,"byteLength":9223372036854775807}""",
        )) {
            val json = """{"asset":{"version":"2.0"},"images":[{"bufferView":0,"mimeType":"image/png"}],
                "bufferViews":[$bv],"buffers":[{"byteLength":36}]}"""
            val f = GltfFactsReader.read(write("img.glb", glb(json, pngHeader(300, 200))))
            assertNull("$bv: ${f.parseError}", f.parseError)
            assertNull("$bv must not be sized", f.maxTextureSidePx)
        }
        // A data: URI buffer with an offset near Long.MAX_VALUE does not overflow.
        val b64 = Base64.getEncoder().encodeToString(pngHeader(10, 10))
        val json = """{"asset":{"version":"2.0"},"images":[{"bufferView":0}],
            "bufferViews":[{"buffer":0,"byteOffset":9223372036854775000,"byteLength":33}],
            "buffers":[{"byteLength":33,"uri":"data:application/octet-stream;base64,$b64"}]}"""
        val f = GltfFactsReader.read(write("datauri-huge.gltf", json))
        assertNull(f.parseError)
        assertNull(f.maxTextureSidePx)
    }

    private object GlbContainerLen {
        fun of(glb: ByteArray): Int = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
    }
}
