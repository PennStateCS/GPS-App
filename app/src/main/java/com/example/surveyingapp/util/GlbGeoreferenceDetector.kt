package com.example.surveyingapp.util

import android.util.Log
import com.example.surveyingapp.domain.model.EmbeddedModelLocation
import com.example.surveyingapp.domain.model.ModelLocationConfidence
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Inspects a GLB (binary glTF 2.0) file for mesh POSITION vertex data that looks like
 * global WGS84 coordinates baked into geometry.
 *
 * This pattern occurs in exports from photogrammetry tools such as Agisoft Metashape when
 * the project CRS is geographic (EPSG:4326). The mesh vertices carry real-world
 * lon/lat/alt values instead of local model-space coordinates.
 *
 * NOTE: Some GLB files have global coordinates baked into vertex positions. The extracted
 * location can be used as a placement suggestion, but AR rendering may also require
 * recentering the mesh geometry around a local origin.
 *
 * Must be called from a background thread (IO dispatcher).
 */
object GlbGeoreferenceDetector {

    private const val TAG = "GlbGeoreferenceDetector"

    private const val GLB_MAGIC = 0x46546C67       // "glTF" little-endian
    private const val GLB_VERSION_2 = 2
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON" little-endian

    // Safety limit: don't read a JSON chunk larger than 8 MB
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024

    /**
     * Attempt to detect embedded WGS84-like coordinates in [file].
     * Returns null if the file is not a GLB, cannot be parsed, or contains no plausible location.
     */
    fun detect(file: File): EmbeddedModelLocation? {
        if (!file.exists() || !file.canRead()) return null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext != "glb") {
            Log.d(TAG, "${file.name}: skipping non-GLB file")
            return null
        }
        return try {
            detectInternal(file)
        } catch (e: EOFException) {
            Log.w(TAG, "${file.name}: unexpected EOF while parsing GLB")
            null
        } catch (e: Exception) {
            Log.w(TAG, "${file.name}: error during detection — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun detectInternal(file: File): EmbeddedModelLocation? {
        RandomAccessFile(file, "r").use { raf ->
            // ── GLB header ──────────────────────────────────────────────────────
            val magic = raf.readIntLE()
            if (magic != GLB_MAGIC) {
                Log.d(TAG, "${file.name}: not a GLB (magic 0x${magic.toString(16)})")
                return null
            }
            val version = raf.readIntLE()
            if (version != GLB_VERSION_2) {
                Log.w(TAG, "${file.name}: unsupported GLB version $version")
                return null
            }
            raf.readIntLE() // total length — skip

            // ── First chunk (must be JSON) ──────────────────────────────────────
            val chunkLen = raf.readIntLE()
            val chunkType = raf.readIntLE()
            if (chunkType != CHUNK_TYPE_JSON) {
                Log.w(TAG, "${file.name}: first chunk is not JSON (type 0x${chunkType.toString(16)})")
                return null
            }
            if (chunkLen <= 0 || chunkLen > MAX_JSON_BYTES) {
                Log.w(TAG, "${file.name}: JSON chunk length $chunkLen is out of safe range")
                return null
            }

            val jsonBytes = ByteArray(chunkLen)
            raf.readFully(jsonBytes)
            return parseGltfJson(file.name, String(jsonBytes, Charsets.UTF_8))
        }
    }

    private fun parseGltfJson(fileName: String, json: String): EmbeddedModelLocation? {
        val root = try { JSONObject(json) } catch (e: Exception) {
            Log.w(TAG, "$fileName: failed to parse JSON — ${e.message}")
            return null
        }

        // Locate the POSITION accessor index from the first mesh primitive
        val meshes = root.optJSONArray("meshes") ?: return null
        if (meshes.length() == 0) return null

        val primitives = meshes.getJSONObject(0).optJSONArray("primitives") ?: return null
        if (primitives.length() == 0) return null

        val attributes = primitives.getJSONObject(0).optJSONObject("attributes") ?: return null
        val posIdx = attributes.optInt("POSITION", -1)
        if (posIdx < 0) {
            Log.d(TAG, "$fileName: no POSITION attribute in first primitive")
            return null
        }

        val accessors = root.optJSONArray("accessors") ?: return null
        if (posIdx >= accessors.length()) return null
        val accessor = accessors.getJSONObject(posIdx)

        // Rely on the pre-computed min/max arrays (standard in well-formed GLB exports)
        val minArr = accessor.optJSONArray("min")
        val maxArr = accessor.optJSONArray("max")
        if (minArr == null || maxArr == null || minArr.length() < 3 || maxArr.length() < 3) {
            Log.d(TAG, "$fileName: POSITION accessor missing min/max; skipping binary read")
            return null
        }

        val minX = minArr.getDouble(0); val maxX = maxArr.getDouble(0)
        val minY = minArr.getDouble(1); val maxY = maxArr.getDouble(1)
        val minZ = minArr.getDouble(2); val maxZ = maxArr.getDouble(2)

        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val centerZ = (minZ + maxZ) / 2.0
        val spanX = maxX - minX
        val spanZ = maxZ - minZ

        Log.d(TAG, "$fileName: POSITION center=(%.6f, %.6f, %.6f) spanX=%.6f spanZ=%.6f"
            .format(centerX, centerY, centerZ, spanX, spanZ))

        // Heuristic: Metashape GLTF Y-up export → X=longitude, Y=altitude, Z=-latitude
        val candidateLon = centerX
        val candidateLat = -centerZ
        val candidateAlt = centerY

        // Reject if values are outside plausible WGS84 ranges
        if (candidateLon !in -180.0..180.0) return null
        if (candidateLat !in -90.0..90.0) return null
        if (candidateAlt !in -1000.0..9000.0) return null

        // Reject degenerate (0,0) — that's null island, not a real export
        if (abs(candidateLon) < 1e-6 && abs(candidateLat) < 1e-6) return null

        // Reject spans that are impossibly large for a local scan (> ~5 km)
        if (spanX > 0.05 || spanZ > 0.05) return null

        val confidence = when {
            spanX in 1e-7..0.005 && spanZ in 1e-7..0.005 -> ModelLocationConfidence.HIGH
            spanX < 0.05 && spanZ < 0.05                  -> ModelLocationConfidence.MEDIUM
            else                                           -> ModelLocationConfidence.LOW
        }

        Log.i(TAG, "$fileName: detected WGS-like location lat=%.7f lon=%.7f alt=%.2f [%s]"
            .format(candidateLat, candidateLon, candidateAlt, confidence))

        return EmbeddedModelLocation(
            latitude = candidateLat,
            longitude = candidateLon,
            altitudeMeters = candidateAlt,
            confidence = confidence,
            source = "GLB_POSITION_WGS_LIKE"
        )
    }

    // ── Little-endian helpers ──────────────────────────────────────────────────

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        if ((b0 or b1 or b2 or b3) < 0) throw EOFException("Unexpected EOF reading int")
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
