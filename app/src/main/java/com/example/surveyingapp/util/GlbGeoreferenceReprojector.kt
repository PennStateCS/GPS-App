package com.example.surveyingapp.util

import android.util.Log
import com.example.surveyingapp.domain.model.EmbeddedModelLocation
import com.example.surveyingapp.domain.model.ModelLocationConfidence
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos

/**
 * Rewrites a GLB whose mesh POSITION vertices are baked WGS84 coordinates (longitude in X,
 * altitude-in-metres in Y, negative-latitude in Z — the Metashape Y-up convention) into a
 * **local ENU metric frame** recentred on the model's origin.
 *
 * Such models are unrenderable as-is: the horizontal axes are in degrees (~1e-4 span) while
 * the vertical axis is in metres (~metres span), producing a sub-pixel vertical sliver parked
 * ~150 units from the origin and riding float32 precision limits. After reprojection each axis
 * is in metres and centred on (0,0,0), so the existing camera-framing logic in the thumbnail
 * capturer, 3D viewer, and AR renderer all display it correctly.
 *
 * The returned [EmbeddedModelLocation] is the local origin (the geographic anchor the recentred
 * geometry now sits on). Callers can surface it as the model's embedded location.
 *
 * Must be called off the main thread. Returns null (and leaves the file untouched) when the
 * file is not a plausibly georeferenced single-buffer GLB, so ordinary models are never altered.
 */
object GlbGeoreferenceReprojector {

    private const val TAG = "GlbReprojector"

    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION_2 = 2
    private const val CHUNK_TYPE_JSON = 0x4E4F534A
    private const val CHUNK_TYPE_BIN = 0x004E4942
    private const val COMPONENT_TYPE_FLOAT = 5126

    // Matches the app's existing metres-per-degree approximation (see CoordinatesViewModel).
    private const val METERS_PER_DEG = 111_320.0

    private const val MAX_FILE_BYTES = 128 * 1024 * 1024

    fun reprojectIfGeoreferenced(file: File): EmbeddedModelLocation? {
        if (!file.exists() || !file.canRead()) return null
        if (!file.name.lowercase().endsWith(".glb")) return null
        if (file.length() <= 0 || file.length() > MAX_FILE_BYTES) return null
        return try {
            reprojectInternal(file)
        } catch (e: Exception) {
            Log.w(TAG, "${file.name}: reprojection skipped — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private class Chunk(val type: Int, val contentStart: Int, val length: Int)

    private fun reprojectInternal(file: File): EmbeddedModelLocation? {
        val bytes = file.readBytes()
        if (bytes.size < 12) return null
        val header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != GLB_MAGIC) return null
        if (header.int != GLB_VERSION_2) return null
        // total length (skip)

        // Parse chunks
        val chunks = ArrayList<Chunk>()
        var p = 12
        while (p + 8 <= bytes.size) {
            val clen = readIntLE(bytes, p)
            val ctype = readIntLE(bytes, p + 4)
            val contentStart = p + 8
            if (clen < 0 || contentStart + clen > bytes.size) break
            chunks.add(Chunk(ctype, contentStart, clen))
            p = contentStart + clen
        }
        val jsonChunk = chunks.firstOrNull { it.type == CHUNK_TYPE_JSON } ?: return null
        val binChunk = chunks.firstOrNull { it.type == CHUNK_TYPE_BIN } ?: return null

        val json = String(bytes, jsonChunk.contentStart, jsonChunk.length, Charsets.UTF_8)
        val root = JSONObject(json)

        val accessors = root.optJSONArray("accessors") ?: return null
        val bufferViews = root.optJSONArray("bufferViews") ?: return null
        val buffers = root.optJSONArray("buffers") ?: return null

        // Collect unique POSITION accessor indices across all mesh primitives
        val meshes = root.optJSONArray("meshes") ?: return null
        val posAccessorIdx = LinkedHashSet<Int>()
        for (mi in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
            for (pi in 0 until prims.length()) {
                val attrs = prims.getJSONObject(pi).optJSONObject("attributes") ?: continue
                val idx = attrs.optInt("POSITION", -1)
                if (idx >= 0) posAccessorIdx.add(idx)
            }
        }
        if (posAccessorIdx.isEmpty()) return null

        // Determine the origin from the first POSITION accessor's min/max, with the same
        // plausibility gating as the detector, so ordinary local-space models are left alone.
        val firstAcc = accessors.getJSONObject(posAccessorIdx.first())
        val minArr = firstAcc.optJSONArray("min")
        val maxArr = firstAcc.optJSONArray("max")
        if (minArr == null || maxArr == null || minArr.length() < 3 || maxArr.length() < 3) return null

        val lon0 = (minArr.getDouble(0) + maxArr.getDouble(0)) / 2.0  // X = longitude
        val alt0 = (minArr.getDouble(1) + maxArr.getDouble(1)) / 2.0  // Y = altitude (m)
        val z0   = (minArr.getDouble(2) + maxArr.getDouble(2)) / 2.0  // Z = -latitude
        val lat0 = -z0
        val spanX = maxArr.getDouble(0) - minArr.getDouble(0)
        val spanZ = maxArr.getDouble(2) - minArr.getDouble(2)

        if (lon0 !in -180.0..180.0) return null
        if (lat0 !in -90.0..90.0) return null
        if (alt0 !in -1000.0..9000.0) return null
        if (abs(lon0) < 1e-6 && abs(lat0) < 1e-6) return null
        if (spanX > 0.05 || spanZ > 0.05) return null

        val metersPerDegLon = METERS_PER_DEG * cos(Math.toRadians(lat0))
        val metersPerDegLat = METERS_PER_DEG

        // Transform each unique POSITION accessor's vertices in the BIN chunk.
        for (idx in posAccessorIdx) {
            val acc = accessors.getJSONObject(idx)
            if (acc.optInt("componentType", -1) != COMPONENT_TYPE_FLOAT) return null
            if (acc.optString("type") != "VEC3") return null
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            // Must reference the single embedded BIN buffer (buffer 0, no uri)
            val bufIdx = bv.optInt("buffer", -1)
            if (bufIdx < 0 || bufIdx >= buffers.length()) return null
            if (buffers.getJSONObject(bufIdx).has("uri")) return null

            val count = acc.optInt("count", 0)
            if (count <= 0) return null
            val accByteOffset = acc.optInt("byteOffset", 0)
            val bvByteOffset = bv.optInt("byteOffset", 0)
            val stride = bv.optInt("byteStride", 12).let { if (it == 0) 12 else it }
            val base = binChunk.contentStart + bvByteOffset + accByteOffset

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var nMinX = Double.MAX_VALUE; var nMinY = Double.MAX_VALUE; var nMinZ = Double.MAX_VALUE
            var nMaxX = -Double.MAX_VALUE; var nMaxY = -Double.MAX_VALUE; var nMaxZ = -Double.MAX_VALUE

            for (i in 0 until count) {
                val off = base + i * stride
                if (off + 12 > bytes.size) return null
                val x = buf.getFloat(off).toDouble()
                val y = buf.getFloat(off + 4).toDouble()
                val z = buf.getFloat(off + 8).toDouble()

                // Reproject degrees → local metres, recentred on the origin.
                val nx = ((x - lon0) * metersPerDegLon).toFloat()
                val ny = (y - alt0).toFloat()
                val nz = ((z - z0) * metersPerDegLat).toFloat()

                buf.putFloat(off, nx)
                buf.putFloat(off + 4, ny)
                buf.putFloat(off + 8, nz)

                if (nx < nMinX) nMinX = nx.toDouble(); if (nx > nMaxX) nMaxX = nx.toDouble()
                if (ny < nMinY) nMinY = ny.toDouble(); if (ny > nMaxY) nMaxY = ny.toDouble()
                if (nz < nMinZ) nMinZ = nz.toDouble(); if (nz > nMaxZ) nMaxZ = nz.toDouble()
            }

            // Update accessor min/max so bounding-box framing is correct.
            acc.put("min", org.json.JSONArray(listOf(nMinX, nMinY, nMinZ)))
            acc.put("max", org.json.JSONArray(listOf(nMaxX, nMaxY, nMaxZ)))
        }

        // Re-serialise: JSON length changes (min/max edited), so rebuild the container.
        val newJson = root.toString().toByteArray(Charsets.UTF_8)
        val newJsonPadded = padTo4(newJson, ' '.code.toByte())

        val out = ByteBuffer.allocate(
            12 + chunks.sumOf { 8 + alignedLen(if (it.type == CHUNK_TYPE_JSON) newJsonPadded.size else it.length) }
        ).order(ByteOrder.LITTLE_ENDIAN)

        out.putInt(GLB_MAGIC)
        out.putInt(GLB_VERSION_2)
        val totalLenPos = out.position()
        out.putInt(0) // patched below

        for (c in chunks) {
            if (c.type == CHUNK_TYPE_JSON) {
                out.putInt(newJsonPadded.size)
                out.putInt(c.type)
                out.put(newJsonPadded)
            } else {
                val content = bytes.copyOfRange(c.contentStart, c.contentStart + c.length)
                val padded = if (c.type == CHUNK_TYPE_BIN) padTo4(content, 0) else content
                out.putInt(padded.size)
                out.putInt(c.type)
                out.put(padded)
            }
        }
        val total = out.position()
        out.putInt(totalLenPos, total)

        file.writeBytes(out.array().copyOf(total))

        val confidence = when {
            spanX in 1e-7..0.005 && spanZ in 1e-7..0.005 -> ModelLocationConfidence.HIGH
            else -> ModelLocationConfidence.MEDIUM
        }
        Log.i(TAG, "${file.name}: reprojected to local ENU; origin lat=%.7f lon=%.7f alt=%.2f"
            .format(lat0, lon0, alt0))

        return EmbeddedModelLocation(
            latitude = lat0,
            longitude = lon0,
            altitudeMeters = alt0,
            confidence = confidence,
            source = "GLB_POSITION_WGS_LIKE"
        )
    }

    private fun alignedLen(len: Int): Int = (len + 3) and 3.inv()

    private fun padTo4(data: ByteArray, pad: Byte): ByteArray {
        val rem = data.size % 4
        if (rem == 0) return data
        val out = data.copyOf(data.size + (4 - rem))
        for (i in data.size until out.size) out[i] = pad
        return out
    }

    private fun readIntLE(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or
        ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or
        ((b[p + 3].toInt() and 0xFF) shl 24)
}
