package app.surrealar.ui.openinar

import android.opengl.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── projectToScreen ──────────────────────────────────────────────────────

    @Test
    fun ndcCorners_mapToScreenWithYFlipped() {
        val vp = identity()
        val center = projectToScreen(point(0f, 0f, 0f), vp, w, h)!!
        assertEquals(500f, center.sx, 1e-3f)
        assertEquals(250f, center.sy, 1e-3f)
        assertTrue(center.onScreen)

        val topLeft = projectToScreen(point(-1f, 1f, 0f), vp, w, h)!!   // NDC +Y is up
        assertEquals(0f, topLeft.sx, 1e-3f)
        assertEquals(0f, topLeft.sy, 1e-3f)
        assertTrue(topLeft.onScreen)

        val bottomRight = projectToScreen(point(1f, -1f, 0f), vp, w, h)!!
        assertEquals(1000f, bottomRight.sx, 1e-3f)
        assertEquals(500f, bottomRight.sy, 1e-3f)
    }

    @Test
    fun outsideViewport_isReportedButNotClamped() {
        val p = projectToScreen(point(2f, 0f, 0f), identity(), w, h)!!
        assertEquals(1500f, p.sx, 1e-3f)
        assertFalse(p.onScreen)
    }

    @Test
    fun pointInFrontOfCamera_projectsOnScreen() {
        val p = projectToScreen(point(0f, 0f, -5f), perspectiveVp(), w, h)!!
        assertEquals(500f, p.sx, 1e-2f)
        assertEquals(250f, p.sy, 1e-2f)
        assertTrue(p.onScreen)
    }

    @Test
    fun pointBehindCamera_returnsNull() =
        assertNull(projectToScreen(point(0f, 0f, 5f), perspectiveVp(), w, h))

    @Test
    fun zeroSizedViewport_returnsNull() {
        assertNull(projectToScreen(point(0f, 0f, 0f), identity(), 0, h))
        assertNull(projectToScreen(point(0f, 0f, 0f), identity(), w, 0))
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
