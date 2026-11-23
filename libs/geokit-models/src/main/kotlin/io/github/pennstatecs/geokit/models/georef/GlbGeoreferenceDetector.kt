package io.github.pennstatecs.geokit.models.georef

import io.github.pennstatecs.geokit.models.glb.GlbContainer
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Inspects a GLB (binary glTF 2.0) file for mesh POSITION vertex data that looks like global WGS84
 * coordinates baked into geometry.
 *
 * This pattern occurs in exports from photogrammetry tools such as Agisoft Metashape when the project
 * CRS is geographic (EPSG:4326): the vertices carry lon/lat/height instead of local model coordinates.
 * Only the header and the JSON chunk are read; the result uses the first mesh's first primitive's
 * POSITION accessor min/max (see [GeoreferenceRules]).
 *
 * Blocking file I/O: call off the main thread. Never logs and never throws: the result says why.
 */
public object GlbGeoreferenceDetector {

    /** Safety limit: don't read a JSON chunk larger than 8 MB. */
    public const val MAX_JSON_BYTES: Int = 8 * 1024 * 1024

    /** The outcome of [detect]. */
    public sealed class Result {
        /** Not a GLB: unreadable/missing, wrong extension, or wrong magic. */
        public data class NotGlb(val reason: Reason, val magic: Int? = null) : Result() {
            public enum class Reason { UNREADABLE, EXTENSION, MAGIC }
        }

        /**
         * A GLB that could not be inspected. [value] carries the version, chunk type or chunk length
         * for the structural reasons; [cause] the exception for the failure reasons.
         */
        public data class Rejected(val reason: Reason, val value: Int? = null, val cause: Exception? = null) : Result() {
            public enum class Reason {
                UNSUPPORTED_VERSION, FIRST_CHUNK_NOT_JSON, JSON_CHUNK_LENGTH,
                /** Ended before the header or JSON chunk did. */
                TRUNCATED,
                JSON_PARSE_ERROR,
                /** Any other failure while reading or inspecting (e.g. non-numeric min/max). */
                FAILED,
            }
        }

        /** A readable GLB without a plausible embedded location; [span] once min/max were read. */
        public data class NotGeoreferenced(val reason: Reason, val span: PositionSpan? = null) : Result() {
            public enum class Reason {
                NO_MESH, NO_PRIMITIVE, NO_ATTRIBUTES, NO_POSITION, NO_ACCESSOR, NO_MIN_MAX,
                LONGITUDE_OUT_OF_RANGE, LATITUDE_OUT_OF_RANGE, HEIGHT_OUT_OF_RANGE, NULL_ISLAND, SPAN_TOO_LARGE,
            }
        }

        /** A plausible embedded location (the centre of the POSITION min/max). */
        public data class Found(
            val origin: GeoreferencedOrigin,
            val confidence: GeoreferenceConfidence,
            val span: PositionSpan,
            val method: String = GeoreferenceRules.METHOD,
        ) : Result()
    }

    public fun detect(file: File): Result {
        if (!file.exists() || !file.canRead()) return Result.NotGlb(Result.NotGlb.Reason.UNREADABLE)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext != "glb") return Result.NotGlb(Result.NotGlb.Reason.EXTENSION)
        return try {
            detectInternal(file)
        } catch (e: EOFException) {
            Result.Rejected(Result.Rejected.Reason.TRUNCATED, cause = e)
        } catch (e: Exception) {
            Result.Rejected(Result.Rejected.Reason.FAILED, cause = e)
        }
    }

    private fun detectInternal(file: File): Result {
        RandomAccessFile(file, "r").use { raf ->
            val head = GlbContainer.readHead(raf)
            head.require(4)
            val magic = head.magic
            if (magic != GlbContainer.MAGIC) return Result.NotGlb(Result.NotGlb.Reason.MAGIC, magic)
            head.require(8)
            val version = head.version
            if (version != GlbContainer.VERSION_2) {
                return Result.Rejected(Result.Rejected.Reason.UNSUPPORTED_VERSION, version)
            }
            // total length: skipped. First chunk (must be JSON).
            head.require(GlbContainer.HEAD_BYTES)
            val chunkLen = head.firstChunkLength
            val chunkType = head.firstChunkType
            if (chunkType != GlbContainer.CHUNK_JSON) {
                return Result.Rejected(Result.Rejected.Reason.FIRST_CHUNK_NOT_JSON, chunkType)
            }
            if (chunkLen <= 0 || chunkLen > MAX_JSON_BYTES) {
                return Result.Rejected(Result.Rejected.Reason.JSON_CHUNK_LENGTH, chunkLen)
            }
            val jsonBytes = ByteArray(chunkLen)
            raf.readFully(jsonBytes)
            return detectInJson(String(jsonBytes, Charsets.UTF_8))
        }
    }

    /** The JSON part of [detect]. Throws on malformed values (caught by [detect]). */
    private fun detectInJson(json: String): Result {
        val root = try { JSONObject(json) } catch (e: Exception) {
            return Result.Rejected(Result.Rejected.Reason.JSON_PARSE_ERROR, cause = e)
        }
        fun not(r: Result.NotGeoreferenced.Reason, span: PositionSpan? = null) = Result.NotGeoreferenced(r, span)

        // Locate the POSITION accessor index from the first mesh primitive
        val meshes = root.optJSONArray("meshes") ?: return not(Result.NotGeoreferenced.Reason.NO_MESH)
        if (meshes.length() == 0) return not(Result.NotGeoreferenced.Reason.NO_MESH)

        val primitives = meshes.getJSONObject(0).optJSONArray("primitives")
            ?: return not(Result.NotGeoreferenced.Reason.NO_PRIMITIVE)
        if (primitives.length() == 0) return not(Result.NotGeoreferenced.Reason.NO_PRIMITIVE)

        val attributes = primitives.getJSONObject(0).optJSONObject("attributes")
            ?: return not(Result.NotGeoreferenced.Reason.NO_ATTRIBUTES)
        val posIdx = attributes.optInt("POSITION", -1)
        if (posIdx < 0) return not(Result.NotGeoreferenced.Reason.NO_POSITION)

        val accessors = root.optJSONArray("accessors") ?: return not(Result.NotGeoreferenced.Reason.NO_ACCESSOR)
        if (posIdx >= accessors.length()) return not(Result.NotGeoreferenced.Reason.NO_ACCESSOR)
        val accessor = accessors.getJSONObject(posIdx)

        // Rely on the pre-computed min/max arrays (standard in well-formed GLB exports)
        val minArr = accessor.optJSONArray("min")
        val maxArr = accessor.optJSONArray("max")
        if (minArr == null || maxArr == null || minArr.length() < 3 || maxArr.length() < 3) {
            return not(Result.NotGeoreferenced.Reason.NO_MIN_MAX)
        }

        val minX = minArr.getDouble(0); val maxX = maxArr.getDouble(0)
        val minY = minArr.getDouble(1); val maxY = maxArr.getDouble(1)
        val minZ = minArr.getDouble(2); val maxZ = maxArr.getDouble(2)

        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val centerZ = (minZ + maxZ) / 2.0
        val span = PositionSpan(maxX - minX, maxZ - minZ)

        // Heuristic: Metashape glTF Y-up export → X=longitude, Y=height, Z=-latitude
        val candidateLon = centerX
        val candidateLat = -centerZ
        val candidateAlt = centerY

        GeoreferenceRules.reject(candidateLon, candidateLat, candidateAlt, span)?.let {
            return not(it.toReason(), span)
        }

        return Result.Found(
            origin = GeoreferencedOrigin(latitudeDeg = candidateLat, longitudeDeg = candidateLon, heightM = candidateAlt),
            confidence = GeoreferenceRules.confidence(span, withLowBranch = true),
            span = span,
        )
    }

    private fun GeoreferenceRules.Rejection.toReason(): Result.NotGeoreferenced.Reason = when (this) {
        GeoreferenceRules.Rejection.LONGITUDE_OUT_OF_RANGE -> Result.NotGeoreferenced.Reason.LONGITUDE_OUT_OF_RANGE
        GeoreferenceRules.Rejection.LATITUDE_OUT_OF_RANGE -> Result.NotGeoreferenced.Reason.LATITUDE_OUT_OF_RANGE
        GeoreferenceRules.Rejection.HEIGHT_OUT_OF_RANGE -> Result.NotGeoreferenced.Reason.HEIGHT_OUT_OF_RANGE
        GeoreferenceRules.Rejection.NULL_ISLAND -> Result.NotGeoreferenced.Reason.NULL_ISLAND
        GeoreferenceRules.Rejection.SPAN_TOO_LARGE -> Result.NotGeoreferenced.Reason.SPAN_TOO_LARGE
    }
}
