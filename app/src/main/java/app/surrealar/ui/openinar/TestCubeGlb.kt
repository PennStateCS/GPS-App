package app.surrealar.ui.openinar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the AR test cube as a binary glTF (GLB) in code, so no model file has to ship or be imported:
 * a 1 m light-grey cube standing on its origin (y 0..1) with an orange 0.3 m "nose" on its +Z face.
 * The renderer turns glTF +Z toward geodetic north, so at yaw 0 the nose points north — it makes a turn
 * visible, which a plain cube would not.
 */
object TestCubeGlb {

    private class Box(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float)

    private val CUBE = Box(-0.5f, 0f, -0.5f, 0.5f, 1f, 0.5f)
    private val NOSE = Box(-0.15f, 0.35f, 0.5f, 0.15f, 0.65f, 0.8f)

    /** The GLB file bytes. */
    fun build(): ByteArray {
        val boxes = listOf(CUBE, NOSE)
        // Per box: 24 positions + 24 normals (VEC3 float) and 36 uint16 indices.
        val bin = ByteBuffer.allocate(boxes.size * (24 * 12 * 2 + 36 * 2)).order(ByteOrder.LITTLE_ENDIAN)
        val views = StringBuilder()
        val accessors = StringBuilder()
        val primitives = StringBuilder()
        boxes.forEachIndexed { i, b ->
            val (pos, nrm) = faces(b)
            val posOffset = bin.position(); pos.forEach { bin.putFloat(it) }
            val nrmOffset = bin.position(); nrm.forEach { bin.putFloat(it) }
            val idxOffset = bin.position()
            for (f in 0 until 6) {
                val v = (f * 4).toShort()
                for (k in intArrayOf(0, 1, 2, 0, 2, 3)) bin.putShort((v + k).toShort())
            }
            val base = i * 3
            if (i > 0) { views.append(','); accessors.append(','); primitives.append(',') }
            views.append("""{"buffer":0,"byteOffset":$posOffset,"byteLength":${24 * 12},"target":34962},""")
                .append("""{"buffer":0,"byteOffset":$nrmOffset,"byteLength":${24 * 12},"target":34962},""")
                .append("""{"buffer":0,"byteOffset":$idxOffset,"byteLength":72,"target":34963}""")
            accessors.append("""{"bufferView":$base,"componentType":5126,"count":24,"type":"VEC3",""")
                .append(""""min":[${b.minX},${b.minY},${b.minZ}],"max":[${b.maxX},${b.maxY},${b.maxZ}]},""")
                .append("""{"bufferView":${base + 1},"componentType":5126,"count":24,"type":"VEC3"},""")
                .append("""{"bufferView":${base + 2},"componentType":5123,"count":36,"type":"SCALAR"}""")
            primitives.append("""{"attributes":{"POSITION":$base,"NORMAL":${base + 1}},"indices":${base + 2},"material":$i}""")
        }
        val binBytes = bin.array()
        val json = """{"asset":{"version":"2.0","generator":"SurReal AR test cube"},"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"name":"TestCube","mesh":0}],"meshes":[{"primitives":[$primitives]}],""" +
            """"materials":[{"name":"Cube","pbrMetallicRoughness":{"baseColorFactor":[0.86,0.88,0.92,1.0],"metallicFactor":0.0,"roughnessFactor":0.8}},""" +
            """{"name":"Nose","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.55,0.05,1.0],"metallicFactor":0.0,"roughnessFactor":0.6}}],""" +
            """"buffers":[{"byteLength":${binBytes.size}}],"bufferViews":[$views],"accessors":[$accessors]}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8).padTo4(' '.code.toByte())
        val binPadded = binBytes.padTo4(0)
        val total = 12 + 8 + jsonBytes.size + 8 + binPadded.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)               // "glTF", version 2, length
            putInt(jsonBytes.size); putInt(0x4E4F534A); put(jsonBytes) // JSON chunk
            putInt(binPadded.size); putInt(0x004E4942); put(binPadded) // BIN chunk
        }.array()
    }

    /** 24 positions and normals (4 per face, flat normals, counter-clockwise from outside). */
    private fun faces(b: Box): Pair<FloatArray, FloatArray> {
        val x0 = b.minX; val y0 = b.minY; val z0 = b.minZ; val x1 = b.maxX; val y1 = b.maxY; val z1 = b.maxZ
        val quads = listOf(
            floatArrayOf(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1) to floatArrayOf(1f, 0f, 0f),   // +X
            floatArrayOf(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0) to floatArrayOf(-1f, 0f, 0f),  // -X
            floatArrayOf(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0) to floatArrayOf(0f, 1f, 0f),   // +Y
            floatArrayOf(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1) to floatArrayOf(0f, -1f, 0f),  // -Y
            floatArrayOf(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1) to floatArrayOf(0f, 0f, 1f),   // +Z
            floatArrayOf(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0) to floatArrayOf(0f, 0f, -1f),  // -Z
        )
        val pos = FloatArray(72); val nrm = FloatArray(72)
        quads.forEachIndexed { f, (q, n) ->
            System.arraycopy(q, 0, pos, f * 12, 12)
            for (v in 0 until 4) System.arraycopy(n, 0, nrm, f * 12 + v * 3, 3)
        }
        return pos to nrm
    }

    private fun ByteArray.padTo4(pad: Byte): ByteArray {
        val rem = size % 4
        return if (rem == 0) this else this + ByteArray(4 - rem) { pad }
    }
}
