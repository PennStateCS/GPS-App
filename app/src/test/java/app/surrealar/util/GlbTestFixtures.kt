package app.surrealar.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny, dependency-free GLB (binary glTF 2.0) builders/readers for unit tests. Lets the detector
 * and reprojector be exercised with crafted minimal byte buffers — no real models, Filament, GL,
 * or Android asset pipeline. Layout produced: [12-byte header][JSON chunk][optional BIN chunk].
 */
object GlbTestFixtures {

    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    /** A GLB with only a JSON chunk (enough for the detector, which never reads the BIN). */
    fun glbWithJson(json: String): ByteArray = assemble(json, null)

    /**
     * A plausibly-georeferenced GLB: a single POSITION accessor (FLOAT/VEC3) referencing an
     * embedded BIN buffer holding [vertices] as float32 (x,y,z) in the Metashape convention
     * (X=longitude, Y=altitude-m, Z=-latitude). Accessor min/max are computed from the vertices.
     */
    fun glbGeoreferenced(vertices: List<FloatArray>): ByteArray {
        val bin = ByteBuffer.allocate(vertices.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        vertices.forEach { v -> bin.putFloat(v[0]); bin.putFloat(v[1]); bin.putFloat(v[2]) }
        val binBytes = bin.array()
        val minX = vertices.minOf { it[0] }.toDouble(); val maxX = vertices.maxOf { it[0] }.toDouble()
        val minY = vertices.minOf { it[1] }.toDouble(); val maxY = vertices.maxOf { it[1] }.toDouble()
        val minZ = vertices.minOf { it[2] }.toDouble(); val maxZ = vertices.maxOf { it[2] }.toDouble()
        val json = """
            {"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
             "accessors":[{"componentType":5126,"type":"VEC3","count":${vertices.size},
               "bufferView":0,"byteOffset":0,"min":[$minX,$minY,$minZ],"max":[$maxX,$maxY,$maxZ]}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${binBytes.size}}],
             "buffers":[{"byteLength":${binBytes.size}}]}
        """.trimIndent()
        return assemble(json, binBytes)
    }

    /** A GLB with a fully custom JSON chunk plus a BIN chunk — for crafting structural-edge cases. */
    fun glbWithJsonAndBin(json: String, bin: ByteArray): ByteArray = assemble(json, bin)

    /** Packs [vertices] as little-endian float32 (x,y,z) — a raw BIN chunk payload. */
    fun floatBin(vertices: List<FloatArray>): ByteArray {
        val bin = ByteBuffer.allocate(vertices.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        vertices.forEach { v -> bin.putFloat(v[0]); bin.putFloat(v[1]); bin.putFloat(v[2]) }
        return bin.array()
    }

    /** Writes [bytes] to a temp `*.glb` file (auto-deleted on JVM exit). */
    fun tempGlb(bytes: ByteArray, suffix: String = ".glb"): File =
        File.createTempFile("glbtest", suffix).apply { writeBytes(bytes); deleteOnExit() }

    // ── readers (for verifying reprojection output) ──────────────────────────────────────────────

    /** Parsed view of a GLB: header validity, the JSON string, and the BIN chunk bytes. */
    class GlbView(val validHeader: Boolean, val totalLenMatches: Boolean, val json: String?, val bin: ByteArray?)

    fun parse(bytes: ByteArray): GlbView {
        if (bytes.size < 12) return GlbView(false, false, null, null)
        val hdr = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val magic = hdr.int; val version = hdr.int; val total = hdr.int
        val validHeader = magic == GLB_MAGIC && version == 2
        var json: String? = null; var bin: ByteArray? = null
        var p = 12
        while (p + 8 <= bytes.size) {
            val clen = readIntLE(bytes, p); val ctype = readIntLE(bytes, p + 4)
            val start = p + 8
            if (clen < 0 || start + clen > bytes.size) break
            when (ctype) {
                CHUNK_JSON -> json = String(bytes, start, clen, Charsets.UTF_8)
                CHUNK_BIN -> bin = bytes.copyOfRange(start, start + clen)
            }
            p = start + clen
        }
        return GlbView(validHeader, total == bytes.size, json, bin)
    }

    /** Reads (x,y,z) float triples from a BIN chunk (assumes contiguous VEC3 float32 layout). */
    fun verticesOf(bin: ByteArray): List<FloatArray> {
        val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val n = bin.size / 12
        return (0 until n).map { i -> floatArrayOf(buf.getFloat(i * 12), buf.getFloat(i * 12 + 4), buf.getFloat(i * 12 + 8)) }
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private fun assemble(json: String, bin: ByteArray?): ByteArray {
        val jsonBytes = pad4(json.toByteArray(Charsets.UTF_8), ' '.code.toByte())
        val binPadded = bin?.let { pad4(it, 0) }
        val total = 12 + (8 + jsonBytes.size) + (binPadded?.let { 8 + it.size } ?: 0)
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(GLB_MAGIC); buf.putInt(2); buf.putInt(total)
        buf.putInt(jsonBytes.size); buf.putInt(CHUNK_JSON); buf.put(jsonBytes)
        if (binPadded != null) { buf.putInt(binPadded.size); buf.putInt(CHUNK_BIN); buf.put(binPadded) }
        return buf.array()
    }

    private fun pad4(data: ByteArray, pad: Byte): ByteArray {
        val rem = data.size % 4
        if (rem == 0) return data
        return data.copyOf(data.size + (4 - rem)).also { for (i in data.size until it.size) it[i] = pad }
    }

    private fun readIntLE(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
}
