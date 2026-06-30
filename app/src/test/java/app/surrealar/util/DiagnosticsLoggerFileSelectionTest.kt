package app.surrealar.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the file-selection logic that decides which DiagnosticsLogger files the diagnostic export
 * bundles: that the latest error/crash log is included, the rotated event logs are ordered (current
 * first), empty files are excluded, and the session summary is found. This guards the export against
 * silently omitting the most recent evidence.
 *
 * Only the synchronous read side (`logFiles`/`errorFiles`/`sessionSummaryFile`) is exercised — the
 * async file writers are intentionally not used, so the test is deterministic (no timing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsLoggerFileSelectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun setUp() {
        DiagnosticsLogger.init(context)
        // Use the logger's ACTUAL directory (init may have run earlier from Application.onCreate
        // against a different context), not a guessed path.
        dir = DiagnosticsLogger.currentLogDir()!!.apply { mkdirs() }
        // Start from a clean slate for the specific files this test controls.
        dir.listFiles()?.forEach { it.delete() }
    }

    @Test fun `logFiles lists current first then rotated and excludes empty files`() {
        File(dir, "app-log-current.txt").writeText("current events")
        File(dir, "app-log-1.txt").writeText("older events")
        File(dir, "app-log-2.txt").writeText("")   // empty rotated file → must be excluded

        val names = DiagnosticsLogger.logFiles().map { it.name }
        assertEquals("app-log-current.txt", names.first())          // current preserved + first
        assertTrue(names.contains("app-log-1.txt"))
        assertFalse("empty files must be excluded", names.contains("app-log-2.txt"))
    }

    @Test fun `errorFiles includes the latest crash log so it is never lost`() {
        File(dir, "app-errors-current.txt").writeText("FATAL boom")
        File(dir, "app-errors-1.txt").writeText("older crash")

        val names = DiagnosticsLogger.errorFiles().map { it.name }
        assertEquals("app-errors-current.txt", names.first())
        assertTrue(names.contains("app-errors-1.txt"))
    }

    @Test fun `errorFiles is empty and safe when no crash has occurred`() {
        assertTrue(DiagnosticsLogger.errorFiles().isEmpty())   // no crash files present → no crash
    }

    @Test fun `sessionSummaryFile returns a written summary and null when absent`() {
        assertNull(DiagnosticsLogger.sessionSummaryFile("ar-last-session.txt"))
        File(dir, "ar-last-session.txt").writeText("=== AR last session ===")
        val f = DiagnosticsLogger.sessionSummaryFile("ar-last-session.txt")
        assertTrue(f != null && f.exists())
        assertNull(DiagnosticsLogger.sessionSummaryFile("does-not-exist.txt"))
    }
}
