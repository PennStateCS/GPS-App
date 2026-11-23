package io.github.pennstatecs.geokit.models.georef

import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceReprojector.Result
import io.github.pennstatecs.geokit.models.glb.GlbContainer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Malformed and unusual layouts for [GlbGeoreferenceReprojector]: every accessor is validated before
 * anything is transformed, arithmetic does not overflow, and the caller's array is never modified.
 */
class GlbGeoreferenceReprojectorHardeningTest {

    private val v1 = floatArrayOf(-76.002f, 298f, -41.002f)
    private val v2 = floatArrayOf(-75.998f, 302f, -40.998f)
    private val minMax = """"min":[-76.002,298,-41.002],"max":[-75.998,302,-40.998]"""
    private val twoVerts = GlbTestFixtures.floatBin(listOf(v1, v2))   // 24 bytes

    /** One POSITION accessor 0; [acc], [bv] and [buf] are extra JSON members for each object. */
    private fun glb(acc: String = "", bv: String = "", buf: String = "", bin: ByteArray = twoVerts,
                    bvLength: Int = bin.size, bufLength: Int = bin.size, count: Int = 2): ByteArray {
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":$count,"bufferView":0,$minMax$acc}],
            "bufferViews":[{"buffer":0,"byteLength":$bvLength$bv}],
            "buffers":[{"byteLength":$bufLength$buf}]}"""
        return GlbTestFixtures.glbWithJsonAndBin(json, bin)
    }

    /** Runs the reprojector and asserts the input array is byte-for-byte unchanged afterwards. */
    private fun run(bytes: ByteArray): Result {
        val before = bytes.copyOf()
        val r = GlbGeoreferenceReprojector.reproject(bytes)
        assertArrayEquals("input must never be modified ($r)", before, bytes)
        return r
    }

    private fun assertRejected(reason: Result.Rejected.Reason, bytes: ByteArray) {
        val r = run(bytes)
        assertTrue("expected Rejected($reason), was $r", r is Result.Rejected)
        assertEquals(reason, (r as Result.Rejected).reason)
    }

    private fun setInt(b: ByteArray, at: Int, v: Int) = b.also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, v) }

    private fun vertices(out: ByteArray): List<FloatArray> = GlbTestFixtures.verticesOf(GlbTestFixtures.parse(out).bin!!)

    // ── container ────────────────────────────────────────────────────────────────────────────

    @Test fun `wrong declared length is malformed`() {
        val bytes = glb()
        assertEquals(Result.Malformed(GlbContainer.Problem.LENGTH_MISMATCH), run(setInt(bytes.copyOf(), 8, bytes.size + 4)))
        assertEquals(Result.Malformed(GlbContainer.Problem.LENGTH_MISMATCH), run(setInt(bytes.copyOf(), 8, bytes.size - 4)))
    }

    @Test fun `truncated BIN chunk is malformed`() {
        val bytes = glb().let { it.copyOf(it.size - 4) }
        assertEquals(Result.Malformed(GlbContainer.Problem.BAD_CHUNK_LENGTH), run(setInt(bytes, 8, bytes.size)))
    }

    @Test fun `misaligned JSON chunk is malformed`() {
        // Hand-built: a 7-byte JSON chunk ("{}" plus 5 spaces) followed by an aligned-looking BIN.
        val json = "{}     ".toByteArray()
        val total = 12 + 8 + json.size + 8 + 4
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(GlbContainer.MAGIC).putInt(2).putInt(total)
        b.putInt(json.size).putInt(GlbContainer.CHUNK_JSON).put(json)
        b.putInt(4).putInt(GlbContainer.CHUNK_BIN).put(ByteArray(4))
        assertEquals(Result.Malformed(GlbContainer.Problem.MISALIGNED_CHUNK), run(b.array()))
    }

    @Test fun `malformed header bytes are not GLBs`() {
        val bytes = glb()
        assertEquals(Result.NotGlb, run(bytes.copyOf().also { it[0] = 0 }))
        assertEquals(Result.NotGlb, run(bytes.copyOf().also { it[4] = 3 }))
        assertEquals(Result.NotGlb, run(bytes.copyOf(11)))
    }

    // ── bufferView / buffer ──────────────────────────────────────────────────────────────────

    @Test fun `bufferView past the BIN chunk is rejected`() {
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, glb(bv = ""","byteOffset":8""", bufLength = 24))
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, glb(bvLength = 28, bufLength = 24))
    }

    @Test fun `negative bufferView offset and length are rejected`() {
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, glb(bv = ""","byteOffset":-12"""))
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, glb(bvLength = -24))
    }

    @Test fun `missing bufferView byteLength is rejected`() {
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,$minMax}],
            "bufferViews":[{"buffer":0}],"buffers":[{"byteLength":24}]}"""
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, GlbTestFixtures.glbWithJsonAndBin(json, twoVerts))
    }

    @Test fun `buffer longer than the BIN chunk is rejected`() {
        assertRejected(Result.Rejected.Reason.BAD_BUFFER, glb(bufLength = 64))
    }

    @Test fun `embedded data must be buffer 0`() {
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,$minMax}],
            "bufferViews":[{"buffer":1,"byteLength":24}],
            "buffers":[{"byteLength":24},{"byteLength":24}]}"""
        assertRejected(Result.Rejected.Reason.BAD_BUFFER, GlbTestFixtures.glbWithJsonAndBin(json, twoVerts))
    }

    @Test fun `accessor without a bufferView and meshopt bufferViews are rejected`() {
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,$minMax}],
            "bufferViews":[{"buffer":0,"byteLength":24}],"buffers":[{"byteLength":24}]}"""
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, GlbTestFixtures.glbWithJsonAndBin(json, twoVerts))
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW,
            glb(bv = ""","extensions":{"EXT_meshopt_compression":{"buffer":0,"byteLength":24}}"""))
    }

    // ── accessor ranges, strides, overflow ───────────────────────────────────────────────────

    @Test fun `accessor running past its bufferView is out of bounds even when the file is longer`() {
        // Old behaviour checked only the end of the file: a trailing chunk let the write land there.
        val bin = twoVerts + ByteArray(24)
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(bin = bin, bvLength = 24, bufLength = 48, count = 3))
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(acc = ""","byteOffset":4"""))
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(acc = ""","byteOffset":-4"""))
    }

    @Test fun `bad strides are rejected`() {
        assertRejected(Result.Rejected.Reason.BAD_STRIDE, glb(bv = ""","byteStride":8"""))
        assertRejected(Result.Rejected.Reason.BAD_STRIDE, glb(bv = ""","byteStride":14"""))
        assertRejected(Result.Rejected.Reason.BAD_STRIDE, glb(bv = ""","byteStride":-12"""))
    }

    @Test fun `huge offsets, counts and strides do not overflow`() {
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(acc = ""","byteOffset":1099511627776"""))   // 2^40
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(acc = ""","byteOffset":4294967292"""))     // wraps as Int
        assertRejected(Result.Rejected.Reason.BAD_BUFFER_VIEW, glb(bv = ""","byteOffset":9223372036854775807"""))
        // count·stride wraps Int (and with this stride, Long).
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, glb(count = 357913942))                       // ·12 > 2^32
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS,
            glb(acc = "", bv = ""","byteStride":4611686018427387904""", count = 3))                         // 2^62·2 overflows Long
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":4294967298,"bufferView":0,$minMax}],
            "bufferViews":[{"buffer":0,"byteLength":24}],"buffers":[{"byteLength":24}]}"""
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, GlbTestFixtures.glbWithJsonAndBin(json, twoVerts))
    }

    // ── unsupported vertex formats ───────────────────────────────────────────────────────────

    @Test fun `unsupported vertex formats are rejected`() {
        assertRejected(Result.Rejected.Reason.SPARSE,
            glb(acc = ""","sparse":{"count":1,"indices":{"bufferView":0,"componentType":5125},"values":{"bufferView":0}}"""))
        assertRejected(Result.Rejected.Reason.NORMALIZED, glb(acc = ""","normalized":true"""))
        val u16 = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"componentType":5123,"type":"VEC3","count":2,"bufferView":0,$minMax}],
            "bufferViews":[{"buffer":0,"byteLength":24}],"buffers":[{"byteLength":24}]}"""
        assertRejected(Result.Rejected.Reason.NOT_FLOAT, GlbTestFixtures.glbWithJsonAndBin(u16, twoVerts))
        val vec4 = u16.replace("5123", "5126").replace("VEC3", "VEC4")
        assertRejected(Result.Rejected.Reason.NOT_VEC3, GlbTestFixtures.glbWithJsonAndBin(vec4, twoVerts))
    }

    // ── multiple POSITION accessors ──────────────────────────────────────────────────────────

    /** Two meshes; accessor 0 at bufferView offset 0, accessor 1 described by [acc1]/[bv1]. */
    private fun twoAccessors(acc1: String, bv1: String, bin: ByteArray): ByteArray {
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]},{"primitives":[{"attributes":{"POSITION":1}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,$minMax},
                         {"componentType":5126,"type":"VEC3",$acc1}],
            "bufferViews":[{"buffer":0,"byteLength":24},{"buffer":0,$bv1}],
            "buffers":[{"byteLength":${bin.size}}]}"""
        return GlbTestFixtures.glbWithJsonAndBin(json, bin)
    }

    @Test fun `a later invalid accessor leaves the input untouched (validated before any write)`() {
        // Accessor 0 is fine; accessor 1 runs past its bufferView. The old code had already rewritten
        // accessor 0's vertices in the caller's array when it found the problem.
        val bytes = twoAccessors(""""count":5,"bufferView":1""", """"byteOffset":24,"byteLength":24""", twoVerts + twoVerts)
        assertRejected(Result.Rejected.Reason.OUT_OF_BOUNDS, bytes)
        val badIndex = twoAccessors(""""count":2,"bufferView":1""", """"byteOffset":24,"byteLength":24""", twoVerts + twoVerts)
            .let { String(it, Charsets.ISO_8859_1).replace("\"POSITION\":1", "\"POSITION\":9").toByteArray(Charsets.ISO_8859_1) }
        assertRejected(Result.Rejected.Reason.BAD_ACCESSOR, badIndex)
    }

    @Test fun `separate POSITION accessors are all reprojected, input untouched on success`() {
        val bin = twoVerts + twoVerts
        val r = run(twoAccessors(""""count":2,"bufferView":1,$minMax""", """"byteOffset":24,"byteLength":24""", bin))
        assertTrue("$r", r is Result.Reprojected)
        val v = vertices((r as Result.Reprojected).bytes)
        assertEquals(4, v.size)
        for (i in 0..2) { assertEquals(v[0][i], v[2][i], 0f); assertEquals(v[1][i], v[3][i], 0f) }
        assertTrue(v.all { p -> p.all { kotlin.math.abs(it) < 1000f } })
    }

    @Test fun `accessors sharing the same vertices are transformed once`() {
        val single = run(glb()) as Result.Reprojected
        // Accessor 1 = same bufferView range as accessor 0 through a second bufferView.
        val r = run(twoAccessors(""""count":2,"bufferView":1,$minMax""", """"byteOffset":0,"byteLength":24""", twoVerts))
        assertTrue("$r", r is Result.Reprojected)
        val out = (r as Result.Reprojected).bytes
        val a = vertices(single.bytes); val b = vertices(out)
        for (i in a.indices) assertArrayEquals(a[i], b[i], 0f)
        val accs = JSONObject(GlbTestFixtures.parse(out).json!!).getJSONArray("accessors")
        assertEquals(accs.getJSONObject(0).getJSONArray("min").toString(), accs.getJSONObject(1).getJSONArray("min").toString())
    }

    @Test fun `partly overlapping POSITION accessors are rejected`() {
        // Accessor 1 starts one vertex into accessor 0's range: vertex 2 would be transformed twice.
        val bin = twoVerts + GlbTestFixtures.floatBin(listOf(v1))
        assertRejected(Result.Rejected.Reason.OVERLAPPING,
            twoAccessors(""""count":2,"bufferView":1,$minMax""", """"byteOffset":12,"byteLength":24""", bin))
        // Same stride, element windows overlapping by 4 bytes.
        val bin2 = twoVerts + ByteArray(8)
        assertRejected(Result.Rejected.Reason.OVERLAPPING,
            twoAccessors(""""count":2,"bufferView":1,$minMax""", """"byteOffset":8,"byteLength":24""", bin2))
    }

    @Test fun `interleaved POSITION accessors that share a bufferView without sharing bytes are reprojected`() {
        // stride 24: accessor 0 at +0, accessor 1 at +12 of the same bufferView.
        val bin = GlbTestFixtures.floatBin(listOf(v1, v2, v2, v1))
        val json = """{"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]},{"primitives":[{"attributes":{"POSITION":1}}]}],
            "accessors":[{"componentType":5126,"type":"VEC3","count":2,"bufferView":0,$minMax},
                         {"componentType":5126,"type":"VEC3","count":2,"bufferView":0,"byteOffset":12,$minMax}],
            "bufferViews":[{"buffer":0,"byteLength":48,"byteStride":24}],
            "buffers":[{"byteLength":48}]}"""
        val r = run(GlbTestFixtures.glbWithJsonAndBin(json, bin))
        assertTrue("$r", r is Result.Reprojected)
        val v = vertices((r as Result.Reprojected).bytes)
        // v1, v2, v2, v1 in, so vertex 0 == vertex 3 and vertex 1 == vertex 2 out.
        assertArrayEquals(v[0], v[3], 0f); assertArrayEquals(v[1], v[2], 0f)
    }
}
