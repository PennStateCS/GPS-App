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
        val written = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = { true })
        val expected = listOf(
            "README.txt", "diagnostic-summary.txt", "timeline.txt", "sessions.txt", "exits.txt",
            "exits/20261121-133000-ANR.txt", "device-health.txt", "captures.txt", "gnss-stats.txt", "data-health.txt",
            "settings-snapshot.txt", "permissions-status.txt", "app-state-summary.txt",
            "coordinates.txt", "model-integrity.txt", "ar-session-summary.txt", "map-diagnostics.txt",
            "nmea-stream-diagnostics.txt", "app-log-current.txt", "app-errors-current.txt", "export-health.txt", "manifest.txt",
        )
        assertEquals(expected, names(zip))
        assertEquals(expected, written.entries.map { it.first })
    }

    private fun entry(zip: File, name: String) = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(name)).reader().readText() }

    @Test fun `over the size limit only the last 24 h of logs go in, and README and manifest say so`() = runTest {
        val old = "2026-11-19T10:00:00.000-05:00 W GNSS [main] two days old\n\tcontinuation of the old line\n"
        val src = FakeReportSources(tmp.newFolder("logs"), eventLog = old + FakeReportSources.DEFAULT_EVENT_LOG)
        val small = File(tmp.root, "small.zip")
        ReportAssembler.write(small, src, flush = { true }, maxLogBytes = 10)
        val log = entry(small, "app-log-current.txt")
        assertEquals(false, log.contains("two days old") || log.contains("continuation of the old line"))
        assertEquals(true, log.contains("Capture saved name=\"CP-1\""))
        assertEquals(true, entry(small, "README.txt").contains("only the last 24 hours"))
        assertEquals(true, entry(small, "manifest.txt").contains("truncated to the last 24 hours"))

        val full = File(tmp.root, "full.zip")
        ReportAssembler.write(full, src, flush = { true })
        assertEquals(true, entry(full, "app-log-current.txt").contains("two days old"))
        assertEquals(false, entry(full, "manifest.txt").contains("truncated"))
    }

    @Test fun `a failing section is replaced by its error, the rest is still written`() = runTest {
        val zip = File(tmp.root, "r.zip")
        val src = object : FakeReportSources(tmp.newFolder("logs")) {
            override suspend fun models(): String = throw IllegalStateException("db closed")
        }
        ReportAssembler.write(zip, src, flush = { true })
        val text = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("model-integrity.txt")).reader().readText() }
        assertEquals("Section failed to generate: IllegalStateException: db closed\n", text)
        assertEquals(22, names(zip).size)
    }
}