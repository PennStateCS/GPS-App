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
        trackingFailure: String? = null,
        vpsAvailable: Boolean? = null,
    ) = ArViewQuality.evaluate(earth, camera, h, v, yaw, placement, trackingFailure, vpsAvailable)

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

    @Test fun `placed by timeout at fair quality carries the low-confidence helper`() {
        val s = eval(h = 4.0, yaw = 20.0, placement = Placement.PLACED_BY_TIMEOUT)
        assertTrue(s.modelsRenderable)
        assertEquals(Level.FAIR, s.level)
        assertEquals(ArViewQuality.HELPER_TIMEOUT, s.helperText)
    }

    @Test fun `low quality says what is poor, while waiting and after placement`() {
        // Field case: ARCore at ±10 m (no visual localisation) → point at buildings.
        assertEquals(ArViewQuality.HELPER_POSITION, eval(h = 9.0, placement = Placement.WAITING).helperText)
        assertEquals(ArViewQuality.HELPER_POSITION, eval(h = 10.2, yaw = 7.0, placement = Placement.PLACED_BY_TIMEOUT).helperText)
        // Poor vertical alone also means position.
        assertEquals(ArViewQuality.HELPER_POSITION, eval(h = 1.0, v = 11.0, placement = Placement.PLACED).helperText)
        // Position fine, heading poor → pan.
        assertEquals(ArViewQuality.HELPER_HEADING, eval(h = 1.5, yaw = 50.0, placement = Placement.PLACED).helperText)
        // Starting up → generic hint.
        assertEquals(ArViewQuality.HELPER_LOW, eval(earth = false, placement = Placement.WAITING).helperText)
    }

    @Test fun `camera tracking failure gives the specific fix and wins over accuracy hints`() {
        assertEquals(ArViewQuality.HELPER_DARK, eval(camera = false, trackingFailure = "INSUFFICIENT_LIGHT").helperText)
        assertEquals(ArViewQuality.HELPER_MOTION, eval(camera = false, trackingFailure = "EXCESSIVE_MOTION").helperText)
        assertEquals(ArViewQuality.HELPER_FEATURES, eval(camera = false, trackingFailure = "INSUFFICIENT_FEATURES").helperText)
        assertEquals(ArViewQuality.HELPER_BAD_STATE, eval(camera = false, trackingFailure = "BAD_STATE").helperText)
        assertEquals(ArViewQuality.HELPER_CAMERA, eval(camera = false, trackingFailure = "CAMERA_UNAVAILABLE").helperText)
        assertEquals(ArViewQuality.HELPER_MOTION, eval(h = 9.0, trackingFailure = "EXCESSIVE_MOTION").helperText)
        // NONE / unknown fall through to the normal hints.
        assertEquals(ArViewQuality.HELPER_LOW, eval(camera = false, trackingFailure = "NONE").helperText)
        assertEquals(ArViewQuality.HELPER_LOW, eval(camera = false, trackingFailure = "SOMETHING_NEW").helperText)
    }

    @Test fun `VPS result replaces the coverage guess`() {
        assertEquals(ArViewQuality.HELPER_NO_VPS, eval(h = 10.0, vpsAvailable = false).helperText)
        assertEquals(ArViewQuality.HELPER_POSITION_VPS, eval(h = 10.0, vpsAvailable = true).helperText)
        assertEquals(ArViewQuality.HELPER_POSITION, eval(h = 10.0, vpsAvailable = null).helperText)
        // Heading-only problem is unaffected by VPS.
        assertEquals(ArViewQuality.HELPER_HEADING, eval(h = 1.5, yaw = 50.0, vpsAvailable = false).helperText)
    }

    @Test fun `waiting is not renderable`() {
        assertFalse(eval(placement = Placement.WAITING).modelsRenderable)
    }
}
