package app.surrealar.util

import app.surrealar.domain.model.ModelLocationConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Golden (byte-for-byte) tests for the GLB georeference reprojector and detector, recorded from the
 * app implementation before it moved to :geokit-models. The same inputs and hashes are asserted by
 * libs/geokit-models GlbGeoreferenceGoldenTest, so a change in reprojected output (vertex floats,
 * JSON re-serialisation, chunk padding) fails on both sides.
 *
 * Inputs are built in code (see [GoldenGlbs]); their SHA-256 is checked too, so the two copies of
 * the builder cannot drift apart unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlbGeoreferenceGoldenTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("golden", ".glb").apply { writeBytes(bytes); deleteOnExit() }

    private fun cubeFixture(): ByteArray =
        javaClass.classLoader!!.getResource("models/surreal-test-cube-1m.glb")!!.readBytes()

    private class Expect(val input: String, val output: String?)

    /** name -> (input SHA-256, output SHA-256 or null when the reprojector must leave the file alone). */
    private val expected = mapOf(
        "compass" to Expect("bf146a5803ef1a748d1d7b202bf043c8770dc6424aff8e61503ce00aa8b3b47e", "01c9d18180df75bce44fc61405775223961f537539eef8ea1d33e92b5e77e055"),
        "triangle" to Expect("37e16b3e865f4d5c98ee5fb0eceeccdfb499bea3e2dfc1989e2ac8a669de0d7f", "97d75590a621041a6c351acef86b77b5059d43f64f02b13afb00397be73083ed"),
        "interleaved" to Expect("4a5ae8d6dd6ad42e49ec5ab4e2e2b229e94f8e5dc2afcdbf1540280cf07b719f", "e4645c7bdf3922e7b94893182c1fe60efba4ba7e6a21c3c901ac18458033f77e"),
        "stride0-asset" to Expect("5b554086901b5ef915d2e61171261f0880c24cc9d79f958c90b68124a1b0238e", "8b1713905aecd0b17958bedeb387bea1cb218fa925b4fc82c687680aa987814e"),
        "overrun" to Expect("4da461d51ca6a1544fd1b0100f0d570e126beb3f6dd2066aa3976207fd4590cf", null),
        "local" to Expect("6153e4df0016f1d82c78096699c12807c58833a1e3d3c710bec7070c95341b19", null),
        "cube" to Expect("c0b5a0f523e3891f6e0c74ff1ecad339a1bc9992d49a985afdddf9f4b5d0a3a9", null),
    )

    private fun inputs(): Map<String, ByteArray> = GoldenGlbs.all() + ("cube" to cubeFixture())

    /** name -> (lat, lon, height) recorded for both the detector and the reprojector; all HIGH confidence. */
    private val origins = mapOf(
        "compass" to Triple(41.0, -76.0, 300.0),
        "triangle" to Triple(41.0, -76.0, 300.0),
        "interleaved" to Triple(40.2, -77.5, 121.0),
        "stride0-asset" to Triple(60.0, 10.0002, 0.0),
    )

    @Test fun `reprojected bytes match the recorded golden hashes`() {
        for ((name, bytes) in inputs()) {
            val exp = expected.getValue(name)
            assertEquals("$name input", exp.input, sha256(bytes))
            val file = temp(bytes)
            val origin = GlbGeoreferenceReprojector.reprojectIfGeoreferenced(file)
            if (exp.output == null) {
                assertNull("$name must not reproject", origin)
                assertEquals("$name must be untouched", exp.input, sha256(file.readBytes()))
            } else {
                assertNotNull("$name must reproject", origin)
                assertEquals("$name output", exp.output, sha256(file.readBytes()))
            }
        }
    }

    @Test fun `reprojected origins match the recorded values exactly`() {
        val all = GoldenGlbs.all()
        for ((name, o) in origins) {
            val loc = GlbGeoreferenceReprojector.reprojectIfGeoreferenced(temp(all.getValue(name)))!!
            assertEquals("$name lat", o.first, loc.latitude, 0.0)
            assertEquals("$name lon", o.second, loc.longitude, 0.0)
            assertEquals("$name h", o.third, loc.altitudeMeters!!, 0.0)
            assertEquals(ModelLocationConfidence.HIGH, loc.confidence)
            assertEquals("GLB_POSITION_WGS_LIKE", loc.source)
        }
    }

    @Test fun `detector results match the recorded values exactly`() {
        val all = GoldenGlbs.all()
        // The detector reads only the JSON, so the overrun file is still detected.
        for ((name, o) in origins + ("overrun" to Triple(41.0, -76.0, 300.0))) {
            val loc = GlbGeoreferenceDetector.detect(temp(all.getValue(name)))!!
            assertEquals("$name lat", o.first, loc.latitude, 0.0)
            assertEquals("$name lon", o.second, loc.longitude, 0.0)
            assertEquals("$name h", o.third, loc.altitudeMeters!!, 0.0)
            assertEquals(ModelLocationConfidence.HIGH, loc.confidence)
            assertEquals("GLB_POSITION_WGS_LIKE", loc.source)
        }
        assertNull(GlbGeoreferenceDetector.detect(temp(all.getValue("local"))))
        assertNull(GlbGeoreferenceDetector.detect(temp(cubeFixture())))
    }

    @Test fun `diagnostics log lines keep their text`() {
        val dir = java.nio.file.Files.createTempDirectory("diaglog").toFile()
        DiagnosticsLogger.initForTest(dir)
        try {
            val all = GoldenGlbs.all()
            val repro = temp(all.getValue("compass"))
            GlbGeoreferenceReprojector.reprojectIfGeoreferenced(repro)
            GlbGeoreferenceReprojector.reprojectIfGeoreferenced(temp(all.getValue("overrun")))
            GlbGeoreferenceDetector.detect(temp(all.getValue("triangle")))
            val v3 = all.getValue("triangle").copyOf().also { it[4] = 3 }
            GlbGeoreferenceDetector.detect(temp(v3))
            GlbGeoreferenceDetector.detect(temp(all.getValue("triangle").copyOf(30)))
            GlbGeoreferenceDetector.detect(temp(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0)))
            DiagnosticsLogger.flush()
            val log = File(dir, "app-log-current.txt").readText()
            val wanted = listOf(
                "Model reprojection precision limited: float32 degree vertices file=${repro.name} latResM=",
                "Model reprojected to local ENU file=${repro.name} georeferenced=true method=GLB_POSITION_WGS_LIKE confidence=HIGH",
                "method=GLB_POSITION_WGS_LIKE confidence=HIGH",
                "Model georeference detection skipped: unsupported GLB version file=",
                "version=3",
                "Model georeference detection failed: unexpected end of file file=",
            )
            for (w in wanted) org.junit.Assert.assertTrue("log must contain: $w\n$log", log.contains(w))
            org.junit.Assert.assertTrue(log.contains("Model georeference detected file="))
            // Two precision lines: the overrun file logs it before failing the vertex walk.
            assertEquals(2, Regex("Model reprojection precision limited").findAll(log).count())
            org.junit.Assert.assertFalse(log.contains("-76"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

/**
 * Deterministic GLB inputs for the golden tests. Kept byte-identical with the copy in
 * libs/geokit-models (the input hashes in the tests enforce it).
 */
internal object GoldenGlbs {

    private const val MAGIC = 0x46546C67
    private const val JSON = 0x4E4F534A
    private const val BIN = 0x004E4942

    fun all(): Map<String, ByteArray> = linkedMapOf(
        "compass" to compass(),
        "triangle" to triangle(),
        "interleaved" to interleaved(),
        "stride0-asset" to stride0WithAsset(),
        "overrun" to overrun(),
        "local" to local(),
    )

    private fun floats(vararg v: Float): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    private class Chunk(val type: Int, val data: ByteArray)

    /** Header + chunks exactly as given (no padding added: callers pad when they want to). */
    private fun container(vararg chunks: Chunk): ByteArray {
        val total = 12 + chunks.sumOf { 8 + it.data.size }
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC); b.putInt(2); b.putInt(total)
        for (c in chunks) { b.putInt(c.data.size); b.putInt(c.type); b.put(c.data) }
        return b.array()
    }

    private fun pad(data: ByteArray, with: Byte): ByteArray {
        val rem = data.size % 4
        return if (rem == 0) data else data.copyOf(data.size + 4 - rem).also { for (i in data.size until it.size) it[i] = with }
    }

    private fun jsonChunk(json: String) = Chunk(JSON, pad(json.trimIndent().toByteArray(Charsets.UTF_8), ' '.code.toByte()))

    private fun simple(verts: FloatArray, min: String, max: String): ByteArray {
        val bin = floats(*verts)
        val json = """
            {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"type":"VEC3","count":${verts.size / 3},
               "bufferView":0,"byteOffset":0,"min":$min,"max":$max}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${bin.size}}],
             "buffers":[{"byteLength":${bin.size}}]}
        """
        return container(jsonChunk(json), Chunk(BIN, bin))
    }

    /** N, S, E, W of 41 N 76 W at 300 m, 1/1024 degree out. */
    fun compass(): ByteArray {
        val d = 1.0 / 1024.0
        return simple(
            floatArrayOf(
                -76f, 300f, (-(41.0 + d)).toFloat(),
                -76f, 300f, (-(41.0 - d)).toFloat(),
                (-76.0 + d).toFloat(), 300f, -41f,
                (-76.0 - d).toFloat(), 300f, -41f,
            ),
            "[${-76.0 - d},300.0,${-(41.0 + d)}]", "[${-76.0 + d},300.0,${-(41.0 - d)}]",
        )
    }

    fun triangle(): ByteArray = simple(
        floatArrayOf(-76.002f, 298f, -41.002f, -75.998f, 302f, -40.998f, -76.000f, 300f, -41.000f),
        "[-76.002,298,-41.002]", "[-75.998,302,-40.998]",
    )

    /**
     * Interleaved POSITION+NORMAL (stride 24) shared by two meshes, a second POSITION accessor with
     * bufferView and accessor offsets, an asset with generator and extras (non-ASCII, '/', numbers),
     * an unaligned BIN chunk and a trailing unknown chunk.
     */
    fun interleaved(): ByteArray {
        val a = floats(
            -77.501f, 120.5f, -40.201f, 0f, 1f, 0f,
            -77.499f, 121.5f, -40.199f, 0f, 1f, 0f,
            -77.500f, 121.0f, -40.200f, 0f, 1f, 0f,
        )
        val b = floats(
            -77.5005f, 120.75f, -40.2005f,
            -77.4995f, 121.25f, -40.1995f,
        )
        val gap = ByteArray(8) { 0x5A }
        val bin = a + gap + ByteArray(4) + b + byteArrayOf(1, 2)   // 72 + 8 + 4 + 24 + 2 = 110 (unaligned)
        val json = """
            {"asset":{"version":"2.0","generator":"Agisoft Metashape 2.1/ü ✓","extras":{"keep":true,"n":1.5E-7,"big":12345678901,"s":"a\"b"}},
             "scene":0,"scenes":[{"nodes":[0,1,2]}],
             "nodes":[{"mesh":0},{"mesh":1,"translation":[1,2,3]},{"mesh":2}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"mode":4}]},
                       {"primitives":[{"attributes":{"POSITION":0}}]},
                       {"primitives":[{"attributes":{"POSITION":2}},{"attributes":{"NORMAL":1}}]}],
             "accessors":[{"componentType":5126,"type":"VEC3","count":3,"bufferView":0,
                            "min":[-77.501,120.5,-40.201],"max":[-77.499,121.5,-40.199]},
                          {"componentType":5126,"type":"VEC3","count":3,"bufferView":0,"byteOffset":12},
                          {"componentType":5126,"type":"VEC3","count":2,"bufferView":1,"byteOffset":4,
                            "min":[-77.5005,120.75,-40.2005],"max":[-77.4995,121.25,-40.1995]}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":72,"byteStride":24},
                            {"buffer":0,"byteOffset":80,"byteLength":28}],
             "buffers":[{"byteLength":110}],
             "extensionsUsed":[]}
        """
        return container(jsonChunk(json), Chunk(BIN, bin), Chunk(0x12345678, byteArrayOf(9, 8, 7, 6, 5)))
    }

    /** byteStride 0 (= tightly packed), an asset without extras, JSON padded with NULs not spaces. */
    fun stride0WithAsset(): ByteArray {
        val bin = floats(10.0001f, -5f, -60.0001f, 10.0003f, 5f, -59.9999f)
        val json = """
            {"asset":{"version":"2.0"},
             "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,
               "min":[10.0001,-5,-60.0001],"max":[10.0003,5,-59.9999]}],
             "bufferViews":[{"buffer":0,"byteLength":24,"byteStride":0}],
             "buffers":[{"byteLength":24}]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        return container(Chunk(JSON, pad(json, 0)), Chunk(BIN, bin))
    }

    /** Plausible origin but the accessor count runs past the end of the file. */
    fun overrun(): ByteArray {
        val bin = floats(-76.002f, 298f, -41.002f, -75.998f, 302f, -40.998f)
        val json = """
            {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"type":"VEC3","count":50,"bufferView":0,
               "min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]}],
             "bufferViews":[{"buffer":0,"byteLength":24}],
             "buffers":[{"byteLength":24}]}
        """
        return container(jsonChunk(json), Chunk(BIN, bin))
    }

    /** An ordinary model in local metres. */
    fun local(): ByteArray = simple(floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f), "[-1,-1,-1]", "[1,1,1]")
}
