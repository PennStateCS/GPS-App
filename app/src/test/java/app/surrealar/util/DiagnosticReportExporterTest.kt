package app.surrealar.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class DiagnosticReportExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `flush runs before anything is zipped`() = runTest {
        val events = mutableListOf<String>()
        val zip = File(tmp.root, "r.zip")
        DiagnosticReportExporter.writeZip(zip, flush = { events += "flush" }) { zos ->
            events += "zip"
            zos.putNextEntry(ZipEntry("a.txt")); zos.write("x".toByteArray()); zos.closeEntry()
        }
        assertEquals(listOf("flush", "zip"), events)
    }

    @Test fun `the default flush puts queued log lines in the zip`() = runTest {
        val dir = tmp.newFolder("logs")
        DiagnosticsLogger.initForTest(dir)
        repeat(200) { DiagnosticsLogger.i("APP", "queued line $it") }
        val zip = File(tmp.root, "r.zip")
        DiagnosticReportExporter.writeZip(zip) { zos ->
            DiagnosticsLogger.logFiles().forEach { f ->
                zos.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry()
            }
        }
        val text = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("app-log-current.txt")).reader().readText() }
        assertTrue(text.contains("queued line 199"))
    }
}