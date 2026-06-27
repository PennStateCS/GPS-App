package app.surrealar.settings.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [CoordinateDisplaySettings] defaults and value semantics.
 *
 * NOTE: this test was previously written against an older shape of the class
 * (`format`, `distanceUnits`, `showRtkStatusBadges`) which has since been removed. It was already
 * failing to compile before unrelated work; it is updated here to match the current data class.
 */
class CoordinateDisplaySettingsTest {

    @Test fun `defaults are as expected`() {
        val s = CoordinateDisplaySettings()
        assertTrue(s.showAccuracyIndicators)
        assertEquals("Point", s.defaultNamePrefix)
        assertTrue(s.autoIncrementNames)
    }

    @Test fun `showAccuracyIndicators can be disabled`() {
        assertFalse(CoordinateDisplaySettings(showAccuracyIndicators = false).showAccuracyIndicators)
    }

    @Test fun `autoIncrementNames can be disabled`() {
        assertFalse(CoordinateDisplaySettings(autoIncrementNames = false).autoIncrementNames)
    }

    @Test fun `default name prefix is configurable`() {
        assertEquals("BM", CoordinateDisplaySettings(defaultNamePrefix = "BM").defaultNamePrefix)
        assertEquals("", CoordinateDisplaySettings(defaultNamePrefix = "").defaultNamePrefix)
    }

    @Test fun `value equality holds for identical settings`() {
        assertEquals(CoordinateDisplaySettings(), CoordinateDisplaySettings())
        assertNotEquals(
            CoordinateDisplaySettings(defaultNamePrefix = "A"),
            CoordinateDisplaySettings(defaultNamePrefix = "B")
        )
    }
}
