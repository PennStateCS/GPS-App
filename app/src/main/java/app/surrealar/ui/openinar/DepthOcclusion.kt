package app.surrealar.ui.openinar

import android.opengl.Matrix
import app.surrealar.util.DiagnosticsLogger
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * One depth-occlusion mesh built from a single ARCore depth image. Immutable once published from
 * the GL thread; the Filament renderer uploads it on the main thread (compared by identity).
 *
 * [positions] are WORLD-space (ARCore world == Filament world), so the mesh stays correctly placed
 * as the camera moves between depth images — only the depth data itself ages.
 */
internal class OcclusionFrame(
    /** 3 floats per vertex, [DepthOccluderMesh.VERTEX_COUNT] vertices. */
    val positions: FloatArray,
    /** Triangle list; only the first [indexCount] entries are valid. */
    val indices: ShortArray,
    val indexCount: Int,
)

/**
 * Pure (Android-free) construction of the depth-occluder mesh: a screen-space grid whose vertices
 * are pushed out along their view rays to the depth ARCore measured, then transformed to world
 * space. Rendered depth-only (no colour) it hides model fragments that are behind real surfaces.
 *
 * Kept separate from the ARCore/Filament plumbing so the geometry is unit-testable.
 */
internal object DepthOccluderMesh {
    /** Grid vertices across / down the screen (~1 vertex per 20–40 px on a tablet). */
    const val COLS = 64
    const val ROWS = 48
    const val VERTEX_COUNT = COLS * ROWS
    const val MAX_INDEX_COUNT = (COLS - 1) * (ROWS - 1) * 6

    /**
     * Depth beyond this is ignored (never occludes). Device depth (ToF or depth-from-motion) is
     * reliable to roughly 5–8 m and noisy beyond; ignoring far samples avoids distant survey models
     * being hidden by noise.
     */
    const val MAX_DEPTH_M = 15f
    /** ARCore reports 0 for "no data"; anything this close is treated as invalid too. */
    const val MIN_DEPTH_M = 0.05f

    /**
     * The occluder is pushed slightly *behind* the measured surface so a model resting on the
     * ground (or touching a wall) is not z-fought / clipped by the very surface it sits on.
     */
    const val BIAS_ABS_M = 0.15f
    const val BIAS_REL = 0.03f

    /**
     * A grid cell spanning a depth discontinuity (e.g. a person's silhouette against a far wall)
     * would otherwise become a long stretched "rubber sheet" that wrongly occludes what is behind
     * the silhouette. Cells whose depth range exceeds `EDGE_ABS_M + EDGE_REL * nearest` are dropped.
     */
    const val EDGE_ABS_M = 0.25f
    const val EDGE_REL = 0.30f

    /** NDC (x,y) of every grid vertex, row-major; row 0 is the TOP of the screen. */
    fun ndcGrid(): FloatArray = FloatArray(VERTEX_COUNT * 2).also { g ->
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val i = (r * COLS + c) * 2
            g[i] = -1f + 2f * c / (COLS - 1)
            g[i + 1] = 1f - 2f * r / (ROWS - 1)
        }
    }

    fun isValidDepth(d: Float): Boolean = d.isFinite() && d >= MIN_DEPTH_M && d <= MAX_DEPTH_M

    /**
     * Builds the mesh.
     *
     * @param ndc           [ndcGrid] output.
     * @param depthM        Metric depth per vertex (distance along the camera's principal axis, as
     *                      ARCore reports it); invalid entries (0/NaN/out of range) occlude nothing.
     * @param cameraToWorld Column-major inverse of the ARCore view matrix.
     * @param proj          Column-major ARCore projection matrix (same one used for rendering).
     * @param outPositions  Receives 3 * [VERTEX_COUNT] world-space floats.
     * @param outIndices    Receives up to [MAX_INDEX_COUNT] triangle indices.
     * @return number of valid entries written to [outIndices].
     */
    fun build(
        ndc: FloatArray,
        depthM: FloatArray,
        cameraToWorld: FloatArray,
        proj: FloatArray,
        outPositions: FloatArray,
        outIndices: ShortArray,
    ): Int {
        val p0 = proj[0]; val p5 = proj[5]; val p8 = proj[8]; val p9 = proj[9]
        val c = cameraToWorld
        for (v in 0 until VERTEX_COUNT) {
            val raw = depthM[v]
            // Invalid vertices are never referenced by a triangle; give them a harmless position.
            val d = if (isValidDepth(raw)) raw * (1f + BIAS_REL) + BIAS_ABS_M else 1f
            val xn = ndc[v * 2]; val yn = ndc[v * 2 + 1]
            // Invert the projection for a point at view-space z = -d:
            //   x_ndc = (P0·x + P8·z) / -z  →  x = d·(x_ndc + P8) / P0   (likewise for y)
            val xv = d * (xn + p8) / p0
            val yv = d * (yn + p9) / p5
            val zv = -d
            val o = v * 3
            outPositions[o]     = c[0] * xv + c[4] * yv + c[8]  * zv + c[12]
            outPositions[o + 1] = c[1] * xv + c[5] * yv + c[9]  * zv + c[13]
            outPositions[o + 2] = c[2] * xv + c[6] * yv + c[10] * zv + c[14]
        }

        var n = 0
        for (r in 0 until ROWS - 1) for (col in 0 until COLS - 1) {
            val a = r * COLS + col          // top-left
            val b = a + 1                   // top-right
            val cc = a + COLS               // bottom-left
            val dd = cc + 1                 // bottom-right
            if (!cellUsable(depthM[a], depthM[b], depthM[cc], depthM[dd])) continue
            outIndices[n++] = a.toShort(); outIndices[n++] = cc.toShort(); outIndices[n++] = b.toShort()
            outIndices[n++] = b.toShort(); outIndices[n++] = cc.toShort(); outIndices[n++] = dd.toShort()
        }
        return n
    }

    internal fun cellUsable(a: Float, b: Float, c: Float, d: Float): Boolean {
        if (!isValidDepth(a) || !isValidDepth(b) || !isValidDepth(c) || !isValidDepth(d)) return false
        val mn = minOf(minOf(a, b), minOf(c, d))
        val mx = maxOf(maxOf(a, b), maxOf(c, d))
        return mx - mn <= EDGE_ABS_M + EDGE_REL * mn
    }
}

/**
 * GL-thread helper that turns ARCore's per-frame depth image into an [OcclusionFrame].
 *
 * Depth images arrive at their own (often lower) rate; a new mesh is built only when a new depth
 * image is available or the display geometry changes, otherwise the previous mesh is returned —
 * still valid because it is in world space.
 *
 * Must only be used on the GL thread (ARCore [Frame] objects are not thread-safe).
 */
internal class DepthOcclusionSampler {
    private val ndc = DepthOccluderMesh.ndcGrid()
    private val uv = FloatArray(ndc.size)
    private var haveUv = false
    private val depthM = FloatArray(DepthOccluderMesh.VERTEX_COUNT)
    private val cameraToWorld = FloatArray(16)
    private var lastTimestampNs = Long.MIN_VALUE
    private var last: OcclusionFrame? = null
    private var loggedFailure = false

    /** Forget the current mesh (occlusion turned off / view torn down). */
    fun reset() {
        last = null
        lastTimestampNs = Long.MIN_VALUE
        haveUv = false
    }

    fun update(frame: Frame, viewMatrix: FloatArray, projMatrix: FloatArray): OcclusionFrame? {
        try {
            if (frame.hasDisplayGeometryChanged() || !haveUv) {
                // Depth images share the camera TEXTURE's framing, so map each screen-grid vertex
                // into TEXTURE_NORMALIZED space (the same mapping the camera background uses).
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
                    Coordinates2d.TEXTURE_NORMALIZED, uv
                )
                haveUv = true
                lastTimestampNs = Long.MIN_VALUE   // force a rebuild with the new mapping
            }

            val image = try {
                frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                return last
            } catch (_: IllegalStateException) {
                // Depth was just switched on (Occlude toggle → session reconfigure): the first frame or
                // two can still come from the depth-off config. Not an error — wait for the next frame.
                return last
            }
            image.use { img ->
                val ts = img.timestamp
                if (ts == lastTimestampNs) return last
                val w = img.width
                val h = img.height
                val plane = img.planes[0]
                val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                for (v in 0 until DepthOccluderMesh.VERTEX_COUNT) {
                    val px = (uv[v * 2] * w).toInt().coerceIn(0, w - 1)
                    val py = (uv[v * 2 + 1] * h).toInt().coerceIn(0, h - 1)
                    val mm = buf.getShort(py * rowStride + px * pixelStride).toInt() and 0xFFFF
                    depthM[v] = mm / 1000f   // 0 = no data → invalid
                }
                if (!Matrix.invertM(cameraToWorld, 0, viewMatrix, 0)) return last
                val positions = FloatArray(DepthOccluderMesh.VERTEX_COUNT * 3)
                val indices = ShortArray(DepthOccluderMesh.MAX_INDEX_COUNT)
                val count = DepthOccluderMesh.build(ndc, depthM, cameraToWorld, projMatrix, positions, indices)
                last = OcclusionFrame(positions, indices, count)
                lastTimestampNs = ts
            }
        } catch (e: Exception) {
            // Never let occlusion break the AR frame; log once per session.
            if (!loggedFailure) {
                loggedFailure = true
                DiagnosticsLogger.w(ArFilamentRenderer.DIAG, "AR depth occlusion update failed", e)
            }
        }
        return last
    }
}
