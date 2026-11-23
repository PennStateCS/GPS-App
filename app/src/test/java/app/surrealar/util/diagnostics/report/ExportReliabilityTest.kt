package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Export reliability on the plain JVM: section statuses and export-health.txt, timeouts, cancellation,
 * the `.part` → validate → rename flow, write failures and entry-name sanitising. All from fakes.
 */
class ExportReliabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    private fun status(r: ReportResult, name: String) = r.health.sections.single { it.name == name }

    /** Passes bytes to the file until [limit], then throws [error]. */
    private fun failingAfter(limit: Long, error: () -> Exception): (File) -> OutputStream = { f ->
        object : FilterOutputStream(FileOutputStream(f)) {
            var n = 0L
            override fun write(b: Int) { if (++n > limit) throw error(); out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (n + len > limit) throw error()
                n += len; out.write(b, off, len)
            }
        }
    }

    // ── Section statuses / export-health.txt ──

    @Test fun `all sections succeed gives a COMPLETE health file before the manifest`() = runTest {
        val r = ReportAssembler.write(File(tmp.root, "r.zip"), FakeReportSources(tmp.newFolder("logs")), flush = { true })
        assertFalse(r.health.problems.toString(), r.partial)
        assertEquals(ExportHealth.Verdict.COMPLETE, r.health.verdict)
        assertEquals(FlushStatus.State.FLUSHED, r.health.flush.state)
        assertEquals(listOf("export-health.txt", "manifest.txt"), r.entries.takeLast(2).map { it.first })
        val health = entries(File(tmp.root, "r.zip")).getValue("export-health.txt")
        assertTrue(health, health.contains("Verdict   : COMPLETE"))
        assertTrue(health, health.contains("Log flush : FLUSHED"))
        assertTrue(health, health.lines().any { it.trim().startsWith("model-integrity.txt") && it.contains("OK") })
        assertTrue(r.completenessSummary().contains("Verdict  : COMPLETE"))
        // AR tracks are debug-only: listed as unavailable, not a problem.
        assertEquals(SectionStatus.State.UNAVAILABLE, status(r, "source: AR track files").state)
    }

    @Test fun `one throwing source is FAILED with its type, the other sections and the summary are still written`() = runTest {
        val src = FakeReportSources(tmp.newFolder("logs"),
            failing = mapOf(FakeReportSources.Keys.MODELS to IllegalStateException("db closed at /data/user/0/x lat=41.123456789")))
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, src, flush = { true })
        val s = status(r, "model-integrity.txt")
        assertEquals(SectionStatus.State.FAILED, s.state)
        assertTrue(s.detail!!, s.detail!!.startsWith("IllegalStateException: db closed"))
        assertFalse("raw coordinate must be redacted: ${s.detail}", s.detail!!.contains("41.123456789"))
        assertTrue(r.partial)
        val e = entries(zip)
        assertTrue(e.getValue("diagnostic-summary.txt").contains("Report ID        : 7K3M-QX2D"))
        assertTrue(e.getValue("coordinates.txt").contains("Total coordinates"))
        assertTrue(e.getValue("model-integrity.txt").startsWith("Section failed to generate: IllegalStateException"))
        val health = e.getValue("export-health.txt")
        assertTrue(health, health.contains("Verdict   : PARTIAL"))
        assertTrue(health, health.lines().any { it.contains("model-integrity.txt") && it.contains("FAILED") && it.contains("IllegalStateException") })
        assertTrue(r.completenessSummary().contains("model-integrity.txt FAILED (IllegalStateException)"))
    }

    @Test fun `failure messages are redacted and capped`() {
        val d = ReportFailures.describe(RuntimeException("password=hunter2 " + "x".repeat(500)))
        assertTrue(d, d.startsWith("RuntimeException: password=(redacted)"))
        assertTrue(d.length <= "RuntimeException: ".length + ReportFailures.MAX_MESSAGE_CHARS + 1)
        assertEquals("IOException", ReportFailures.describe(IOException()))
    }

    @Test fun `a source that never answers is TIMED_OUT after the section timeout`() = runTest {
        val src = FakeReportSources(tmp.newFolder("logs"), hanging = setOf(FakeReportSources.Keys.MAP))
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, src, flush = { true }, sectionTimeoutMs = 2_000)
        assertEquals(SectionStatus.State.TIMED_OUT, status(r, "map-diagnostics.txt").state)
        assertTrue(testScheduler.currentTime >= 2_000)       // virtual time: waited the timeout, no longer
        assertTrue(testScheduler.currentTime < 4_000)
        assertEquals("Section timed out after 2000 ms\n", entries(zip).getValue("map-diagnostics.txt"))
        assertEquals(SectionStatus.State.OK, status(r, "nmea-stream-diagnostics.txt").state)
        assertTrue(r.partial)
    }

    @Test fun `flush returning false or throwing is recorded`() = runTest {
        val notConfirmed = ReportAssembler.write(File(tmp.root, "a.zip"), FakeReportSources(tmp.newFolder("a")), flush = { false })
        assertEquals(FlushStatus.State.NOT_CONFIRMED, notConfirmed.health.flush.state)
        assertTrue(notConfirmed.partial)
        assertTrue(entries(File(tmp.root, "a.zip")).getValue("export-health.txt").contains("Log flush : NOT_CONFIRMED"))

        val threw = ReportAssembler.write(File(tmp.root, "b.zip"), FakeReportSources(tmp.newFolder("b")), flush = { throw IOException("disk") })
        assertEquals(FlushStatus.State.FAILED, threw.health.flush.state)
        assertEquals("IOException: disk", threw.health.flush.detail)
    }

    @Test fun `a log file that cannot be read is omitted and recorded`() = runTest {
        val dir = tmp.newFolder("logs")
        val missing = File(dir, "app-log-1.txt")             // listed but not there
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, FakeReportSources(dir, extraLogFiles = listOf(missing)), flush = { true })
        assertFalse(r.entries.any { it.first == "app-log-1.txt" })
        assertTrue(r.entries.any { it.first == "app-log-current.txt" })
        val omitted = r.health.omitted.single()
        assertEquals("app-log-1.txt", omitted.name)
        assertTrue(omitted.reason, omitted.reason.startsWith("unreadable: FileNotFoundException"))
        assertEquals(SectionStatus.State.FAILED, status(r, "log index").state)
        assertTrue(r.partial)
        val health = entries(zip).getValue("export-health.txt")
        assertTrue(health, health.contains("app-log-1.txt  unreadable: FileNotFoundException"))
    }

    @Test fun `logs filtered to 24 h are listed as expected truncation with both sizes`() = runTest {
        val old = "2026-11-19T10:00:00.000-05:00 W GNSS [main] two days old\n"
        val src = FakeReportSources(tmp.newFolder("logs"), eventLog = old + FakeReportSources.DEFAULT_EVENT_LOG)
        val r = ReportAssembler.write(File(tmp.root, "r.zip"), src, flush = { true }, maxLogBytes = 1)
        val t = r.health.truncated.first { it.name == "app-log-current.txt" }
        assertTrue(t.expected)
        assertTrue("${t.originalBytes} > ${t.includedBytes}", t.originalBytes > t.includedBytes)
        assertEquals(r.entries.first { it.first == "app-log-current.txt" }.second, t.includedBytes)
        assertFalse("policy truncation is not a problem", r.partial)
    }

    @Test fun `throwing file-list sources are FAILED, empty ones UNAVAILABLE`() = runTest {
        val src = object : FakeReportSources(tmp.newFolder("logs")) {
            override fun exitTraceFiles(): List<File> = throw SecurityException("no access")
            override fun errorFiles(): List<File> = emptyList()
        }
        val r = ReportAssembler.write(File(tmp.root, "r.zip"), src, flush = { true })
        assertEquals(SectionStatus.State.FAILED, status(r, "source: exit traces").state)
        assertEquals("SecurityException: no access", status(r, "source: exit traces").detail)
        assertEquals(SectionStatus.State.UNAVAILABLE, status(r, "source: error files").state)
    }

    @Test fun `manifest lists exactly the entries in the zip`() = runTest {
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = { true })
        val actual = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        val manifest = entries(zip).getValue("manifest.txt")
        val listed = manifest.lines().filter { it.endsWith(" bytes") || it.endsWith("(this file)") }
            .filterNot { it.contains("files,") }.map { it.trim().substringBefore(' ') }
        assertEquals(actual, listed)
        assertEquals(actual, r.entries.map { it.first })
        assertNull(ReportAssembler.validate(zip, r.entries))
    }

    // ── export(): temp file, validation, rename ──

    @Test fun `export writes a validated zip under the final name and leaves no part file`() = runTest {
        val dir = tmp.newFolder("out")
        val out = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true })
        out as ExportOutcome.Success
        assertEquals(File(dir, "r.zip"), out.file)
        assertEquals("7K3M-QX2D", out.reportId)
        assertFalse(out.partial)
        assertEquals(listOf("r.zip"), dir.list()!!.toList())
    }

    @Test fun `failed sections give Success with partial true`() = runTest {
        val dir = tmp.newFolder("out")
        val src = FakeReportSources(tmp.newFolder("logs"), failing = mapOf(FakeReportSources.Keys.DATA_HEALTH to IllegalStateException("x")))
        val out = ReportAssembler.export(dir, "r.zip", src, flush = { true })
        out as ExportOutcome.Success
        assertTrue(out.partial)
    }

    @Test fun `a write failure mid-zip gives WRITE_FAILED and no files`() = runTest {
        val dir = tmp.newFolder("out")
        val out = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true },
            openOutput = failingAfter(500) { IOException("I/O error") })
        assertEquals(ExportOutcome.Failure(ExportFailure.WRITE_FAILED, "IOException: I/O error"), out)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `disk full gives NO_SPACE and no files`() = runTest {
        val dir = tmp.newFolder("out")
        val out = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true },
            openOutput = failingAfter(100) { IOException("write failed: ENOSPC (No space left on device)") })
        assertEquals(ExportFailure.NO_SPACE, (out as ExportOutcome.Failure).reason)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(ExportFailure.NO_SPACE, ReportAssembler.classify(RuntimeException(IOException("No space left on device"))))
        assertEquals(ExportFailure.UNKNOWN, ReportAssembler.classify(IllegalStateException("bug")))
    }

    @Test fun `a zip that does not validate gives VALIDATION_FAILED and no files`() = runTest {
        val dir = tmp.newFolder("out")
        val out = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true },
            beforeValidate = { f -> f.writeBytes(f.readBytes().copyOf(200)) })   // cut off the central directory
        out as ExportOutcome.Failure
        assertEquals(ExportFailure.VALIDATION_FAILED, out.reason)
        assertTrue(out.detail!!, out.detail!!.startsWith("ZIP does not reopen"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `validate reports a missing entry, a size mismatch and a count mismatch`() = runTest {
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs")), flush = { true })
        val e = r.entries
        assertTrue(ReportAssembler.validate(zip, e.dropLast(1))!!.startsWith("entry count"))
        assertTrue(ReportAssembler.validate(zip, e.dropLast(1) + ("other.txt" to 1L))!!.startsWith("missing entry other.txt"))
        assertTrue(ReportAssembler.validate(zip, listOf(e[0].first to e[0].second + 1) + e.drop(1))!!.contains("expected"))
    }

    @Test fun `cancelling the caller propagates and deletes the temp file`() = runBlocking {
        val dir = tmp.newFolder("out")
        lateinit var job: Job
        // The stream cancels the caller mid-write, as a cancelled scope would at its next check.
        val cancelling: (File) -> OutputStream = { f ->
            object : FilterOutputStream(FileOutputStream(f)) {
                var n = 0
                override fun write(b: ByteArray, off: Int, len: Int) {
                    n += len
                    if (n > 300) { job.cancel(); throw CancellationException("cancelled") }
                    out.write(b, off, len)
                }
            }
        }
        var outcome: ExportOutcome? = null
        job = launch {
            outcome = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true }, openOutput = cancelling)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(outcome)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `cancelling while a source hangs stops the export without files`() = runBlocking {
        val dir = tmp.newFolder("out")
        val src = FakeReportSources(tmp.newFolder("logs"), hanging = setOf(FakeReportSources.Keys.SETTINGS))
        val job = launch { ReportAssembler.export(dir, "r.zip", src, flush = { true }, sectionTimeoutMs = Long.MAX_VALUE) }
        delay(200)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `a stray CancellationException while active is a CANCELLED failure`() = runTest {
        val dir = tmp.newFolder("out")
        val out = ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true },
            openOutput = failingAfter(100) { CancellationException("stray") })
        assertEquals(ExportFailure.CANCELLED, (out as ExportOutcome.Failure).reason)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `stale part files are removed, recent ones kept`() = runTest {
        val dir = tmp.newFolder("out")
        val now = 10L * 3_600_000L
        val stale = File(dir, "old.zip.part").apply { writeText("x"); setLastModified(now - 2 * 3_600_000L) }
        val fresh = File(dir, "busy.zip.part").apply { writeText("x"); setLastModified(now - 10 * 60_000L) }
        val other = File(dir, "keep.zip").apply { writeText("x"); setLastModified(now - 5 * 3_600_000L) }
        ReportAssembler.export(dir, "r.zip", FakeReportSources(tmp.newFolder("logs")), flush = { true }, nowMs = now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
        assertTrue(File(dir, "r.zip").exists())
    }

    // ── Entry names from outside text ──

    @Test fun `entry names from file names are sanitised`() {
        assertEquals("_/_/etc/passwd", ZipBuilder.safeEntryName("../../etc/passwd"))
        assertEquals("abs/x.txt", ZipBuilder.safeEntryName("/abs/x.txt"))
        assertEquals("exits/a_b_c.txt", ZipBuilder.safeEntryName("exits/a b:c.txt"))
        assertEquals("C__Users_x.txt", ZipBuilder.safeEntryName("C:\\Users\\x.txt"))
        assertNull(ZipBuilder.safeEntryName("..."))
        assertNull(ZipBuilder.safeEntryName("/"))
        assertEquals(ZipBuilder.MAX_ENTRY_NAME, ZipBuilder.safeEntryName("a".repeat(500))!!.length)
    }

    @Test fun `exit traces with unsafe names are copied under a safe name and the manifest matches`() = runTest {
        val dir = tmp.newFolder("logs")
        val trace = File(dir, "trace ..evil.txt").apply { writeText("trace\n") }
        val src = object : FakeReportSources(dir) { override fun exitTraceFiles() = listOf(trace) }
        val zip = File(tmp.root, "r.zip")
        val r = ReportAssembler.write(zip, src, flush = { true })
        assertTrue(r.entries.map { it.first }.toString(), r.entries.any { it.first == "exits/trace__evil.txt" })
        assertNull(ReportAssembler.validate(zip, r.entries))
    }

    @Test fun `a duplicate or unsafe file name is omitted with a reason instead of breaking the zip`() {
        val dir = tmp.newFolder("f")
        val f = File(dir, "a.txt").apply { writeText("one\n") }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            val z = ZipBuilder(zos)
            assertTrue(z.file("a.txt", f))
            assertFalse(z.file("a.txt", f))
            assertFalse(z.file("..", f))
            assertEquals(listOf(OmittedFile("a.txt", "duplicate name"), OmittedFile("..", "unsafe file name")), z.omitted)
        }
    }

    @Test fun `a read failure mid-copy keeps what was read and records the file as cut short`() {
        val dir = tmp.newFolder("f")
        val f = File(dir, "log.txt").apply { writeText("one\ntwo\nthree\n") }
        val zipFile = File(tmp.root, "r.zip")
        var n = 0
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val z = ZipBuilder(zos)
            assertTrue(z.file("log.txt", f, keep = { if (++n == 3) throw IOException("read error") else true }))
            val t = z.truncated.single()
            assertFalse(t.expected)
            assertEquals(8L, t.includedBytes)
            assertEquals("read failed: IOException", t.reason)
        }
        assertEquals("one\ntwo\n", entries(zipFile).getValue("log.txt"))
        assertNotNull(ZipFile(zipFile).use { it.getEntry("log.txt") })
    }
}
