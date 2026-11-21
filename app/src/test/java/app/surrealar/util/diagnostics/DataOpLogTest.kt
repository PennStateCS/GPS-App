package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataOpLogTest {
    @Test fun `done and failed lines`() {
        assertEquals("Export done format=csv rows=42 in 120ms", DataOpLog.doneLine("Export", "csv", 42, 120))
        assertEquals("Backup import failed format=json/merge after 5ms: IllegalArgumentException: bad", DataOpLog.failLine("Backup import", "json/merge", 5, IllegalArgumentException("bad")))
    }

    @Test fun `run returns the result and rethrows failures`() {
        assertEquals(3, DataOpLog.run<Int>("Export", "csv", { it }) { 3 })
        val e = runCatching { DataOpLog.run<Int>("Export", "csv", { it }) { throw IllegalStateException("disk full") } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test fun `database open lines`() {
        assertEquals("Database migrated v11 → v12 (40ms from build to open)", DbDiagnostics.openLine(11, 12, 40))
        assertEquals("Database opened v12 (8ms after build)", DbDiagnostics.openLine(12, 12, 8))
        assertEquals("Database opened v12 (first open recorded, 9ms after build)", DbDiagnostics.openLine(null, 12, 9))
    }
}