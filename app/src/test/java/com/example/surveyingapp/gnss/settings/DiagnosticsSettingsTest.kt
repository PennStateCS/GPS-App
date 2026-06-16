package com.example.surveyingapp.gnss.settings

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsSettingsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test fun `defaults are sane`() {
        val s = DiagnosticsSettings()
        assertFalse(s.nmeaLoggingEnabled)
        assertEquals(5, s.nmeaLogMaxFileSizeMB)
        assertFalse(s.showRawNmea)
        assertFalse(s.showSatelliteDiagnostics)
        assertFalse(s.logRawNmeaLines)
        assertFalse(s.logParsedFixes)
        assertFalse(s.logSatelliteDetails)
    }

    // ── NMEA file logging ─────────────────────────────────────────────────────

    @Test fun `can enable NMEA logging`() {
        assertTrue(DiagnosticsSettings(nmeaLoggingEnabled = true).nmeaLoggingEnabled)
    }

    @Test fun `minimum log file size 1 MB is accepted`() {
        assertEquals(1, DiagnosticsSettings(nmeaLogMaxFileSizeMB = 1).nmeaLogMaxFileSizeMB)
    }

    @Test fun `maximum log file size 100 MB is accepted`() {
        assertEquals(100, DiagnosticsSettings(nmeaLogMaxFileSizeMB = 100).nmeaLogMaxFileSizeMB)
    }

    // ── Live diagnostics panels ───────────────────────────────────────────────

    @Test fun `can show raw NMEA panel`() {
        assertTrue(DiagnosticsSettings(showRawNmea = true).showRawNmea)
    }

    @Test fun `can show satellite diagnostics panel`() {
        assertTrue(DiagnosticsSettings(showSatelliteDiagnostics = true).showSatelliteDiagnostics)
    }

    // ── Advanced Logcat logging ───────────────────────────────────────────────

    @Test fun `can enable raw NMEA line logging`() {
        assertTrue(DiagnosticsSettings(logRawNmeaLines = true).logRawNmeaLines)
    }

    @Test fun `can enable parsed-fix logging`() {
        assertTrue(DiagnosticsSettings(logParsedFixes = true).logParsedFixes)
    }

    @Test fun `can enable satellite-detail logging`() {
        assertTrue(DiagnosticsSettings(logSatelliteDetails = true).logSatelliteDetails)
    }

    @Test fun `all advanced logs off by default prevents logcat flooding`() {
        val s = DiagnosticsSettings()
        assertFalse("logRawNmeaLines must default off",   s.logRawNmeaLines)
        assertFalse("logParsedFixes must default off",    s.logParsedFixes)
        assertFalse("logSatelliteDetails must default off", s.logSatelliteDetails)
    }

    // ── Equality ──────────────────────────────────────────────────────────────

    @Test fun `two identical instances are equal`() {
        assertEquals(DiagnosticsSettings(), DiagnosticsSettings())
    }

    @Test fun `instances with different log size are not equal`() {
        assertNotEquals(
            DiagnosticsSettings(nmeaLogMaxFileSizeMB = 5),
            DiagnosticsSettings(nmeaLogMaxFileSizeMB = 10)
        )
    }

    @Test fun `enabling logging makes instances unequal`() {
        assertNotEquals(
            DiagnosticsSettings(nmeaLoggingEnabled = false),
            DiagnosticsSettings(nmeaLoggingEnabled = true)
        )
    }
}
