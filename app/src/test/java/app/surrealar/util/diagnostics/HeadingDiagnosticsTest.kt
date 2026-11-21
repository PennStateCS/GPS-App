package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingDiagnosticsTest {
    @Test fun `heading source descriptions`() {
        assertEquals("rotation vector + declination (LIVE_FIX)", HeadingDiagnostics.describe(null, "LIVE_FIX", false))
        assertEquals("rotation vector, magnetic only (no declination)", HeadingDiagnostics.describe("COMPASS", "NONE", true))
        assertEquals("GNSS course over ground", HeadingDiagnostics.describe("COURSE_OVER_GROUND", "LIVE_FIX", false))
        assertEquals("north-up (no heading)", HeadingDiagnostics.describe("NORTH_UP", "NONE", true))
    }

    @Test fun `accuracy names`() {
        assertEquals(listOf("UNRELIABLE", "LOW", "MEDIUM", "HIGH"), (0..3).map(HeadingDiagnostics::accuracyName))
    }
}