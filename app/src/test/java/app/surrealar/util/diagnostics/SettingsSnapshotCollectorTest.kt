package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSnapshotCollectorTest {

    private val out = SettingsSnapshotCollector.render(
        mapOf(
            "location_source" to "EXTERNAL",
            "external_tcp_port" to 9001,
            "ntrip_password" to "hunter2",
            "ntrip_user" to "",
            "cloud_api_key" to "AIzaSyFAKEFAKEFAKE",
            "some_future_setting" to "secret-ish",
            "ar_visible_ids" to setOf("1", "2", "3"),
        ),
        extras = listOf("maps_api_key" to SettingsRedaction.describe("maps_api_key", "AIzaSyREAL")),
    )

    private fun line(key: String) = out.lines().first { it.startsWith("$key ") }.substringAfter(" : ")

    @Test fun `changed, default, hidden and redacted keys`() {
        assertEquals("EXTERNAL  [changed]", line("location_source"))
        assertEquals("9001  [changed]", line("external_tcp_port"))
        assertEquals("(default)", line("stakeout_tolerance_m"))
        assertEquals("(not included)  [changed]", line("some_future_setting"))
        assertEquals("(set)  [changed]", line("ntrip_password"))
        assertEquals("(empty)  [changed]", line("ntrip_user"))
        assertEquals("(set)  [changed]", line("cloud_api_key"))
        assertEquals("(set)", line("maps_api_key"))
        assertEquals("3 item(s)  [changed]", line("ar_visible_ids"))
    }

    @Test fun `no secret value appears and the stale CRS note is gone`() {
        listOf("hunter2", "AIzaSy", "secret-ish").forEach { assertFalse(it, out.contains(it)) }
        assertFalse(out.contains("not persisted in this build"))
        assertTrue(out.contains("coord_horizontal_system"))
    }

    @Test fun `every allowlisted key is listed once`() {
        SettingsRedaction.ALLOWLIST.forEach { k -> assertEquals(k, 1, out.lines().count { it.startsWith("$k ") }) }
    }
}