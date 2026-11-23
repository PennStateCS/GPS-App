package app.surrealar.ui.openinar

import android.opengl.Matrix
import io.github.pennstatecs.geokit.ar.projection.projectToScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen projection + off-screen pin arrow math used by the AR overlay. Robolectric supplies the
 * real android.opengl.Matrix and PointF implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArProjectionTest {

    private val w = 1000
    private val h = 500

    private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Camera at origin looking down -Z with a simple perspective projection. */
    private fun perspectiveVp(): FloatArray {
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, 60f, w.toFloat() / h, 0.1f, 100f)
        return proj
    }

    private fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z, 1f)

    // ── projectToScreen (geokit-ar) against the old android.opengl.Matrix path ─

    /** The pre-geokit implementation, kept here as the golden reference. */
    private fun oldProject(worldPos: FloatArray, vp: FloatArray): FloatArray? {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, worldPos, 0)
        if (clip[3] <= 0f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        return floatArrayOf((ndcX + 1f) * 0.5f * w, (1f - ndcY) * 0.5f * h)
    }

    @Test
    fun libraryProjection_matchesAndroidMatrix() {
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, 1f, 1.6f, 2f, 0.3f, 0.2f, -4f, 0f, 1f, 0f)
        val vp = FloatArray(16).also { Matrix.multiplyMM(it, 0, perspectiveVp(), 0, view, 0) }
        for (p in listOf(point(0f, 0f, -5f), point(3f, -1f, -20f), point(-7f, 2f, -3f), point(0.5f, 0.5f, 10f))) {
            val old = oldProject(p, vp)
            val new = projectToScreen(p, vp, w, h)
            if (old == null) { assertNull(new); continue }
            assertEquals(old[0], new!!.sx, 1e-3f)
            assertEquals(old[1], new.sy, 1e-3f)
        }
        assertTrue(projectToScreen(point(0f, 0f, -5f), identity(), w, h)!!.onScreen)
    }

    // ── computeEdgeArrow ─────────────────────────────────────────────────────

    @Test
    fun pinFarRight_arrowOnRightEdgePointingRight() {
        val (pos, angle) = computeEdgeArrow(5000f, 250f, w, h, margin = 40f)!!
        assertEquals(960f, pos.x, 1e-3f)
        assertEquals(250f, pos.y, 1e-3f)
        assertEquals(0f, angle, 1e-3f)
    }

    @Test
    fun pinAbove_arrowOnTopEdgePointingUp() {
        val (pos, angle) = computeEdgeArrow(500f, -3000f, w, h, margin = 40f)!!
        assertEquals(500f, pos.x, 1e-3f)
        assertEquals(40f, pos.y, 1e-3f)
        assertEquals(-90f, angle, 1e-3f)   // screen Y down → up is -90°
    }

    @Test
    fun pinLeftAndBelow_arrowStaysInsideMargins() {
        val (pos, angle) = computeEdgeArrow(-2000f, 2000f, w, h, margin = 40f)!!
        assertTrue(pos.x in 40f..960f)
        assertTrue(pos.y in 40f..460f)
        // Hits the bottom edge first (shorter half-height), pointing down-left.
        assertEquals(460f, pos.y, 1e-3f)
        assertTrue(angle > 90f && angle < 180f)
    }

    @Test
    fun degenerateInputs_returnNull() {
        assertNull(computeEdgeArrow(500f, 250f, w, h, margin = 40f))   // exactly at center
        assertNull(computeEdgeArrow(10f, 10f, 0, h, margin = 40f))
    }

    @Test
    fun arrowDirection_matchesPinDirection() {
        val result = computeEdgeArrow(900f, 450f, w, h, margin = 0f)
        assertNotNull(result)
        val (_, angle) = result!!
        val expected = Math.toDegrees(Math.atan2(200.0, 400.0)).toFloat()
        assertEquals(expected, angle, 1e-3f)
    }
}
