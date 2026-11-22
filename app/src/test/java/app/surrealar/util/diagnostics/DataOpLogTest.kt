package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataOpLogTest {
    @Test fun `done and failed lines`() {
        assertEquals("Export completed format=csv rows=42 durationMs=120", DataOpLog.doneLine("Export", "csv", 42, 120))
        assertEquals("Export completed format=csv rows=unknown durationMs=7", DataOpLog.doneLine("Export", "csv", null, 7))
        assertEquals("Backup import failed format=json/merge durationMs=5", DataOpLog.failLine("Backup import", "json/merge", 5, IllegalArgumentException("bad")))
    }

    @Test fun `run returns the result and rethrows failures`() {
        assertEquals(3, DataOpLog.run<Int>("Export", "csv", { it }) { 3 })
        val e = runCatching { DataOpLog.run<Int>("Export", "csv", { it }) { throw IllegalStateException("disk full") } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test fun `database open lines`() {
        assertEquals("Database migrated v11 → v12 buildToOpenMs=40", DbDiagnostics.openLine(11, 12, 40))
        assertEquals("Database opened version=12 buildToOpenMs=8", DbDiagnostics.openLine(12, 12, 8))
        assertEquals("Database opened version=12 firstRecorded=true buildToOpenMs=9", DbDiagnostics.openLine(null, 12, 9))
    }
}