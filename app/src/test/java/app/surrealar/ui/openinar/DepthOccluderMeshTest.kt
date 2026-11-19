package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.DepthOccluderMesh.COLS
import app.surrealar.ui.openinar.DepthOccluderMesh.MAX_INDEX_COUNT
import app.surrealar.ui.openinar.DepthOccluderMesh.ROWS
import app.surrealar.ui.openinar.DepthOccluderMesh.VERTEX_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry of the depth-occlusion mesh: each grid vertex must land on the real surface ARCore
 * measured (plus the small anti-z-fight bias), in WORLD space, and must re-project exactly onto
 * its own screen position. Bad cells (missing depth, silhouettes) must be dropped.
 */
class DepthOccluderMeshTest {

    private val ndc = DepthOccluderMesh.ndcGrid()

    private fun biased(d: Float) = d * (1f + DepthOccluderMesh.BIAS_REL) + DepthOccluderMesh.BIAS_ABS_M

    private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** ARCore-like asymmetric perspective projection (column-major), near 0.1 / far 2000. */
    private fun arcoreProj(): FloatArray {
        val n = 0.1f; val f = 2000f
        return FloatArray(16).also {
            it[0] = 1.5f; it[5] = 2.0f; it[8] = 0.05f; it[9] = -0.03f
            it[10] = -(f + n) / (f - n); it[11] = -1f; it[14] = -2f * f * n / (f - n)
        }
    }

    /** Rigid camera→world: yaw about Y then translate. */
    private fun cameraToWorld(yawDeg: Double, tx: Float, ty: Float, tz: Float): FloatArray {
        val c = cos(Math.toRadians(yawDeg)).toFloat(); val s = sin(Math.toRadians(yawDeg)).toFloat()
        return FloatArray(16).also {
            it[0] = c; it[2] = -s; it[5] = 1f; it[8] = s; it[10] = c
            it[12] = tx; it[13] = ty; it[14] = tz; it[15] = 1f
        }
    }

    /** Inverse of a rigid transform: R^T, -R^T·t. */
    private fun invertRigid(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0..2) for (j in 0..2) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0..2) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1f
        return r
    }

    private fun mulVec(m: FloatArray, x: Float, y: Float, z: Float, w: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w,
        m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w,
        m[3] * x + m[7] * y + m[11] * z + m[15] * w,
    )

    private fun build(depth: FloatArray, camToWorld: FloatArray = identity(), proj: FloatArray = arcoreProj()):
        Pair<FloatArray, Int> {
        val pos = FloatArray(VERTEX_COUNT * 3)
        val idx = ShortArray(MAX_INDEX_COUNT)
        return pos to DepthOccluderMesh.build(ndc, depth, camToWorld, proj, pos, idx)
    }

    private fun uniform(d: Float) = FloatArray(VERTEX_COUNT) { d }

    @Test
    fun ndcGrid_spansScreen_rowZeroAtTop() {
        assertEquals(-1f, ndc[0], 1e-6f); assertEquals(1f, ndc[1], 1e-6f)          // top-left
        val last = (VERTEX_COUNT - 1) * 2
        assertEquals(1f, ndc[last], 1e-6f); assertEquals(-1f, ndc[last + 1], 1e-6f) // bottom-right
    }

    @Test
    fun flatWall_isFullyMeshed_atBiasedDepth() {
        val (pos, count) = build(uniform(2f))
        assertEquals(MAX_INDEX_COUNT, count)
        // Identity camera looks down -Z: every vertex sits at z = -(biased depth).
        for (v in 0 until VERTEX_COUNT) assertEquals(-biased(2f), pos[v * 3 + 2], 1e-4f)
    }

    @Test
    fun everyVertex_reprojectsToItsOwnScreenPoint_andDepth() {
        val proj = arcoreProj()
        val c2w = cameraToWorld(yawDeg = 30.0, tx = 5f, ty = 1.6f, tz = -3f)
        val view = invertRigid(c2w)
        val depth = FloatArray(VERTEX_COUNT) { 1f + (it % COLS) * 0.05f }  // gentle slope
        val (pos, _) = build(depth, c2w, proj)

        for (v in intArrayOf(0, COLS - 1, VERTEX_COUNT / 2 + 7, VERTEX_COUNT - 1)) {
            val eye = mulVec(view, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], 1f)
            assertEquals("view-space depth v=$v", -biased(depth[v]), eye[2], 1e-3f)
            val clip = mulVec(proj, eye[0], eye[1], eye[2], 1f)
            assertEquals("ndc x v=$v", ndc[v * 2], clip[0] / clip[3], 1e-4f)
            assertEquals("ndc y v=$v", ndc[v * 2 + 1], clip[1] / clip[3], 1e-4f)
        }
    }

    @Test
    fun cameraTranslation_movesMeshInWorldSpace() {
        val (atOrigin, _) = build(uniform(3f))
        val (moved, _) = build(uniform(3f), cameraToWorld(0.0, 10f, 2f, -4f))
        for (v in 0 until VERTEX_COUNT step 97) {
            assertEquals(atOrigin[v * 3] + 10f, moved[v * 3], 1e-3f)
            assertEquals(atOrigin[v * 3 + 1] + 2f, moved[v * 3 + 1], 1e-3f)
            assertEquals(atOrigin[v * 3 + 2] - 4f, moved[v * 3 + 2], 1e-3f)
        }
    }

    @Test
    fun missingDepthSample_dropsOnlyTheFourCellsTouchingIt() {
        val depth = uniform(2f)
        depth[10 * COLS + 10] = 0f   // ARCore "no data"
        val (_, count) = build(depth)
        assertEquals(MAX_INDEX_COUNT - 4 * 6, count)
    }

    @Test
    fun silhouetteEdge_isNotBridged() {
        // Left half: a person at 1 m. Right half: a wall at 5 m.
        val depth = FloatArray(VERTEX_COUNT) { if (it % COLS < COLS / 2) 1f else 5f }
        val (_, count) = build(depth)
        // Exactly one column of cells straddles the edge, on every row.
        assertEquals(MAX_INDEX_COUNT - (ROWS - 1) * 6, count)
    }

    @Test
    fun depthBeyondReliableRange_occludesNothing() {
        val (_, count) = build(uniform(DepthOccluderMesh.MAX_DEPTH_M + 1f))
        assertEquals(0, count)
    }

    @Test
    fun cellUsable_rules() {
        assertTrue(DepthOccluderMesh.cellUsable(2.0f, 2.1f, 2.2f, 2.3f))        // gentle slope
        assertTrue(DepthOccluderMesh.cellUsable(10f, 11f, 12f, 12.5f))          // far ground, relative tolerance
        assertFalse(DepthOccluderMesh.cellUsable(1f, 1f, 4f, 4f))               // silhouette
        assertFalse(DepthOccluderMesh.cellUsable(2f, 2f, 2f, Float.NaN))        // invalid sample
        assertFalse(DepthOccluderMesh.cellUsable(2f, 2f, 2f, 0f))               // ARCore no-data
    }

    @Test
    fun bias_pushesOccluderBehindSurface() {
        // A model touching the measured surface must not be hidden by that same surface.
        assertTrue(biased(1f) > 1.1f)
        assertTrue(biased(5f) > 5.1f)
    }
}
