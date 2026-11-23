package io.github.pennstatecs.geokit.models.gltf

import io.github.pennstatecs.geokit.models.glb.GlbContainer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads [GltfFacts] from a .glb or .gltf file without rendering it.
 *
 * Only the JSON, the GLB chunk headers and the first bytes of each image (enough for its header) are
 * read, so a 150 MB model costs a few kilobytes of I/O. Never throws: any failure becomes
 * [GltfFacts.parseError].
 */
public object GltfFactsReader {

    private const val MAX_JSON_BYTES = 64L * 1024 * 1024
    /** Enough for a PNG/WebP/KTX2 header and a JPEG SOF behind a typical EXIF block. */
    private const val IMAGE_HEADER_BYTES = 256 * 1024
    private const val MAX_NODE_DEPTH = 256

    public fun read(file: File): GltfFacts {
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
        val isGlb = GlbContainer.readIntLE(magic, 0) == GlbContainer.MAGIC
        var json: JSONObject? = null
        var bin: GlbBin? = null
        if (isGlb) {
            RandomAccessFile(file, "r").use { raf ->
                val head = GlbContainer.readHead(raf).also { it.require(GlbContainer.HEAD_BYTES) }
                val version = head.version
                if (version != 2) return failed(size, "Unsupported glTF version $version")
                val declared = GlbContainer.unsigned(head.declaredLength)
                if (declared > size) return failed(size, "File is truncated")
                val jsonLen = GlbContainer.unsigned(head.firstChunkLength)
                if (head.firstChunkType != GlbContainer.CHUNK_JSON) return failed(size, "GLB has no JSON chunk")
                // Lengths are unsigned Longs: no Int overflow. Chunks must end inside the declared
                // length (bytes after it are ignored).
                if (declared < 20) return failed(size, "GLB header is invalid")
                if (jsonLen <= 0 || jsonLen > MAX_JSON_BYTES || 20 + jsonLen > declared) {
                    return failed(size, "GLB JSON chunk is invalid")
                }
                val jsonBytes = ByteArray(jsonLen.toInt()).also { raf.readFully(it) }
                json = JSONObject(String(jsonBytes, Charsets.UTF_8).trimEnd(' ', '\u0000'))
                val binHeaderAt = 20 + ((jsonLen + 3) / 4) * 4
                if (binHeaderAt + 8 <= declared) {
                    raf.seek(binHeaderAt)
                    val ch = ByteArray(8).also { raf.readFully(it) }
                    if (GlbContainer.readIntLE(ch, 4) == GlbContainer.CHUNK_BIN) {
                        val len = GlbContainer.unsigned(GlbContainer.readIntLE(ch, 0))
                        if (len > declared - binHeaderAt - 8) return failed(size, "GLB BIN chunk is invalid")
                        bin = GlbBin(binHeaderAt + 8, len)
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
        fun resolve(uri: String): File? = when (val r = GltfUris.resolve(folder, uri)) {
            GltfUris.Resolution.Embedded -> null
            GltfUris.Resolution.NotRelative -> { missing += "$uri (not a file next to the model)"; null }
            GltfUris.Resolution.Outside -> { missing += "$uri (outside the model's folder)"; null }
            is GltfUris.Resolution.Missing -> { missing += uri; null }
            is GltfUris.Resolution.Found -> r.file
        }

        fun checkExternalBuffers() {
            for (i in 0 until buffers.length()) {
                val uri = buffers.optJSONObject(i)?.optString("uri").orEmpty()
                if (uri.isNotEmpty()) resolve(uri)
            }
        }

        /**
         * Reads up to [max] bytes of a bufferView; null when its buffer is unavailable or the view does
         * not lie inside it (embedded BIN) / fit an Int (data: URI).
         */
        fun bufferViewPrefix(index: Int, max: Int): ByteArray? {
            val bv = bufferViews.optJSONObject(index) ?: return null
            val bufIndex = bv.optInt("buffer", -1)
            val buf = buffers.optJSONObject(bufIndex) ?: return null
            val off = bv.optLong("byteOffset", 0L)
            val fullLen = bv.optLong("byteLength", 0L)
            val len = minOf(fullLen, max.toLong())
            if (off < 0 || len <= 0) return null
            val uri = buf.optString("uri", "")
            return when {
                uri.isEmpty() -> {
                    val b = bin ?: return null
                    // The whole view must lie inside the BIN chunk (no clamping of a bad view).
                    if (off > b.length || fullLen > b.length - off) return null
                    readAt(file, b.offset + off, len.toInt())
                }
                uri.startsWith("data:") -> {
                    if (off > Int.MAX_VALUE - len) return null
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

        class Geometry(val bounds: GltfBounds?, val triangles: Long?, val vertices: Long?)

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
            val bounds = if (minX.isFinite() && maxX.isFinite()) GltfBounds(minX, minY, minZ, maxX, maxY, maxZ) else null
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
        return runCatching { Base64Decoder.decode(payload.substring(0, chars)) }.getOrNull()
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

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it, "").ifEmpty { null } }

    private fun ints(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it, -1) }.filter { it >= 0 }
}
