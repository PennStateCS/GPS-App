package com.example.surveyingapp.gnss.settings

import org.junit.Assert.*
import org.junit.Test

class CoordinateDisplaySettingsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test fun `defaults are sane`() {
        val s = CoordinateDisplaySettings()
        assertEquals("DECIMAL_DEGREES", s.format)
        assertEquals("METERS",          s.distanceUnits)
        assertTrue(s.showAccuracyIndicators)
        assertTrue(s.showRtkStatusBadges)
        assertEquals("Point", s.defaultNamePrefix)
        assertTrue(s.autoIncrementNames)
    }

    // ── Coordinate format ─────────────────────────────────────────────────────

    @Test fun `DECIMAL_DEGREES format is stored`() {
        assertEquals("DECIMAL_DEGREES", CoordinateDisplaySettings(format = "DECIMAL_DEGREES").format)
    }

    @Test fun `DEGREES_MINUTES format is stored`() {
        assertEquals("DEGREES_MINUTES", CoordinateDisplaySettings(format = "DEGREES_MINUTES").format)
    }

    @Test fun `DMS format is stored`() {
        assertEquals("DMS", CoordinateDisplaySettings(format = "DMS").format)
    }

    @Test fun `UTM format is stored`() {
        assertEquals("UTM", CoordinateDisplaySettings(format = "UTM").format)
    }

    // ── Distance units ────────────────────────────────────────────────────────

    @Test fun `METERS units is stored`() {
        assertEquals("METERS", CoordinateDisplaySettings(distanceUnits = "METERS").distanceUnits)
    }

    @Test fun `US_SURVEY_FEET units is stored`() {
        assertEquals("US_SURVEY_FEET", CoordinateDisplaySettings(distanceUnits = "US_SURVEY_FEET").distanceUnits)
    }

    @Test fun `FEET units is stored`() {
        assertEquals("FEET", CoordinateDisplaySettings(distanceUnits = "FEET").distanceUnits)
    }

    // ── Boolean flags ─────────────────────────────────────────────────────────

    @Test fun `can disable accuracy indicators`() {
        assertFalse(CoordinateDisplaySettings(showAccuracyIndicators = false).showAccuracyIndicators)
    }

    @Test fun `can disable RTK badges`() {
        assertFalse(CoordinateDisplaySettings(showRtkStatusBadges = false).showRtkStatusBadges)
    }

    @Test fun `can disable auto-increment`() {
        assertFalse(CoordinateDisplaySettings(autoIncrementNames = false).autoIncrementNames)
    }

    // ── Name prefix ───────────────────────────────────────────────────────────

    @Test fun `custom name prefix is stored`() {
        assertEquals("BM", CoordinateDisplaySettings(defaultNamePrefix = "BM").defaultNamePrefix)
    }

    @Test fun `empty name prefix is stored as-is (caller responsible for fallback)`() {
        assertEquals("", CoordinateDisplaySettings(defaultNamePrefix = "").defaultNamePrefix)
    }

    // ── Equality ──────────────────────────────────────────────────────────────

    @Test fun `two identical instances are equal`() {
        assertEquals(CoordinateDisplaySettings(), CoordinateDisplaySettings())
    }

    @Test fun `instances with different formats are not equal`() {
        assertNotEquals(
            CoordinateDisplaySettings(format = "DMS"),
            CoordinateDisplaySettings(format = "UTM")
        )
    }
}
