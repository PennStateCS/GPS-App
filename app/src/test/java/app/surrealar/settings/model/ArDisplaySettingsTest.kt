package app.surrealar.settings.model

import org.junit.Assert.*
import org.junit.Test

class ArDisplaySettingsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test fun `defaults are sane`() {
        val s = ArDisplaySettings()
        assertEquals("STORED", s.altitudeMode)
        assertEquals(1,        s.distanceFilterIndex)
        assertFalse(s.showDebugOverlay)
        assertTrue(s.showLabels)
        assertTrue(s.showOffscreenArrows)
        assertEquals(2.0f, s.modelScale, 0.0001f)
    }

    // ── Altitude mode ─────────────────────────────────────────────────────────

    @Test fun `altitude mode STORED is accepted`() {
        val s = ArDisplaySettings(altitudeMode = "STORED")
        assertEquals("STORED", s.altitudeMode)
    }

    @Test fun `altitude mode TERRAIN is accepted`() {
        val s = ArDisplaySettings(altitudeMode = "TERRAIN")
        assertEquals("TERRAIN", s.altitudeMode)
    }

    // ── Distance filter index ─────────────────────────────────────────────────

    @Test fun `distance filter index 0 (all) is stored verbatim`() {
        val s = ArDisplaySettings(distanceFilterIndex = 0)
        assertEquals(0, s.distanceFilterIndex)
    }

    @Test fun `distance filter index 3 (max) is stored verbatim`() {
        val s = ArDisplaySettings(distanceFilterIndex = 3)
        assertEquals(3, s.distanceFilterIndex)
    }

    // ── Boolean flags ─────────────────────────────────────────────────────────

    @Test fun `can enable debug overlay`() {
        assertTrue(ArDisplaySettings(showDebugOverlay = true).showDebugOverlay)
    }

    @Test fun `can disable labels`() {
        assertFalse(ArDisplaySettings(showLabels = false).showLabels)
    }

    @Test fun `can disable offscreen arrows`() {
        assertFalse(ArDisplaySettings(showOffscreenArrows = false).showOffscreenArrows)
    }

    // ── Model scale ───────────────────────────────────────────────────────────

    @Test fun `minimum model scale 0_1 is accepted`() {
        val s = ArDisplaySettings(modelScale = 0.1f)
        assertEquals(0.1f, s.modelScale, 0.0001f)
    }

    @Test fun `maximum model scale 10_0 is accepted`() {
        val s = ArDisplaySettings(modelScale = 10.0f)
        assertEquals(10.0f, s.modelScale, 0.0001f)
    }

    // ── Equality ──────────────────────────────────────────────────────────────

    @Test fun `two identical instances are equal`() {
        assertEquals(ArDisplaySettings(), ArDisplaySettings())
    }

    @Test fun `instances with different scales are not equal`() {
        assertNotEquals(ArDisplaySettings(modelScale = 1.0f), ArDisplaySettings(modelScale = 2.0f))
    }
}
