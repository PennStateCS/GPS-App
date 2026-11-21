package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class ReportAssemblerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun names(zip: File) = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }

    @Test fun `golden list of files in the zip`() = runTest {
        val zip = File(tmp.root, "r.zip")
        val written = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = {})
        val expected = listOf(
            "README.txt", "diagnostic-summary.txt", "timeline.txt", "sessions.txt", "settings-snapshot.txt", "permissions-status.txt", "app-state-summary.txt",
            "coordinates.txt", "model-integrity.txt", "ar-session-summary.txt", "map-diagnostics.txt",
            "nmea-stream-diagnostics.txt", "app-log-current.txt", "app-errors-current.txt",
        )
        assertEquals(expected, names(zip))
        assertEquals(expected, written.map { it.first })
    }

    @Test fun `a failing section is replaced by its error, the rest is still written`() = runTest {
        val zip = File(tmp.root, "r.zip")
        val src = object : FakeReportSources(tmp.newFolder("logs")) {
            override suspend fun models(): String = throw IllegalStateException("db closed")
        }
        ReportAssembler.write(zip, src, flush = {})
        val text = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("model-integrity.txt")).reader().readText() }
        assertEquals("Section failed to generate: IllegalStateException: db closed\n", text)
        assertEquals(14, names(zip).size)
    }
}