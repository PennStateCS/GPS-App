package app.surrealar.util.diagnostics.report

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class ManifestCollectorTest {
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)
    private val now = java.time.OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli()

    @Test fun `golden manifest`() {
        assertEquals("""
            === Manifest ===
            Report ID   : 7K3M-QX2D
            Report time : 2026-11-21T15:00:00.000-05:00

            README.txt                 2104 bytes
            app-log-current.txt      512000 bytes
            manifest.txt         (this file)

            3 files, 514104 bytes before compression (plus this file)
        """.trimIndent() + "\n", ManifestCollector.render(listOf("README.txt" to 2104L, "app-log-current.txt" to 512_000L), "7K3M-QX2D", now, false))
    }

    @Test fun `truncation is stated`() {
        assertTrue(ManifestCollector.render(emptyList(), "X", now, true).contains("truncated to the last 24 hours"))
    }
}