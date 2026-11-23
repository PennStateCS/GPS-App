package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelMove
import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ArMoveMathTest {

    /** Camera 1.5 m up at the origin, pitched down 45° looking toward world -Z, 90° vertical FOV, 1000×1000 px. */
    private fun camLookingDown(pitchDownDeg: Float = 45f): ArMoveMath.CameraState {
        val a = Math.toRadians(-pitchDownDeg.toDouble()) / 2.0      // rotation about +X (negative = look down)
        return ArMoveMath.CameraState(
            px = 0f, py = 1.5f, pz = 0f,
            qx = sin(a).toFloat(), qy = 0f, qz = 0f, qw = cos(a).toFloat(),
            p0 = 1f, p5 = 1f, p8 = 0f, p9 = 0f,    // tan(45°) = 1 → 90° FOV
            widthPx = 1000, heightPx = 1000,
        )
    }

    private fun identityAnchor() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test fun `screen centre looking down 45 degrees hits the ground 1_5 m ahead`() {
        val hit = ArMoveMath.hitHorizontalPlane(camLookingDown(), 500f, 500f, planeY = 0f)!!
        assertEquals(0f, hit[0], 1e-4f)
        assertEquals(0f, hit[1], 0f)
        assertEquals(-1.5f, hit[2], 1e-4f)
    }

    @Test fun `a point above the horizon does not hit the ground`() {
        assertNull(ArMoveMath.hitHorizontalPlane(camLookingDown(pitchDownDeg = 0f), 500f, 100f, planeY = 0f))
        assertNotNull(ArMoveMath.hitHorizontalPlane(camLookingDown(pitchDownDeg = 0f), 500f, 900f, planeY = 0f))
    }

    @Test fun `dragging right moves the hit point to the camera's right`() {
        val cam = camLookingDown()
        val a = ArMoveMath.hitHorizontalPlane(cam, 500f, 500f, 0f)!!
        val b = ArMoveMath.hitHorizontalPlane(cam, 700f, 500f, 0f)!!
        assertEquals(true, b[0] > a[0])                 // camera looks -Z, so its right is +X
        assertEquals(0f, b[1] - a[1], 0f)
    }

    @Test fun `world delta maps to east north up through an identity East-Up-South anchor`() {
        val enu = ArMoveMath.worldDeltaToEnu(identityAnchor(), dx = 2f, dy = 0.5f, dz = -3f)
        assertEquals(2f, enu[0], 1e-6f)      // east = +X
        assertEquals(3f, enu[1], 1e-6f)      // north = -Z
        assertEquals(0.5f, enu[2], 1e-6f)    // up = +Y
    }

    @Test fun `world delta through an anchor rotated 90 degrees about up`() {
        // Anchor whose east axis points along world -Z (world rotated 90° about +Y).
        val c = 0f; val s = 1f
        val anchor = floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        val enu = ArMoveMath.worldDeltaToEnu(anchor, dx = 0f, dy = 0f, dz = -1f)
        assertEquals(1f, enu[0], 1e-6f)      // world -Z is east here
        assertEquals(0f, enu[1], 1e-6f)
    }

    @Test fun `clockwise twist is negative yaw and wraps across 180`() {
        assertEquals(-30f, ArMoveMath.twistToYawDeg(0f, 30f), 1e-4f)
        assertEquals(20f, ArMoveMath.twistToYawDeg(10f, -10f), 1e-4f)
        assertEquals(-20f, ArMoveMath.twistToYawDeg(170f, -170f), 1e-4f)   // crossed ±180
    }

    @Test fun `pointer angle uses screen coordinates`() {
        assertEquals(0f, ArMoveMath.pointerAngleDeg(0f, 0f, 10f, 0f), 1e-4f)
        assertEquals(90f, ArMoveMath.pointerAngleDeg(0f, 0f, 0f, 10f), 1e-4f)   // y down = clockwise
    }

    @Test fun `dragging up raises the model in proportion to distance`() {
        val cam = camLookingDown()
        // 90° FOV over 1000 px at 5 m: the view is 10 m tall, so 100 px = 1 m.
        assertEquals(1f, ArMoveMath.verticalDragToUpM(cam, dyPx = -100f, distanceM = 5f), 1e-4f)
        assertEquals(-2f, ArMoveMath.verticalDragToUpM(cam, dyPx = 100f, distanceM = 10f), 1e-4f)
    }

    @Test fun `distance and quaternion rotation`() {
        assertEquals(sqrt(1f + 1.5f * 1.5f + 4f), ArMoveMath.distance(camLookingDown(), 1f, 0f, -2f), 1e-5f)
        val a = Math.toRadians(90.0) / 2.0
        val r = ArMoveMath.rotate(0f, sin(a).toFloat(), 0f, cos(a).toFloat(), 1f, 0f, 0f)   // 90° about +Y
        assertEquals(0f, r[0], 1e-6f); assertEquals(-1f, r[2], 1e-6f)
    }

    @Test fun `view-relative nudges follow where the camera looks`() {
        val cam = camLookingDown()                       // looks toward world -Z = north (identity anchor)
        val away = ArMoveMath.viewNudgeToEnu(cam, identityAnchor(), rightM = 0f, forwardM = 0.1f)!!
        assertEquals(0f, away[0], 1e-6f); assertEquals(0.1f, away[1], 1e-6f); assertEquals(0f, away[2], 0f)
        val right = ArMoveMath.viewNudgeToEnu(cam, identityAnchor(), rightM = 0.1f, forwardM = 0f)!!
        assertEquals(0.1f, right[0], 1e-6f); assertEquals(0f, right[1], 1e-6f)   // looking north, right is east
        assertNull(ArMoveMath.viewNudgeToEnu(camLookingDown(pitchDownDeg = 90f), identityAnchor(), 0f, 0.1f))
    }

    @Test fun `session move adds on top of a saved placement`() {
        val saved = ModelPlacement(yawDeg = 10f, moveEastM = 1f)
        val p = saved + ModelMove(eastM = 0.5f, northM = -2f, upM = 0.25f, yawDeg = 5f)
        assertEquals(1.5f, p.moveEastM, 0f); assertEquals(-2f, p.moveNorthM, 0f)
        assertEquals(0.25f, p.moveUpM, 0f); assertEquals(15f, p.yawDeg, 0f)
        assertEquals(saved, saved + ModelMove())
    }
}
