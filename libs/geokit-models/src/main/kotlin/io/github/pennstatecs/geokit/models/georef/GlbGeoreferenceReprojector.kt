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
 * Two phases: every POSITION accessor is validated against its bufferView, buffer and the BIN chunk
 * (with overflow-safe `Long` arithmetic) before any vertex is transformed, and the output is a new
 * array. The input array is never modified.
 *
 * Pure: bytes in, result out. Never logs and never throws (failures are [Result.Failed]).
 */
public object GlbGeoreferenceReprojector {

    private const val COMPONENT_TYPE_FLOAT = 5126
    /** Bytes in one float32 VEC3 element. */
    private const val VEC3_BYTES = 12L

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

        /** Not a GLB 2.0 container (too short, wrong magic or version), or it has no BIN chunk. */
        public object NotGlb : Result()

        /** A GLB 2.0 header, but the container itself is malformed (see [GlbContainer.Problem]). */
        public data class Malformed(val problem: GlbContainer.Problem) : Result()

        /** A GLB, but its first POSITION accessor does not describe a plausible WGS84 origin. */
        public data class NotGeoreferenced(val reason: Reason) : Result() {
            public enum class Reason {
                NO_ACCESSORS, NO_BUFFER_VIEWS, NO_BUFFERS, NO_MESHES, NO_POSITION, NO_MIN_MAX,
                LONGITUDE_OUT_OF_RANGE, LATITUDE_OUT_OF_RANGE, HEIGHT_OUT_OF_RANGE, NULL_ISLAND, SPAN_TOO_LARGE,
            }
        }

        /**
         * Plausibly georeferenced, but a POSITION accessor has a layout this rewriter does not handle
         * or that is inconsistent. Nothing was written.
         */
        public data class Rejected(val reason: Reason, override val precision: Precision) : Result() {
            public enum class Reason {
                NOT_FLOAT, NOT_VEC3, BAD_BUFFER_VIEW, BAD_BUFFER, EXTERNAL_BUFFER, NO_VERTICES,
                /** The accessor's elements run past its bufferView, or the bufferView past the buffer/BIN. */
                OUT_OF_BOUNDS,
                /** POSITION index is not an accessor. */
                BAD_ACCESSOR,
                /** Sparse accessor (substitutions are not reprojected). */
                SPARSE,
                /** `normalized: true` (not valid for float positions). */
                NORMALIZED,
                /** byteStride below 12 bytes or not a multiple of 4. */
                BAD_STRIDE,
                /** Two POSITION accessors partly share vertex bytes (would be transformed twice). */
                OVERLAPPING,
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
     * [glb] is not modified; a [Result.Reprojected] carries a new array.
     */
    public fun reproject(glb: ByteArray): Result {
        var precision: Precision? = null
        return try {
            reprojectInternal(glb) { precision = it }
        } catch (e: Exception) {
            Result.Failed(e, precision)
        }
    }

    /** One validated POSITION accessor: element byte offsets relative to the BIN chunk content. */
    private class Layout(val accessor: JSONObject, val start: Long, val stride: Long, val count: Int) {
        val end: Long get() = start + (count - 1).toLong() * stride + VEC3_BYTES
        fun sameElementsAs(o: Layout) = start == o.start && stride == o.stride && count == o.count
    }

    private fun reprojectInternal(bytes: ByteArray, onPrecision: (Precision) -> Unit): Result {
        when (val problem = GlbContainer.check(bytes)) {
            null -> Unit
            GlbContainer.Problem.TOO_SHORT, GlbContainer.Problem.BAD_MAGIC, GlbContainer.Problem.BAD_VERSION ->
                return Result.NotGlb
            else -> return Result.Malformed(problem)
        }
        val glb = GlbContainer.parse(bytes) ?: return Result.NotGlb   // valid: check() passed
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

        // Property of the source file: its vertices are float32 degrees, so their resolution near the
        // origin is one float ulp.
        val precision = Precision(
            latResM = Math.ulp(lat0.toFloat()).toDouble() * Ellipsoid.WGS84.metersPerDegLat(lat0),
            lonResM = Math.ulp(lon0.toFloat()).toDouble() * Ellipsoid.WGS84.metersPerDegLon(lat0),
        )
        onPrecision(precision)
        fun rejected(r: Result.Rejected.Reason) = Result.Rejected(r, precision)

        // ── Phase 1: validate every POSITION accessor. Nothing is transformed or written yet. ──
        val binLength = binChunk.length.toLong()
        val layouts = ArrayList<Layout>(posAccessorIdx.size)
        for (idx in posAccessorIdx) {
            val acc = accessors.optJSONObject(idx) ?: return rejected(Result.Rejected.Reason.BAD_ACCESSOR)
            if (acc.has("sparse")) return rejected(Result.Rejected.Reason.SPARSE)
            if (acc.optInt("componentType", -1) != COMPONENT_TYPE_FLOAT) return rejected(Result.Rejected.Reason.NOT_FLOAT)
            if (acc.optString("type") != "VEC3") return rejected(Result.Rejected.Reason.NOT_VEC3)
            if (acc.optBoolean("normalized", false)) return rejected(Result.Rejected.Reason.NORMALIZED)

            val bvIdx = acc.optLong("bufferView", -1L)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return rejected(Result.Rejected.Reason.BAD_BUFFER_VIEW)
            val bv = bufferViews.optJSONObject(bvIdx.toInt()) ?: return rejected(Result.Rejected.Reason.BAD_BUFFER_VIEW)
            // Compressed bufferViews (meshopt) hold encoded data, not vertices.
            val bvExt = bv.optJSONObject("extensions")
            if (bvExt != null && (bvExt.has("EXT_meshopt_compression") || bvExt.has("KHR_meshopt_compression"))) {
                return rejected(Result.Rejected.Reason.BAD_BUFFER_VIEW)
            }

            // Must reference the embedded BIN buffer: buffer 0 with no uri.
            val bufIdx = bv.optLong("buffer", -1L)
            if (bufIdx < 0 || bufIdx >= buffers.length()) return rejected(Result.Rejected.Reason.BAD_BUFFER)
            val buffer = buffers.optJSONObject(bufIdx.toInt()) ?: return rejected(Result.Rejected.Reason.BAD_BUFFER)
            if (buffer.has("uri")) return rejected(Result.Rejected.Reason.EXTERNAL_BUFFER)
            if (bufIdx != 0L) return rejected(Result.Rejected.Reason.BAD_BUFFER)
            // The BIN chunk may carry up to 3 padding bytes past the buffer's byteLength.
            val bufLength = buffer.optLong("byteLength", binLength)
            if (bufLength < 0 || bufLength > binLength) return rejected(Result.Rejected.Reason.BAD_BUFFER)

            val count = acc.optLong("count", 0L)
            if (count <= 0) return rejected(Result.Rejected.Reason.NO_VERTICES)
            if (count > Int.MAX_VALUE) return rejected(Result.Rejected.Reason.OUT_OF_BOUNDS)

            val bvOffset = bv.optLong("byteOffset", 0L)
            val bvLength = bv.optLong("byteLength", -1L)
            if (bvOffset < 0 || bvLength < 0 || bvOffset > bufLength || bvLength > bufLength - bvOffset) {
                return rejected(Result.Rejected.Reason.BAD_BUFFER_VIEW)
            }
            val stride = bv.optLong("byteStride", VEC3_BYTES).let { if (it == 0L) VEC3_BYTES else it }
            if (stride < VEC3_BYTES || stride % 4 != 0L) return rejected(Result.Rejected.Reason.BAD_STRIDE)

            val accOffset = acc.optLong("byteOffset", 0L)
            if (accOffset < 0 || accOffset > bvLength) return rejected(Result.Rejected.Reason.OUT_OF_BOUNDS)
            // byteOffset + (count − 1)·stride + 12 ≤ bufferView.byteLength, without overflow.
            val lastStart = try {
                Math.addExact(accOffset, Math.multiplyExact(count - 1, stride))
            } catch (overflow: ArithmeticException) {
                return rejected(Result.Rejected.Reason.OUT_OF_BOUNDS)
            }
            if (lastStart > bvLength - VEC3_BYTES) return rejected(Result.Rejected.Reason.OUT_OF_BOUNDS)

            layouts += Layout(acc, bvOffset + accOffset, stride, count.toInt())
        }

        // Accessors that share exactly the same elements are transformed once; any other overlap of
        // element bytes would transform some vertices twice.
        val aliasOf = IntArray(layouts.size) { it }
        for (i in layouts.indices) for (j in 0 until i) {
            val a = layouts[j]; val b = layouts[i]
            if (a.end <= b.start || b.end <= a.start) continue
            if (a.sameElementsAs(b)) { if (aliasOf[i] == i) aliasOf[i] = aliasOf[j]; continue }
            if (a.stride == b.stride) {
                // Same stride: element windows [0,12) and [d,d+12) modulo the stride must be disjoint.
                val d = Math.floorMod(b.start - a.start, a.stride)
                if (d >= VEC3_BYTES && d + VEC3_BYTES <= a.stride) continue
            }
            return rejected(Result.Rejected.Reason.OVERLAPPING)
        }

        // ── Phase 2: transform into separately owned arrays, then build a new GLB. ──
        // WGS84 local ENU about the origin (ellipsoidal, Double). Convert to float only when writing.
        val enu = LocalEnu(lat0, lon0, alt0)
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val binStart = binChunk.contentStart.toLong()
        val transformed = arrayOfNulls<FloatArray>(layouts.size)
        for ((li, layout) in layouts.withIndex()) {
            val out = transformed[aliasOf[li]] ?: FloatArray(layout.count * 3).also { v ->
                for (i in 0 until layout.count) {
                    val off = (binStart + layout.start + i.toLong() * layout.stride).toInt()
                    val x = src.getFloat(off).toDouble()
                    val y = src.getFloat(off + 4).toDouble()
                    val z = src.getFloat(off + 8).toDouble()

                    // Degrees → local ENU metres about the origin (lat = −Z, lon = X, h = Y), written in
                    // the renderer's model convention (+Z = north). Both horizontal axes flip relative to
                    // east→+X / north→−Z: a rotation, not a mirror.
                    val p = enu.toEnu(-z, x, y)
                    v[i * 3] = (-p[0]).toFloat()       // east  → −X
                    v[i * 3 + 1] = p[2].toFloat()      // up    → +Y
                    v[i * 3 + 2] = p[1].toFloat()      // north → +Z
                }
            }
            transformed[li] = out

            var nMinX = Double.MAX_VALUE; var nMinY = Double.MAX_VALUE; var nMinZ = Double.MAX_VALUE
            var nMaxX = -Double.MAX_VALUE; var nMaxY = -Double.MAX_VALUE; var nMaxZ = -Double.MAX_VALUE
            for (i in 0 until layout.count) {
                val nx = out[i * 3]; val ny = out[i * 3 + 1]; val nz = out[i * 3 + 2]
                if (nx < nMinX) nMinX = nx.toDouble(); if (nx > nMaxX) nMaxX = nx.toDouble()
                if (ny < nMinY) nMinY = ny.toDouble(); if (ny > nMaxY) nMaxY = ny.toDouble()
                if (nz < nMinZ) nMinZ = nz.toDouble(); if (nz > nMaxZ) nMaxZ = nz.toDouble()
            }
            // Update accessor min/max so bounding-box framing is correct.
            layout.accessor.put("min", JSONArray(listOf(nMinX, nMinY, nMinZ)))
            layout.accessor.put("max", JSONArray(listOf(nMaxX, nMaxY, nMaxZ)))
        }

        // Mark the file so a later version can tell which convention it was rewritten with. Files
        // reprojected before this marker existed used east→+X / north→−Z (render turned 180°).
        val asset = root.optJSONObject("asset") ?: JSONObject().also { root.put("asset", it) }
        val extras = asset.optJSONObject("extras") ?: JSONObject().also { asset.put("extras", it) }
        extras.put(REPROJECTION_MARKER_KEY, REPROJECTION_MARKER_VALUE)

        // Re-serialise: JSON length changes (min/max edited), so rebuild the container (a new array),
        // then write the transformed vertices into its BIN chunk.
        val outBytes = glb.rewriteJson(root.toString().toByteArray(Charsets.UTF_8))
        val outBin = GlbContainer.parse(outBytes)?.bin ?: error("Rewritten GLB has no BIN chunk")
        val dst = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((li, layout) in layouts.withIndex()) {
            if (aliasOf[li] != li) continue
            val v = transformed[li]!!
            for (i in 0 until layout.count) {
                val off = (outBin.contentStart.toLong() + layout.start + i.toLong() * layout.stride).toInt()
                dst.putFloat(off, v[i * 3])
                dst.putFloat(off + 4, v[i * 3 + 1])
                dst.putFloat(off + 8, v[i * 3 + 2])
            }
        }

        return Result.Reprojected(
            bytes = outBytes,
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
