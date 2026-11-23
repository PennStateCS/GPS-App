package app.surrealar.util

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * [DiagnosticReportExporter.buildReport] end to end on a plain (non-Hilt) Application: the Hilt
 * entry point is unavailable, so app data collection falls back to empty data, and every section must
 * still be written from what the context can provide. Only builds a local ZIP; nothing is sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticReportExporterBuildReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    @Test fun `buildReport writes a zip with every summary section under the cache export dir`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        val report = DiagnosticReportExporter.buildReport(app)
        assertNotNull(report)
        report!!
        assertEquals(File(app.cacheDir, "diagnostic_exports"), report.file.parentFile)
        assertTrue(report.file.isFile)

        val e = entries(report.file)
        for (name in listOf("README.txt", "diagnostic-summary.txt", "permissions-status.txt", "app-state-summary.txt",
                "coordinates.txt", "model-integrity.txt", "device-health.txt", "manifest.txt")) {
            assertTrue("missing $name in ${e.keys}", e.containsKey(name))
        }

        val summary = e.getValue("diagnostic-summary.txt")
        assertTrue(summary, summary.contains("Report ID        : ${report.reportId}"))
        assertTrue(summary, summary.contains("PERMISSION: Camera denied"))
        assertTrue(summary, summary.contains("PERMISSION: Fine location denied"))
        assertTrue(summary, summary.contains("Note: AR session, Earth-tracking"))
        assertTrue(summary, summary.contains("App name         : SurReal AR"))

        val perms = e.getValue("permissions-status.txt")
        assertTrue(perms, perms.contains("Camera (AR)              : DENIED"))
        assertTrue(perms, perms.contains("Fine location (GNSS)     : DENIED"))
        assertTrue(perms, perms.contains("Post notifications"))
        assertTrue(perms, perms.contains("GPS provider enabled"))

        val coords = e.getValue("coordinates.txt")
        assertTrue(coords, coords.contains("Total coordinates        : 0"))
        assertTrue(coords, coords.contains("(no coordinates link a model)"))

        val models = e.getValue("model-integrity.txt")
        assertTrue(models, models.contains("Total models             : 0"))
        assertTrue(models, models.contains("(no models imported)"))

        val state = e.getValue("app-state-summary.txt")
        assertTrue(state, state.contains("=== App State Summary ==="))
        assertTrue(state, state.contains("--- Network ---"))
    }

    @Test fun `granted permissions produce no permission warnings`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        val report = DiagnosticReportExporter.buildReport(app)!!
        val e = entries(report.file)
        val summary = e.getValue("diagnostic-summary.txt")
        assertTrue(summary, !summary.contains("PERMISSION:"))
        assertTrue(e.getValue("permissions-status.txt").contains("Camera (AR)              : GRANTED"))
    }

    @Test fun `old exports beyond the newest three are pruned before a new one is written`() = runBlocking {
        val dir = File(app.cacheDir, "diagnostic_exports").apply { mkdirs() }
        val old = (1..5).map { i ->
            File(dir, "old-$i.zip").apply { writeText("x"); setLastModified(1_000_000L + i * 1_000L) }
        }
        val report = DiagnosticReportExporter.buildReport(app)!!
        val remaining = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("old-3.zip", "old-4.zip", "old-5.zip", report.file.name), remaining)
        assertTrue(old.take(2).none { it.exists() })
    }

    @Test fun `source mismatch warning is null when either side is unknown`() {
        assertNull(DiagnosticReportExporter.sourceMismatchWarning(null, ProviderChoice.INTERNAL, true))
        assertNull(DiagnosticReportExporter.sourceMismatchWarning(LocationSourceType.INTERNAL, null, true))
    }
}
