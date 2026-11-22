package app.surrealar.ui.models.readiness

import android.util.Base64
import app.surrealar.ui.openinar.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads [GltfFacts] from a .glb or .gltf file without rendering it.
 *
 * Only the JSON, the GLB chunk headers and the first bytes of each image (enough for its header) are
 * read, so a 150 MB model costs a few kilobytes of I/O. Never throws: any failure becomes
 * [GltfFacts.parseError].
 */
object GltfFactsReader : GltfFactsSource {

    private const val GLB_MAGIC = 0x46546C67      // "glTF" little-endian
    private const val CHUNK_JSON = 0x4E4F534A     // "JSON"
    private const val CHUNK_BIN = 0x004E4942      // "BIN\0"
    private const val MAX_JSON_BYTES = 64L * 1024 * 1024
    /** Enough for a PNG/WebP/KTX2 header and a JPEG SOF behind a typical EXIF block. */
    private const val IMAGE_HEADER_BYTES = 256 * 1024
    private const val MAX_NODE_DEPTH = 256

    override fun read(file: File): GltfFacts {
        val size = runCatching { file.length() }.getOrDefault(0L)
        return try {
            readOrThrow(file, size)
        } catch (t: Throwable) {
            failed(size, t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName)
        }
    }

    private fun failed(size: Long, reason: String) = GltfFacts(
        fileSizeBytes = size, bounds = null, triangleCount = null, vertexCount = null, meshCount = null,
        textureCount = null, maxTextureSidePx = null, estimatedTextureBytes = null,
        extensionsRequired = emptyList(), extensionsUsed = emptyList(), missingResources = emptyList(),
        parseError = reason,
    )

    // ---------------------------------------------------------------- container

    /** Where buffer 0 lives inside a GLB (the BIN chunk), or null for a .gltf / GLB without BIN. */
    private class GlbBin(val offset: Long, val length: Long)

    private fun readOrThrow(file: File, size: Long): GltfFacts {
        if (!file.isFile) return failed(size, "File not found")
        if (size < 20) return failed(size, "File is too small to be a model")
        val magic = RandomAccessFile(file, "r").use { raf -> ByteArray(4).also { raf.readFully(it) } }
        val isGlb = le32(magic, 0) == GLB_MAGIC
        var json: JSONObject? = null
        var bin: GlbBin? = null
        if (isGlb) {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(20).also { raf.readFully(it) }
                val version = le32(header, 4)
                if (version != 2) return failed(size, "Unsupported glTF version $version")
                val declared = le32(header, 8).toLong() and 0xFFFFFFFFL
                if (declared > size) return failed(size, "File is truncated")
                val jsonLen = le32(header, 12).toLong() and 0xFFFFFFFFL
                if (le32(header, 16) != CHUNK_JSON) return failed(size, "GLB has no JSON chunk")
                if (jsonLen <= 0 || jsonLen > MAX_JSON_BYTES || 20 + jsonLen > size) {
                    return failed(size, "GLB JSON chunk is invalid")
                }
                val jsonBytes = ByteArray(jsonLen.toInt()).also { raf.readFully(it) }
                json = JSONObject(String(jsonBytes, Charsets.UTF_8).trimEnd(' ', '\u0000'))
                val binHeaderAt = 20 + ((jsonLen + 3) / 4) * 4
                if (binHeaderAt + 8 <= size) {
                    raf.seek(binHeaderAt)
                    val ch = ByteArray(8).also { raf.readFully(it) }
                    if (le32(ch, 4) == CHUNK_BIN) {
                        val len = le32(ch, 0).toLong() and 0xFFFFFFFFL
                        bin = GlbBin(binHeaderAt + 8, minOf(len, size - binHeaderAt - 8))
                    }
                }
            }
        } else {
            if (size > MAX_JSON_BYTES) return failed(size, "Not a GLB, and too large to be a glTF JSON file")
            val text = file.readText(Charsets.UTF_8).trimStart('﻿')
            if (!text.trimStart().startsWith("{")) return failed(size, "Not a glTF or GLB file")
            json = JSONObject(text)
        }
        val doc = json ?: return failed(size, "Couldn't read the model's JSON")
        val version = doc.optJSONObject("asset")?.optString("version").orEmpty()
        if (version.isNotEmpty() && !version.startsWith("2")) return failed(size, "Unsupported glTF version $version")
        return Parsed(file, size, doc, bin).facts()
    }

    // ---------------------------------------------------------------- parsed document

    private class Parsed(val file: File, val size: Long, val json: JSONObject, val bin: GlbBin?) {
        val folder: File = (file.absoluteFile.parentFile ?: File(".")).canonicalFile
        val accessors = json.optJSONArray("accessors") ?: JSONArray()
        val bufferViews = json.optJSONArray("bufferViews") ?: JSONArray()
        val buffers = json.optJSONArray("buffers") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val images = json.optJSONArray("images") ?: JSONArray()
        val missing = LinkedHashSet<String>()

        fun facts(): GltfFacts {
            checkExternalBuffers()
            val geo = walkGeometry()
            val tex = readTextures()
            return GltfFacts(
                fileSizeBytes = size,
                bounds = geo.bounds,
                triangleCount = geo.triangles,
                vertexCount = geo.vertices,
                meshCount = meshes.length(),
                textureCount = images.length(),
                maxTextureSidePx = tex.first,
                estimatedTextureBytes = tex.second,
                extensionsRequired = strings(json.optJSONArray("extensionsRequired")),
                extensionsUsed = strings(json.optJSONArray("extensionsUsed")),
                missingResources = missing.toList(),
            )
        }

        // ---------------- external resources

        /** Resolves a relative URI next to the model; null (and recorded as missing) when absent or unsafe. */
        fun resolve(uri: String): File? {
            if (uri.startsWith("data:")) return null
            val decoded = runCatching { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)
            val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(decoded) &&
                !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(decoded)
            if (hasScheme || decoded.startsWith("/") || decoded.startsWith("\\")) {
                missing += "$uri (not a file next to the model)"
                return null
            }
            val f = runCatching { File(folder, decoded).canonicalFile }.getOrNull()
            if (f == null || !f.path.startsWith(folder.path + File.separator)) {
                missing += "$uri (outside the model's folder)"
                return null
            }
            if (!f.isFile) {
                missing += uri
                return null
            }
            return f
        }

        fun checkExternalBuffers() {
            for (i in 0 until buffers.length()) {
                val uri = buffers.optJSONObject(i)?.optString("uri").orEmpty()
                if (uri.isNotEmpty()) resolve(uri)
            }
        }

        /** Reads up to [max] bytes of a bufferView; null when its buffer is unavailable. */
        fun bufferViewPrefix(index: Int, max: Int): ByteArray? {
            val bv = bufferViews.optJSONObject(index) ?: return null
            val bufIndex = bv.optInt("buffer", -1)
            val buf = buffers.optJSONObject(bufIndex) ?: return null
            val off = bv.optLong("byteOffset", 0L)
            val len = minOf(bv.optLong("byteLength", 0L), max.toLong())
            if (off < 0 || len <= 0) return null
            val uri = buf.optString("uri", "")
            return when {
                uri.isEmpty() -> {
                    val b = bin ?: return null
                    if (off >= b.length) return null
                    readAt(file, b.offset + off, minOf(len, b.length - off).toInt())
                }
                uri.startsWith("data:") -> {
                    val all = decodeDataUri(uri, (off + len).toInt()) ?: return null
                    if (off >= all.size) null else all.copyOfRange(off.toInt(), minOf(all.size.toLong(), off + len).toInt())
                }
                else -> {
                    val f = resolve(uri) ?: return null
                    if (off >= f.length()) null else readAt(f, off, minOf(len, f.length() - off).toInt())
                }
            }
        }

        // ---------------- geometry

        class Geometry(val bounds: Bounds?, val triangles: Long?, val vertices: Long?)

        fun walkGeometry(): Geometry {
            val roots = sceneRoots()
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
            var triangles = 0L
            var vertices = 0L
            var countsKnown = true

            fun visit(nodeIndex: Int, parent: DoubleArray, depth: Int, path: Set<Int>) {
                if (depth > MAX_NODE_DEPTH || nodeIndex in path) return
                val node = nodes.optJSONObject(nodeIndex) ?: return
                val world = Mat4.multiply(parent, localMatrix(node))
                val meshIndex = node.optInt("mesh", -1)
                val mesh = meshes.optJSONObject(meshIndex)
                val prims = mesh?.optJSONArray("primitives")
                if (prims != null) for (p in 0 until prims.length()) {
                    val prim = prims.optJSONObject(p) ?: continue
                    val posIndex = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    val pos = accessors.optJSONObject(posIndex)
                    if (pos == null) { countsKnown = false; continue }
                    val posCount = pos.optLong("count", -1L)
                    if (posCount < 0) countsKnown = false else vertices += posCount
                    val mode = prim.optInt("mode", 4)
                    val idx = accessors.optJSONObject(prim.optInt("indices", -1))
                    val n = idx?.optLong("count", -1L) ?: posCount
                    if (n < 0) countsKnown = false else triangles += when (mode) {
                        4 -> n / 3
                        5, 6 -> maxOf(0L, n - 2)
                        else -> 0L
                    }
                    val lo = accessorVec3(pos, "min") ?: continue
                    val hi = accessorVec3(pos, "max") ?: continue
                    for (c in 0 until 8) {
                        val x = if (c and 1 == 0) lo[0] else hi[0]
                        val y = if (c and 2 == 0) lo[1] else hi[1]
                        val z = if (c and 4 == 0) lo[2] else hi[2]
                        val t = Mat4.transformPoint(world, x, y, z)
                        minX = minOf(minX, t[0]); minY = minOf(minY, t[1]); minZ = minOf(minZ, t[2])
                        maxX = maxOf(maxX, t[0]); maxY = maxOf(maxY, t[1]); maxZ = maxOf(maxZ, t[2])
                    }
                }
                val children = node.optJSONArray("children") ?: return
                val nextPath = path + nodeIndex
                for (c in 0 until children.length()) visit(children.optInt(c, -1), world, depth + 1, nextPath)
            }

            for (r in roots) visit(r, Mat4.identity(), 0, emptySet())
            val bounds = if (minX.isFinite() && maxX.isFinite()) Bounds(
                Vec3(minX.toFloat(), minY.toFloat(), minZ.toFloat()),
                Vec3(maxX.toFloat(), maxY.toFloat(), maxZ.toFloat()),
            ) else null
            return Geometry(bounds, if (countsKnown) triangles else null, if (countsKnown) vertices else null)
        }

        /** Root nodes of the default scene; without scenes, every node that is nobody's child. */
        fun sceneRoots(): List<Int> {
            val scenes = json.optJSONArray("scenes")
            if (scenes != null && scenes.length() > 0) {
                val s = scenes.optJSONObject(json.optInt("scene", 0)) ?: scenes.optJSONObject(0)
                return ints(s?.optJSONArray("nodes"))
            }
            val children = HashSet<Int>()
            for (i in 0 until nodes.length()) children += ints(nodes.optJSONObject(i)?.optJSONArray("children"))
            return (0 until nodes.length()).filter { it !in children }
        }

        /** POSITION min/max, de-normalized for quantized integer accessors (KHR_mesh_quantization). */
        fun accessorVec3(acc: JSONObject, key: String): DoubleArray? {
            val a = acc.optJSONArray(key) ?: return null
            if (a.length() < 3) return null
            val v = DoubleArray(3) { a.optDouble(it, Double.NaN) }
            if (v.any { !it.isFinite() }) return null
            if (acc.optBoolean("normalized", false)) {
                val div = when (acc.optInt("componentType")) {
                    5120 -> 127.0; 5121 -> 255.0; 5122 -> 32767.0; 5123 -> 65535.0; else -> 1.0
                }
                for (i in 0..2) v[i] = maxOf(v[i] / div, -1.0)
            }
            return v
        }

        // ---------------- textures

        /** (max side px, estimated bytes) over the images whose size could be read. */
        fun readTextures(): Pair<Int?, Long?> {
            if (images.length() == 0) return 0 to 0L
            var maxSide: Int? = null
            var bytes: Long? = null
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                val head: ByteArray? = when {
                    img.has("bufferView") -> bufferViewPrefix(img.optInt("bufferView", -1), IMAGE_HEADER_BYTES)
                    img.optString("uri", "").startsWith("data:") -> decodeDataUri(img.optString("uri"), IMAGE_HEADER_BYTES)
                    img.optString("uri", "").isNotEmpty() -> resolve(img.optString("uri"))?.let {
                        readAt(it, 0, minOf(it.length(), IMAGE_HEADER_BYTES.toLong()).toInt())
                    }
                    else -> null
                }
                val dims = head?.let { ImageHeaders.dimensions(it) } ?: continue
                maxSide = maxOf(maxSide ?: 0, dims.first, dims.second)
                bytes = (bytes ?: 0L) + dims.first.toLong() * dims.second * 4L * 4L / 3L
            }
            return maxSide to bytes
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun localMatrix(node: JSONObject): DoubleArray {
        val m = node.optJSONArray("matrix")
        if (m != null && m.length() == 16) return DoubleArray(16) { m.optDouble(it, 0.0) }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        return Mat4.trs(
            t?.let { doubleArrayOf(it.optDouble(0, 0.0), it.optDouble(1, 0.0), it.optDouble(2, 0.0)) } ?: doubleArrayOf(0.0, 0.0, 0.0),
            r?.let { doubleArrayOf(it.optDouble(0, 0.0), it.optDouble(1, 0.0), it.optDouble(2, 0.0), it.optDouble(3, 1.0)) }
                ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            s?.let { doubleArrayOf(it.optDouble(0, 1.0), it.optDouble(1, 1.0), it.optDouble(2, 1.0)) } ?: doubleArrayOf(1.0, 1.0, 1.0),
        )
    }

    /** Decodes a base64 data: URI, stopping after roughly [maxBytes] bytes; null for non-base64 URIs. */
    private fun decodeDataUri(uri: String, maxBytes: Int): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
        val payload = uri.substring(comma + 1)
        val chars = ((maxBytes.toLong() + 2) / 3 * 4).coerceAtMost(payload.length.toLong()).toInt() / 4 * 4
        if (chars <= 0) return null
        return runCatching { Base64.decode(payload.substring(0, chars), Base64.DEFAULT) }.getOrNull()
    }

    private fun readAt(file: File, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                ByteArray(length).also { raf.readFully(it) }
            }
        }.getOrNull()
    }

    private fun le32(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it, "").ifEmpty { null } }

    private fun ints(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it, -1) }.filter { it >= 0 }
}

/** Column-major 4×4 matrices (glTF convention), just enough for node transforms. */
internal object Mat4 {
    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (col in 0..3) for (row in 0..3) {
            var s = 0.0
            for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        return out
    }

    /** T · R · S with R from a unit quaternion (x, y, z, w). */
    fun trs(t: DoubleArray, q: DoubleArray, s: DoubleArray): DoubleArray {
        val (x, y, z, w) = q.toList()
        val m = DoubleArray(16)
        m[0] = (1 - 2 * (y * y + z * z)) * s[0]
        m[1] = (2 * (x * y + z * w)) * s[0]
        m[2] = (2 * (x * z - y * w)) * s[0]
        m[4] = (2 * (x * y - z * w)) * s[1]
        m[5] = (1 - 2 * (x * x + z * z)) * s[1]
        m[6] = (2 * (y * z + x * w)) * s[1]
        m[8] = (2 * (x * z + y * w)) * s[2]
        m[9] = (2 * (y * z - x * w)) * s[2]
        m[10] = (1 - 2 * (x * x + y * y)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1.0
        return m
    }

    fun transformPoint(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}

/** Image dimensions from the first bytes of a PNG, JPEG, WebP or KTX2 file. */
internal object ImageHeaders {
    fun dimensions(b: ByteArray): Pair<Int, Int>? = png(b) ?: jpeg(b) ?: webp(b) ?: ktx2(b)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + 3) shl 24)
    private fun ascii(b: ByteArray, i: Int, s: String) =
        b.size >= i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }

    private fun valid(w: Int, h: Int) = if (w > 0 && h > 0) w to h else null

    private fun png(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || u8(b, 0) != 0x89 || !ascii(b, 1, "PNG") || !ascii(b, 12, "IHDR")) return null
        return valid(be32(b, 16), be32(b, 20))
    }

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        while (i + 3 < b.size) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue }
                marker == 0x01 || marker in 0xD0..0xD9 -> { i += 2; continue }
            }
            val len = be16(b, i + 2)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= b.size) return null
                return valid(be16(b, i + 7), be16(b, i + 5))
            }
            if (marker == 0xDA || len < 2) return null
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WEBP")) return null
        return when {
            ascii(b, 12, "VP8 ") -> valid(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
            ascii(b, 12, "VP8L") -> {
                val bits = le32(b, 21)
                valid((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }
            ascii(b, 12, "VP8X") -> valid(le24(b, 24) + 1, le24(b, 27) + 1)
            else -> null
        }
    }

    private fun ktx2(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 28 || u8(b, 0) != 0xAB || !ascii(b, 1, "KTX 20")) return null
        return valid(le32(b, 20), maxOf(1, le32(b, 24)))
    }
}
