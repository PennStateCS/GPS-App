package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.ArViewQuality.Level
import app.surrealar.ui.openinar.ArViewQuality.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure AR-view-quality mapping that drives the on-screen quality banner/chip. Verifies the
 * level thresholds, the "unknown never 0.0" rule, and the placement-dependent wording.
 */
class ArViewQualityTest {

    private fun eval(
        earth: Boolean = true, camera: Boolean = true,
        h: Double? = 1.0, v: Double? = 1.0, yaw: Double? = 5.0,
        placement: Placement = Placement.PLACED,
    ) = ArViewQuality.evaluate(earth, camera, h, v, yaw, placement)

    @Test fun `not ready when earth tracking is off`() {
        assertEquals(Level.NOT_READY, eval(earth = false).level)
    }

    @Test fun `not ready when camera tracking is off`() {
        assertEquals(Level.NOT_READY, eval(camera = false).level)
    }

    @Test fun `unknown when horizontal accuracy is missing`() {
        val s = eval(h = null)
        assertEquals(Level.UNKNOWN, s.level)
        assertEquals("unknown", s.accuracyText)   // never 0.0
    }

    @Test fun `unknown when yaw is missing`() {
        assertEquals(Level.UNKNOWN, eval(yaw = null).level)
    }

    @Test fun `non-finite accuracy is treated as unknown, not zero`() {
        val s = eval(h = Double.NaN, yaw = Double.POSITIVE_INFINITY)
        assertEquals(Level.UNKNOWN, s.level)
        assertEquals("unknown", s.accuracyText)
    }

    @Test fun `good at or under 2m and 15deg`() {
        assertEquals(Level.GOOD, eval(h = 2.0, yaw = 15.0).level)
        assertEquals(Level.GOOD, eval(h = 1.2, yaw = 5.0).level)
    }

    @Test fun `fair between good and fair thresholds`() {
        assertEquals(Level.FAIR, eval(h = 3.8, yaw = 30.0).level)
        assertEquals(Level.FAIR, eval(h = 5.0, yaw = 35.0).level)
    }

    @Test fun `low when horizontal or yaw exceed fair`() {
        assertEquals(Level.LOW, eval(h = 8.5, yaw = 20.0).level)
        assertEquals(Level.LOW, eval(h = 3.0, yaw = 50.0).level)
    }

    @Test fun `poor vertical accuracy caps quality at low even with good horizontal and yaw`() {
        assertEquals(Level.LOW, eval(h = 1.0, yaw = 5.0, v = 25.0).level)
    }

    @Test fun `accuracy and heading text formatting`() {
        val s = eval(h = 4.83, yaw = 32.0)
        assertEquals("±4.8 m", s.accuracyText)
        assertEquals("±32°", s.headingText)
    }

    @Test fun `waiting shows localizing when not ready and improving when tracking`() {
        assertEquals("Localizing AR…", eval(earth = false, placement = Placement.WAITING).statusLabel)
        assertEquals("Improving AR accuracy…", eval(h = 9.0, placement = Placement.WAITING).statusLabel)
    }

    @Test fun `placed shows AR quality label and is renderable`() {
        val s = eval(h = 1.2, yaw = 5.0, placement = Placement.PLACED)
        assertEquals("AR quality: Good", s.statusLabel)
        assertTrue(s.modelsRenderable)
        assertNull(s.helperText)
    }

    @Test fun `placed by timeout carries the low-confidence helper`() {
        val s = eval(h = 4.0, yaw = 50.0, placement = Placement.PLACED_BY_TIMEOUT)
        assertTrue(s.modelsRenderable)
        assertEquals(ArViewQuality.HELPER_TIMEOUT, s.helperText)
    }

    @Test fun `low quality while waiting suggests the scan helper`() {
        assertEquals(ArViewQuality.HELPER_LOW, eval(h = 9.0, placement = Placement.WAITING).helperText)
    }

    @Test fun `waiting is not renderable`() {
        assertFalse(eval(placement = Placement.WAITING).modelsRenderable)
    }
}
