package app.surrealar.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import app.surrealar.ui.settings.DiagnosticExportUi.ExportFailure
import app.surrealar.util.diagnostics.report.ExportFailure as ReportFailure
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

    @Test fun `export outcomes map to friendly messages, never exception text`() {
        assertEquals(ExportFailure.STORAGE_FULL, DiagnosticExportUi.failureFor(ReportFailure.NO_SPACE))
        assertEquals(ExportFailure.WRITE_FAILED, DiagnosticExportUi.failureFor(ReportFailure.WRITE_FAILED))
        assertEquals(ExportFailure.VALIDATION_FAILED, DiagnosticExportUi.failureFor(ReportFailure.VALIDATION_FAILED))
        assertEquals(ExportFailure.CANCELLED, DiagnosticExportUi.failureFor(ReportFailure.CANCELLED))
        assertEquals(ExportFailure.UNKNOWN, DiagnosticExportUi.failureFor(ReportFailure.UNKNOWN))
        // Every reason has its own message resource.
        assertEquals(ReportFailure.values().size, ReportFailure.values().map { DiagnosticExportUi.failureFor(it).messageRes }.toSet().size)

        val result = app.surrealar.util.diagnostics.report.ReportResult(emptyList(),
            app.surrealar.util.diagnostics.report.ExportHealth(emptyList(), app.surrealar.util.diagnostics.report.FlushStatus(
                app.surrealar.util.diagnostics.report.FlushStatus.State.FLUSHED)))
        val ok = app.surrealar.util.diagnostics.report.ExportOutcome.Success(java.io.File("r.zip"), "R", partial = false, result = result)
        val partial = ok.copy(partial = true)
        val failed = app.surrealar.util.diagnostics.report.ExportOutcome.Failure(ReportFailure.NO_SPACE, "IOException: ENOSPC at /data/x")
        assertEquals(app.surrealar.R.string.diag_export_ready, DiagnosticExportUi.resultMessage(ok, null))
        assertEquals(app.surrealar.R.string.diag_export_ready_partial, DiagnosticExportUi.resultMessage(partial, null))
        assertEquals(app.surrealar.R.string.diag_export_failed_storage_full, DiagnosticExportUi.resultMessage(failed, null))
        assertEquals(app.surrealar.R.string.diag_export_failed_no_share_app, DiagnosticExportUi.resultMessage(ok, ExportFailure.NO_SHARE_APP))
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val text = ctx.getString(DiagnosticExportUi.resultMessage(failed, null))
        assertFalse(text, text.contains("IOException") || text.contains("/data"))
        assertTrue(ctx.getString(app.surrealar.R.string.diag_export_ready_partial).contains("export-health.txt"))
    }

    @Test fun `partial report share text says some sections are missing`() {
        val now = 1_764_000_000_000L
        val text = DiagnosticExportUi.shareText("R", null, now, zone, partial = true)
        assertTrue(text, text.contains("some sections could not be collected; details in export-health.txt"))
        assertFalse(DiagnosticExportUi.shareText("R", null, now, zone).contains("export-health.txt"))
    }

    @Test fun `mark time is HH-mm in the given zone`() {
        assertEquals("11:00", DiagnosticExportUi.markTime(1_764_000_000_000L, ZoneId.of("America/New_York")))
    }
}
