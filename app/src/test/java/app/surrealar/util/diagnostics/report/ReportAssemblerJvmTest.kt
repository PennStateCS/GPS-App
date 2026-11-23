package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * [ReportAssembler] on the plain JVM (no Robolectric): the ZIP is built entirely from fake sources,
 * so the assembly order, fail-soft sources, the 24 h filter and the manifest can be checked directly.
 */
class ReportAssemblerJvmTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    @Test fun `flush runs first and the returned list matches the zip including manifest`() = runBlocking {
        var flushed = 0
        val zip = File(tmp.root, "r.zip")
        val written = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = { flushed++ })
        assertEquals(1, flushed)
        val e = entries(zip)
        assertEquals(written.map { it.first }, ZipFile(zip).use { z -> z.entries().toList().map { it.name } })
        assertEquals("manifest.txt", written.last().first)
        assertTrue(e.getValue("diagnostic-summary.txt").contains("Report ID        : 7K3M-QX2D"))
        assertTrue(e.getValue("coordinates.txt").contains("Total coordinates        : 3"))
        assertTrue(e.getValue("manifest.txt").contains("README.txt"))
        // Sizes recorded for each entry match the bytes in the zip (manifest itself excluded).
        written.dropLast(1).forEach { (name, size) ->
            assertEquals(name, size, e.getValue(name).toByteArray(Charsets.UTF_8).size.toLong())
        }
    }

    @Test fun `a throwing flush does not stop the export`() = runBlocking {
        val zip = File(tmp.root, "r.zip")
        val written = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = { error("disk") })
        assertTrue(written.any { it.first == "manifest.txt" })
    }

    @Test fun `throwing file sources are treated as empty and failing sections report their error`() = runBlocking {
        val dir = tmp.newFolder("logs")
        val src = object : FakeReportSources(dir) {
            override fun logFiles(): List<File> = error("no logs dir")
            override fun errorFiles(): List<File> = error("no errors dir")
            override fun exitTraceFiles(): List<File> = error("no exits")
            override fun arTrackFiles(): List<File> = error("no tracks")
            override fun liveState(): LiveState = error("feed down")
            override fun deviceHealth(): DeviceHealth = error("no health")
            override suspend fun nmeaStream(): String = throw IllegalArgumentException("nmea gone")
        }
        val zip = File(tmp.root, "r.zip")
        val names = ReportAssembler.write(zip, src, flush = {}).map { it.first }
        assertFalse(names.contains("app-log-current.txt"))
        assertFalse(names.contains("app-errors-current.txt"))
        assertFalse(names.any { it.startsWith("exits/") })
        val e = entries(zip)
        assertTrue(e.getValue("diagnostic-summary.txt").startsWith("=== First look ===\n(failed: feed down)"))
        assertEquals("Section failed to generate: IllegalStateException: no health\n", e.getValue("device-health.txt"))
        assertEquals("Section failed to generate: IllegalArgumentException: nmea gone\n", e.getValue("nmea-stream-diagnostics.txt"))
    }

    @Test fun `AR track files are copied after the logs`() = runBlocking {
        val dir = tmp.newFolder("logs")
        val track = File(dir, "ar-track-1.csv").apply { writeText("t,east,north\n0,0.0,0.0\n") }
        val src = object : FakeReportSources(dir) {
            override fun arTrackFiles(): List<File> = listOf(track)
        }
        val zip = File(tmp.root, "r.zip")
        val names = ReportAssembler.write(zip, src, flush = {}).map { it.first }
        assertEquals(listOf("app-errors-current.txt", "ar-track-1.csv", "manifest.txt"), names.takeLast(3))
        assertEquals("t,east,north\n0,0.0,0.0\n", entries(zip).getValue("ar-track-1.csv"))
    }

    @Test fun `last24hFilter keeps recent lines and their continuations only`() {
        val now = java.time.OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli()
        val keep = ReportAssembler.last24hFilter(now)
        // A continuation before any parsable line is dropped.
        assertFalse(keep("\torphan continuation"))
        assertFalse(keep("2026-11-19T10:00:00.000-05:00 W GNSS [main] old"))
        assertFalse(keep("\tat old.Frame"))
        assertTrue(keep("2026-11-21T14:00:00.000-05:00 I APP [main] recent"))
        assertTrue(keep("\tat recent.Frame"))
    }

    @Test fun `logs are filtered to 24 h only when over the byte limit`() = runBlocking {
        val old = "2026-11-19T10:00:00.000-05:00 W GNSS [main] two days old\n"
        val src = FakeReportSources(tmp.newFolder("logs"), eventLog = old + FakeReportSources.DEFAULT_EVENT_LOG)
        val small = File(tmp.root, "small.zip")
        ReportAssembler.write(small, src, flush = {}, maxLogBytes = 1)
        assertFalse(entries(small).getValue("app-log-current.txt").contains("two days old"))
        val big = File(tmp.root, "big.zip")
        ReportAssembler.write(big, src, flush = {}, maxLogBytes = Long.MAX_VALUE)
        assertTrue(entries(big).getValue("app-log-current.txt").contains("two days old"))
    }
}
