package app.surrealar.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import app.surrealar.ui.settings.DiagnosticExportUi.ExportFailure
import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentMarker
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticExportUiTest {

    private val plenty = 10L * 1024 * 1024 * 1024
    private val zone = ZoneId.of("UTC")

    @Test fun `failures map to friendly categories`() {
        assertEquals(ExportFailure.CANCELLED, DiagnosticExportUi.classify(CancellationException("x"), plenty))
        assertEquals(ExportFailure.NO_SHARE_APP, DiagnosticExportUi.classify(ActivityNotFoundException(), plenty))
        assertEquals(ExportFailure.STORAGE_FULL,
            DiagnosticExportUi.classify(IOException("write failed: ENOSPC (No space left on device)"), plenty))
        assertEquals(ExportFailure.STORAGE_FULL,
            DiagnosticExportUi.classify(RuntimeException("zip", IOException("No space left on device")), plenty))
        assertEquals(ExportFailure.STORAGE_FULL, DiagnosticExportUi.classify(null, 1024L))
        assertEquals(ExportFailure.WRITE_FAILED, DiagnosticExportUi.classify(IOException("EACCES"), plenty))
        assertEquals(ExportFailure.UNKNOWN, DiagnosticExportUi.classify(IllegalStateException("?"), plenty))
        assertEquals(ExportFailure.UNKNOWN, DiagnosticExportUi.classify(null, plenty))
        assertEquals(ExportFailure.UNKNOWN, DiagnosticExportUi.classify(null, null))
    }

    private fun marker(epochMs: Long, note: String?) = IncidentMarker(
        id = "AB12CD34", epochMs = epochMs, utcTime = "", localTime = "", zoneId = "UTC", sessionId = "s",
        appVersion = "1", screen = "settingsFragment", description = note, context = IncidentContext(),
    )

    @Test fun `share text asks the three questions and adds a recent incident note`() {
        val now = 1_764_000_000_000L   // 16:00 UTC
        val text = DiagnosticExportUi.shareText("7K3M-QX2D", marker(now - 30 * 60_000L, "AR models vanished"), now, zone)
        assertTrue(text, text.contains("What were you doing?"))
        assertTrue(text, text.contains("What did you expect?"))
        assertTrue(text, text.contains("What happened instead?"))
        assertTrue(text, text.contains("Report ID: 7K3M-QX2D"))
        assertTrue(text, text.contains("Problem marked at 15:30 (incident AB12CD34)"))
        assertTrue(text, text.contains("Note: AR models vanished"))

        val noNote = DiagnosticExportUi.shareText("R", marker(now - 60_000L, null), now, zone)
        assertTrue(noNote.contains("Problem marked at 15:59"))
        assertFalse(noNote.contains("Note:"))

        val old = DiagnosticExportUi.shareText("R", marker(now - 2 * DiagnosticExportUi.RECENT_INCIDENT_MS, "old"), now, zone)
        assertFalse(old.contains("Problem marked"))
        assertEquals(DiagnosticExportUi.shareText("R", null, now, zone), old)
    }

    @Test fun `mark time is HH-mm in the given zone`() {
        assertEquals("11:00", DiagnosticExportUi.markTime(1_764_000_000_000L, ZoneId.of("America/New_York")))
    }
}
