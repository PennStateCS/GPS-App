package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.zip.ZipFile

/** [LogBudget]: over the budget the crash, the incident window and recent errors survive; gaps are marked; the size fits. */
class LogPreservationTest {

    @get:Rule val tmp = TemporaryFolder()
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)

    private val now = OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli()
    private val incidentAt = OffsetDateTime.parse("2026-11-21T08:00:00-05:00").toInstant().toEpochMilli()

    private fun ts(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atOffset(java.time.ZoneOffset.ofHours(-5))
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))

    /** ~20 h of one I line every 10 s (≈ 7,200 lines, ~0.8 MB) with a crash, an incident window and an old error. */
    private fun bigLog(): File {
        val f = File(tmp.root, "app-log-1.txt")
        f.bufferedWriter().use { w ->
            var t = now - 20 * 3_600_000L
            var n = 0
            while (t < now) {
                when {
                    n == 100 -> {                                              // early crash, ~19.7 h ago
                        w.write("${ts(t)} E ERROR [main] Uncaught exception (fatal) thread=main screen=x session=aaa111 type=java.lang.IllegalStateException: boom\n")
                        w.write("java.lang.IllegalStateException: boom\n\tat app.surrealar.Foo.bar(Foo.kt:1)\n\tat app.surrealar.Foo.baz(Foo.kt:2)\n")
                    }
                    n == 200 -> w.write("${ts(t)} E DATA [io] Backup export failed rows=12 dest=saf\n")
                    else -> w.write("${ts(t)} I GNSS [worker] GNSS heartbeat source=EXTERNAL_TCP rtk=FIX sats=18/27 hdop=0.7 hAcc=<2cm n=$n\n")
                }
                t += 10_000L; n++
            }
        }
        return f
    }

    private fun plan(f: File, budget: Long): Pair<LogBudget.FilePlan, LogIndex> {
        val b = LogBudget(now, budget, listOf(incidentAt))
        val idx = LogIndex.build(listOf(f), now, listOf(incidentAt), b::onEntry)
        return b.plan().getValue(f) to idx
    }

    private fun copy(f: File, p: LogBudget.FilePlan): List<String> {
        val out = mutableListOf<String>()
        val filter = p.filter()
        f.bufferedReader().useLines { lines -> lines.forEach { filter.onLine(it) { l -> out += l } } }
        return out
    }

    @Test fun `over budget keeps crash, incident window and errors, marks gaps and fits`() {
        val f = bigLog()
        val budget = 200_000L
        val (p, _) = plan(f, budget)
        assertTrue(p.reduced)
        val out = copy(f, p)
        val bytes = out.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }
        assertTrue("$bytes > $budget", bytes <= budget)
        assertEquals(p.includedBytes, bytes)
        // Crash with its full stack trace.
        val crash = out.indexOfFirst { it.contains("Uncaught exception (fatal)") }
        assertTrue(crash >= 0)
        assertEquals("\tat app.surrealar.Foo.baz(Foo.kt:2)", out[crash + 3])
        // Every line of the incident window (07:55 → 08:02).
        val window = out.filter { it.startsWith("2026-11-21T07:5") && it.substring(14, 16).toInt() >= 55 || it.startsWith("2026-11-21T08:0") && it.substring(14, 16).toInt() <= 1 }
        assertEquals(42, window.size)                                          // 7 min × 6 lines/min
        // The old error.
        assertTrue(out.any { it.contains("Backup export failed") })
        // The newest line is kept; markers say what is missing.
        assertTrue(out.last().contains("GNSS heartbeat") && out.last().startsWith("2026-11-21T14:59:5"))
        val markers = out.filter { it.startsWith("… [") }
        assertEquals(p.omittedSpans, markers.size)
        assertTrue(markers.all { Regex("""^… \[\d+ lines omitted 2026-11-2\dT\S+ → 2026-11-2\dT\S+] …$""").matches(it) })
        assertEquals(p.omittedLines, markers.sumOf { Regex("""\[(\d+) lines""").find(it)!!.groupValues[1].toLong() })
        assertTrue(p.originalSpan!!.startsWith("2026-11-20T19:00:00.000-05:00 → "))
        assertTrue(p.includedSpan!!.endsWith("→ 2026-11-21T14:59:50.000-05:00"))
    }

    @Test fun `crash evidence is kept even when the budget is tiny`() {
        val f = bigLog()
        val (p, _) = plan(f, 600)
        val out = copy(f, p)
        assertTrue(out.any { it.contains("Uncaught exception (fatal)") })
        assertTrue(out.sumOf { it.toByteArray().size + 1L } <= 600)
    }

    @Test fun `under budget nothing is reduced`() {
        val f = bigLog()
        val (p, _) = plan(f, 50L * 1024 * 1024)
        assertFalse(p.reduced)
        assertEquals(f.readLines(), copy(f, p))
    }

    @Test fun `lines appended after the plan are kept`() {
        val f = bigLog()
        val (p, _) = plan(f, 100_000L)
        f.appendText("${ts(now + 1_000)} I EXPORT [io] Diagnostic report built verdict=COMPLETE\n")
        assertTrue(copy(f, p).last().contains("Diagnostic report built"))
    }

    @Test fun `assembled report records spans and bytes in export-health and stays within budget`() = runBlocking {
        val dir = tmp.newFolder("logs")
        val big = bigLog()
        val marker = app.surrealar.util.diagnostics.incident.IncidentMarker("INC00001", incidentAt, "", "", "America/New_York",
            "aaa111", "1.4", "nav_home", null, app.surrealar.util.diagnostics.incident.IncidentContext())
        val src = object : FakeReportSources(dir, incidentList = listOf(marker)) {
            override fun logFiles() = listOf(big)
        }
        val zip = File(tmp.root, "r.zip")
        val budget = 150_000L
        val r = ReportAssembler.write(zip, src, flush = { true }, maxLogBytes = budget)
        val t = r.health.truncated.single { it.name == "app-log-1.txt" }
        assertTrue(t.expected)
        assertTrue(t.omittedSpans > 0)
        assertTrue(t.originalSpan != null && t.includedSpan != null)
        val logBytes = r.entries.filter { it.first.startsWith("app-") }.sumOf { it.second }
        assertTrue("$logBytes > $budget", logBytes <= budget)
        assertFalse(r.partial)
        ZipFile(zip).use { z ->
            val health = z.getInputStream(z.getEntry("export-health.txt")).reader().readText()
            assertTrue(health, health.contains("original span : 2026-11-20T19:00:00.000-05:00"))
            assertTrue(health, health.contains("omitted       : ${t.omittedSpans} span(s)"))
            val summary = z.getInputStream(z.getEntry("diagnostic-summary.txt")).reader().readText()
            assertTrue(summary, summary.contains("Logs             : 1 file(s) reduced to fit the size budget"))
            val log = z.getInputStream(z.getEntry("app-log-1.txt")).reader().readText()
            assertTrue(log.contains("Uncaught exception (fatal)"))
            assertTrue(log.contains("2026-11-21T07:58:00.000-05:00"))                 // inside the incident window
        }
    }
}
