package io.github.pennstatecs.geokit.ar.projection

import io.github.pennstatecs.geokit.ar.frame.ArFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/** Screen projection used by the AR overlay, with hand-built matrices (no Android). */
class ScreenProjectionTest {

    private val w = 1000
    private val h = 500

    /** Column-major perspective matrix, same formula as android.opengl.Matrix.perspectiveM. */
    private fun perspectiveVp(fovyDeg: Float = 60f, aspect: Float = w.toFloat() / h, near: Float = 0.1f, far: Float = 100f): FloatArray {
        val f = 1.0f / tan(fovyDeg * (Math.PI / 360.0)).toFloat()
        val rangeReciprocal = 1.0f / (near - far)
        return FloatArray(16).also {
            it[0] = f / aspect
            it[5] = f
            it[10] = (far + near) * rangeReciprocal
            it[11] = -1.0f
            it[14] = 2.0f * far * near * rangeReciprocal
        }
    }

    private fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z, 1f)

    @Test fun ndcCorners_mapToScreenWithYFlipped() {
        val vp = ArFrames.identity()
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

    @Test fun outsideViewport_isReportedButNotClamped() {
        val p = projectToScreen(point(2f, 0f, 0f), ArFrames.identity(), w, h)!!
        assertEquals(1500f, p.sx, 1e-3f)
        assertFalse(p.onScreen)
    }

    @Test fun pointInFrontOfCamera_projectsOnScreen() {
        val p = projectToScreen(point(0f, 0f, -5f), perspectiveVp(), w, h)!!
        assertEquals(500f, p.sx, 1e-2f)
        assertEquals(250f, p.sy, 1e-2f)
        assertTrue(p.onScreen)
    }

    @Test fun pointAtEdgeOfFieldOfView_landsOnScreenEdge() {
        // 60° vertical FOV: a point 5 m ahead and 5·tan(30°) m up sits on the top edge.
        val y = 5f * tan(Math.toRadians(30.0)).toFloat()
        val p = projectToScreen(point(0f, y, -5f), perspectiveVp(), w, h)!!
        assertEquals(0f, p.sy, 1e-2f)
        assertEquals(500f, p.sx, 1e-2f)
    }

    @Test fun pointBehindCamera_returnsNull() =
        assertNull(projectToScreen(point(0f, 0f, 5f), perspectiveVp(), w, h))

    @Test fun zeroSizedViewport_returnsNull() {
        assertNull(projectToScreen(point(0f, 0f, 0f), ArFrames.identity(), 0, h))
        assertNull(projectToScreen(point(0f, 0f, 0f), ArFrames.identity(), w, 0))
    }
}
