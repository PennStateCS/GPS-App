package io.github.pennstatecs.geokit.models.georef

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.local.LocalEnu
import io.github.pennstatecs.geokit.models.glb.GlbContainer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites a GLB whose mesh POSITION vertices are baked WGS84 coordinates (longitude in X, height in
 * metres in Y, negative latitude in Z: the Metashape Y-up convention) into a **local ENU metric frame**
 * recentred on the model's origin.
 *
 * Such models are unrenderable as-is: the horizontal axes are in degrees (~1e-4 span) while the
 * vertical axis is in metres, producing a sub-pixel sliver parked far from the origin and riding
 * float32 precision limits. After reprojection each axis is in metres and centred on (0,0,0).
 *
 * Output convention (model +Z = north): east → −X, up → +Y, north → +Z. The app's AR renderer turns
 * every model 180° about Y, which maps model +Z to world north and model −X to world east. The
 * rewritten file carries `asset.extras.`[REPROJECTION_MARKER_KEY] = [REPROJECTION_MARKER_VALUE].
 *
 * Pure: bytes in, result out. Never logs and never throws (failures are [Result.Failed]).
 */
public object GlbGeoreferenceReprojector {

    private const val COMPONENT_TYPE_FLOAT = 5126

    /** Files larger than this are not reprojected (the whole file is processed in memory). */
    public const val MAX_FILE_BYTES: Int = 128 * 1024 * 1024

    /** asset.extras key/value written into reprojected GLBs (WGS84 ENU, north = model +Z). */
    public const val REPROJECTION_MARKER_KEY: String = "surrealarReprojection"
    public const val REPROJECTION_MARKER_VALUE: String = "wgs84-enu-north+z-v2"

    /**
     * Resolution of the source's float32 degree vertices at the origin, in metres (one float ulp). A
     * property of the file, reported once the origin was accepted.
     */
    public data class Precision(val latResM: Double, val lonResM: Double)

    /** The outcome of [reproject]. [precision] is set once the origin passed the plausibility rules. */
    public sealed class Result {
        public open val precision: Precision? get() = null

        /** Not a GLB 2.0 container, or it has no JSON or no BIN chunk. */
        public object NotGlb : Result()

        /** A GLB, but its first POSITION accessor does not describe a plausible WGS84 origin. */
        public data class NotGeoreferenced(val reason: Reason) : Result() {
            public enum class Reason {
                NO_ACCESSORS, NO_BUFFER_VIEWS, NO_BUFFERS, NO_MESHES, NO_POSITION, NO_MIN_MAX,
                LONGITUDE_OUT_OF_RANGE, LATITUDE_OUT_OF_RANGE, HEIGHT_OUT_OF_RANGE, NULL_ISLAND, SPAN_TOO_LARGE,
            }
        }

        /** Plausibly georeferenced, but a POSITION accessor has a layout this rewriter does not handle. */
        public data class Rejected(val reason: Reason, override val precision: Precision) : Result() {
            public enum class Reason {
                NOT_FLOAT, NOT_VEC3, BAD_BUFFER_VIEW, BAD_BUFFER, EXTERNAL_BUFFER, NO_VERTICES,
                /** A vertex lies past the end of the file. */
                OUT_OF_BOUNDS,
            }
        }

        /** An exception while parsing or rewriting. */
        public data class Failed(val error: Exception, override val precision: Precision?) : Result()

        /** The rewritten GLB, and the origin the recentred geometry now sits on. */
        public class Reprojected(
            public val bytes: ByteArray,
            public val origin: GeoreferencedOrigin,
            public val confidence: GeoreferenceConfidence,
            override val precision: Precision,
            public val method: String = GeoreferenceRules.METHOD,
        ) : Result()
    }

    /** True when a file with this [name] and [length] is worth reading: a `.glb` of 1 byte..[MAX_FILE_BYTES]. */
    public fun isCandidate(name: String, length: Long): Boolean =
        name.lowercase().endsWith(".glb") && length > 0 && length <= MAX_FILE_BYTES

    /**
     * Reprojects [glb] if it is a plausibly georeferenced GLB with embedded float VEC3 positions.
     * [glb] is used as scratch space (vertices are rewritten in place) and must not be reused.
     */
    public fun reproject(glb: ByteArray): Result {
        var precision: Precision? = null
        return try {
            reprojectInternal(glb) { precision = it }
        } catch (e: Exception) {
            Result.Failed(e, precision)
        }
    }

    private fun reprojectInternal(bytes: ByteArray, onPrecision: (Precision) -> Unit): Result {
        val glb = GlbContainer.parse(bytes) ?: return Result.NotGlb
        val jsonChunk = glb.json ?: return Result.NotGlb
        val binChunk = glb.bin ?: return Result.NotGlb

        val root = JSONObject(glb.text(jsonChunk))
        fun not(r: Result.NotGeoreferenced.Reason) = Result.NotGeoreferenced(r)

        val accessors = root.optJSONArray("accessors") ?: return not(Result.NotGeoreferenced.Reason.NO_ACCESSORS)
        val bufferViews = root.optJSONArray("bufferViews") ?: return not(Result.NotGeoreferenced.Reason.NO_BUFFER_VIEWS)
        val buffers = root.optJSONArray("buffers") ?: return not(Result.NotGeoreferenced.Reason.NO_BUFFERS)

        // Collect unique POSITION accessor indices across all mesh primitives
        val meshes = root.optJSONArray("meshes") ?: return not(Result.NotGeoreferenced.Reason.NO_MESHES)
        val posAccessorIdx = LinkedHashSet<Int>()
        for (mi in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
            for (pi in 0 until prims.length()) {
                val attrs = prims.getJSONObject(pi).optJSONObject("attributes") ?: continue
                val idx = attrs.optInt("POSITION", -1)
                if (idx >= 0) posAccessorIdx.add(idx)
            }
        }
        if (posAccessorIdx.isEmpty()) return not(Result.NotGeoreferenced.Reason.NO_POSITION)

        // Origin from the first POSITION accessor's min/max, with the same plausibility gating as the
        // detector, so ordinary local-space models are left alone.
        val firstAcc = accessors.getJSONObject(posAccessorIdx.first())
        val minArr = firstAcc.optJSONArray("min")
        val maxArr = firstAcc.optJSONArray("max")
        if (minArr == null || maxArr == null || minArr.length() < 3 || maxArr.length() < 3) {
            return not(Result.NotGeoreferenced.Reason.NO_MIN_MAX)
        }

        val lon0 = (minArr.getDouble(0) + maxArr.getDouble(0)) / 2.0  // X = longitude
        val alt0 = (minArr.getDouble(1) + maxArr.getDouble(1)) / 2.0  // Y = height (m)
        val z0 = (minArr.getDouble(2) + maxArr.getDouble(2)) / 2.0    // Z = -latitude
        val lat0 = -z0
        val span = PositionSpan(maxArr.getDouble(0) - minArr.getDouble(0), maxArr.getDouble(2) - minArr.getDouble(2))

        GeoreferenceRules.reject(lon0, lat0, alt0, span)?.let { return not(it.toReason()) }

        // WGS84 local ENU about the origin (ellipsoidal, Double). Convert to float only when writing.
        val enu = LocalEnu(lat0, lon0, alt0)

        // Property of the source file: its vertices are float32 degrees, so their resolution near the
        // origin is one float ulp.
        val precision = Precision(
            latResM = Math.ulp(lat0.toFloat()).toDouble() * Ellipsoid.WGS84.metersPerDegLat(lat0),
            lonResM = Math.ulp(lon0.toFloat()).toDouble() * Ellipsoid.WGS84.metersPerDegLon(lat0),
        )
        onPrecision(precision)
        fun rejected(r: Result.Rejected.Reason) = Result.Rejected(r, precision)

        // Transform each unique POSITION accessor's vertices in the BIN chunk.
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (idx in posAccessorIdx) {
            val acc = accessors.getJSONObject(idx)
            if (acc.optInt("componentType", -1) != COMPONENT_TYPE_FLOAT) return rejected(Result.Rejected.Reason.NOT_FLOAT)
            if (acc.optString("type") != "VEC3") return rejected(Result.Rejected.Reason.NOT_VEC3)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return rejected(Result.Rejected.Reason.BAD_BUFFER_VIEW)
            val bv = bufferViews.getJSONObject(bvIdx)
            // Must reference the embedded BIN buffer (no uri)
            val bufIdx = bv.optInt("buffer", -1)
            if (bufIdx < 0 || bufIdx >= buffers.length()) return rejected(Result.Rejected.Reason.BAD_BUFFER)
            if (buffers.getJSONObject(bufIdx).has("uri")) return rejected(Result.Rejected.Reason.EXTERNAL_BUFFER)

            val count = acc.optInt("count", 0)
            if (count <= 0) return rejected(Result.Rejected.Reason.NO_VERTICES)
            val accByteOffset = acc.optInt("byteOffset", 0)
            val bvByteOffset = bv.optInt("byteOffset", 0)
            val stride = bv.optInt("byteStride", 12).let { if (it == 0) 12 else it }
            val base = binChunk.contentStart + bvByteOffset + accByteOffset

            var nMinX = Double.MAX_VALUE; var nMinY = Double.MAX_VALUE; var nMinZ = Double.MAX_VALUE
            var nMaxX = -Double.MAX_VALUE; var nMaxY = -Double.MAX_VALUE; var nMaxZ = -Double.MAX_VALUE

            for (i in 0 until count) {
                val off = base + i * stride
                if (off + 12 > bytes.size) return rejected(Result.Rejected.Reason.OUT_OF_BOUNDS)
                val x = buf.getFloat(off).toDouble()
                val y = buf.getFloat(off + 4).toDouble()
                val z = buf.getFloat(off + 8).toDouble()

                // Degrees → local ENU metres about the origin (lat = −Z, lon = X, h = Y), written in
                // the renderer's model convention (+Z = north). Both horizontal axes flip relative to
                // east→+X / north→−Z: a rotation, not a mirror.
                val p = enu.toEnu(-z, x, y)
                val nx = (-p[0]).toFloat()       // east  → −X
                val ny = p[2].toFloat()          // up    → +Y
                val nz = p[1].toFloat()          // north → +Z

                buf.putFloat(off, nx)
                buf.putFloat(off + 4, ny)
                buf.putFloat(off + 8, nz)

                if (nx < nMinX) nMinX = nx.toDouble(); if (nx > nMaxX) nMaxX = nx.toDouble()
                if (ny < nMinY) nMinY = ny.toDouble(); if (ny > nMaxY) nMaxY = ny.toDouble()
                if (nz < nMinZ) nMinZ = nz.toDouble(); if (nz > nMaxZ) nMaxZ = nz.toDouble()
            }

            // Update accessor min/max so bounding-box framing is correct.
            acc.put("min", JSONArray(listOf(nMinX, nMinY, nMinZ)))
            acc.put("max", JSONArray(listOf(nMaxX, nMaxY, nMaxZ)))
        }

        // Mark the file so a later version can tell which convention it was rewritten with. Files
        // reprojected before this marker existed used east→+X / north→−Z (render turned 180°).
        val asset = root.optJSONObject("asset") ?: JSONObject().also { root.put("asset", it) }
        val extras = asset.optJSONObject("extras") ?: JSONObject().also { asset.put("extras", it) }
        extras.put(REPROJECTION_MARKER_KEY, REPROJECTION_MARKER_VALUE)

        // Re-serialise: JSON length changes (min/max edited), so rebuild the container.
        val out = glb.rewriteJson(root.toString().toByteArray(Charsets.UTF_8))

        return Result.Reprojected(
            bytes = out,
            origin = GeoreferencedOrigin(latitudeDeg = lat0, longitudeDeg = lon0, heightM = alt0),
            confidence = GeoreferenceRules.confidence(span, withLowBranch = false),
            precision = precision,
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
