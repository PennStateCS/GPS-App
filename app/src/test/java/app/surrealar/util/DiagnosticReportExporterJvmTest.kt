package app.surrealar.util

import android.Manifest
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.util.zip.ZipFile

/**
 * [DiagnosticReportExporter.buildReport] on the plain JVM with a mocked [Context] (no Hilt graph, no
 * framework services): app data collection falls back to empty data and every section that can be
 * built from the context still is. Only a local ZIP under the temp cache dir is written.
 */
class DiagnosticReportExporterJvmTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: Context
    private lateinit var cache: File

    @Before fun setUp() {
        cache = tmp.newFolder("cache")
        ctx = mock(Context::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageName).thenReturn("app.surrealar")
        `when`(ctx.cacheDir).thenReturn(cache)
        `when`(ctx.filesDir).thenReturn(tmp.newFolder("files"))
    }

    private fun deny(vararg perms: String) {
        `when`(ctx.checkPermission(anyString(), anyInt(), anyInt())).thenAnswer { if (it.arguments[0] in perms) -1 else 0 }
    }

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    @Test fun `report has summary, permission warnings and empty data sections`() = runBlocking {
        deny(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        val report = DiagnosticReportExporter.buildReport(ctx)
        assertNotNull(report)
        assertEquals(File(cache, "diagnostic_exports"), report!!.file.parentFile)
        val e = entries(report.file)

        val summary = e.getValue("diagnostic-summary.txt")
        assertTrue(summary, summary.contains("Report ID        : ${report.reportId}"))
        assertTrue(summary, summary.contains("⚠ PERMISSION: Camera denied — AR cannot start"))
        assertTrue(summary, summary.contains("⚠ PERMISSION: Fine location denied — GNSS/geospatial will not work"))
        assertTrue(summary, summary.contains("Package          : app.surrealar"))
        assertTrue(summary, summary.contains("Screen           : "))

        val perms = e.getValue("permissions-status.txt")
        assertTrue(perms, perms.contains("Camera (AR)              : DENIED"))
        assertTrue(perms, perms.contains("Coarse location          : GRANTED"))
        // No LocationManager on the mock: reported, not thrown.
        assertTrue(perms, perms.contains("Location services        : (unavailable:"))

        assertTrue(e.getValue("coordinates.txt").contains("(no coordinates link a model)"))
        assertTrue(e.getValue("model-integrity.txt").contains("(no models imported)"))

        val state = e.getValue("app-state-summary.txt")
        assertTrue(state, state.contains("=== App State Summary ==="))
        assertTrue(state, state.contains("Transport              : none"))

        assertTrue(e.getValue("map-diagnostics.txt").contains("=== Map Troubleshooting ==="))
        assertTrue(e.getValue("ar-session-summary.txt").isNotEmpty())
        assertTrue(e.containsKey("manifest.txt"))
    }

    @Test fun `all permissions granted gives only the log pointer warning`() = runBlocking {
        deny()
        val summary = entries(DiagnosticReportExporter.buildReport(ctx)!!.file).getValue("diagnostic-summary.txt")
        val warnings = summary.lines().filter { it.startsWith("⚠ ") }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].startsWith("⚠ Note: AR session, Earth-tracking"))
    }

    @Test fun `only the newest three earlier exports are kept`() = runBlocking {
        val dir = File(cache, "diagnostic_exports").apply { mkdirs() }
        (1..4).forEach { i -> File(dir, "old-$i.zip").apply { writeText("x"); setLastModified(5_000_000L + i * 1_000L) } }
        val report = DiagnosticReportExporter.buildReport(ctx)!!
        assertEquals(setOf("old-2.zip", "old-3.zip", "old-4.zip", report.file.name), dir.list()!!.toSet())
    }

    @Test fun `an unusable cache dir returns null instead of throwing`() = runBlocking {
        val notADir = File(tmp.root, "blocker").apply { writeText("file, not a dir") }
        `when`(ctx.cacheDir).thenReturn(notADir)
        assertEquals(null, DiagnosticReportExporter.buildReport(ctx))
    }
}
