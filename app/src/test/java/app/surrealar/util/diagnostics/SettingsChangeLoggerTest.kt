package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsChangeLoggerTest {
    @Test fun `changed, added and removed keys with redaction`() {
        val old = mapOf("location_source" to "INTERNAL", "ntrip_password" to "old-secret", "keep_screen_awake" to true)
        val new = mapOf("location_source" to "EXTERNAL", "ntrip_password" to "new-secret", "external_tcp_port" to 9001,
            "keep_screen_awake" to true, "unknown_key" to "x")
        val lines = SettingsChangeLogger.changes(old, new)
        assertEquals(listOf(
            "Setting external_tcp_port: (default) → 9001",
            "Setting location_source: INTERNAL → EXTERNAL",
            "Setting ntrip_password: (set) → (set) (changed)",
            "Setting unknown_key: (default) → (not included)",
        ), lines)
        assertFalse(lines.any { it.contains("secret") })
        assertEquals(listOf("Setting location_source: EXTERNAL → (default)"),
            SettingsChangeLogger.changes(mapOf("location_source" to "EXTERNAL"), emptyMap()))
    }
}