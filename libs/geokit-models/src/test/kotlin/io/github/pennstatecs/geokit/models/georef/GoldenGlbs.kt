package io.github.pennstatecs.geokit.models.georef

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic GLB inputs for the golden tests. Kept byte-identical with the copy in
 * the app's GlbGeoreferenceGoldenTest (the input hashes in the tests enforce it).
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
