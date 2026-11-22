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

    @Test fun `keys not included on either side produce no line`() {
        assertEquals(emptyList<String>(), SettingsChangeLogger.changes(mapOf("unknown_key" to "a"), mapOf("unknown_key" to "b")))
    }

    @Test fun `typing is coalesced per key to first old and final new`() {
        val c = SettingsChangeLogger.Coalescer(windowMs = 2_000L)
        c.add(SettingsChangeLogger.changeList(mapOf("location_source" to "INTERNAL"), mapOf("location_source" to "EXTERNAL")), nowMs = 0L)
        c.add(SettingsChangeLogger.changeList(mapOf("location_source" to "EXTERNAL"), mapOf("location_source" to "REPLAY")), nowMs = 500L)
        assertEquals(emptyList<SettingsChangeLogger.Change>(), c.due(nowMs = 1_000L))
        val lines = c.due(nowMs = 2_600L).map(SettingsChangeLogger::line)
        assertEquals(listOf("Setting location_source: INTERNAL → REPLAY"), lines)
        assertEquals(true, c.isEmpty())
    }

    @Test fun `a key edited back within the window logs nothing`() {
        val c = SettingsChangeLogger.Coalescer(windowMs = 2_000L)
        c.add(SettingsChangeLogger.changeList(mapOf("location_source" to "INTERNAL"), mapOf("location_source" to "EXTERNAL")), nowMs = 0L)
        c.add(SettingsChangeLogger.changeList(mapOf("location_source" to "EXTERNAL"), mapOf("location_source" to "INTERNAL")), nowMs = 100L)
        assertEquals(emptyList<SettingsChangeLogger.Change>(), c.due(nowMs = 5_000L))
    }
}