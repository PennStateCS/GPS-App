package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentMarker
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

/** diagnostic-summary.txt as the assembler writes it: section order, readable content, completeness. */
class SummaryStructureTest {

    @get:Rule val tmp = TemporaryFolder()
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)

    private fun ms(hms: String) = OffsetDateTime.parse("2026-11-21T$hms-05:00").toInstant().toEpochMilli()

    private val incident = IncidentMarker(
        id = "AB12CD34", epochMs = ms("14:01:00"), utcTime = "2026-11-21T19:01:00Z", localTime = "2026-11-21T14:01:00",
        zoneId = "America/New_York", sessionId = "bbb222", appVersion = "1.4", screen = "nav_open_in_ar",
        description = "Models jumped at 41.1234567, -75.1234567 call me at tester@example.com",
        context = IncidentContext(rtkStatus = "FLOAT", correctionAgeS = 14.0, arSessionActive = true, arCameraTracking = "TRACKING"),
    )

    private suspend fun summary(src: FakeReportSources): Pair<String, ReportResult> {
        val zip = File(tmp.root, "r-${System.nanoTime()}.zip")
        val r = ReportAssembler.write(zip, src, flush = { true })
        return ZipFile(zip).use { z -> z.getInputStream(z.getEntry("diagnostic-summary.txt")).reader().readText() } to r
    }

    @Test fun `complete report - every section in order, readable, details kept below`() = runBlocking {
        val (s, r) = summary(FakeReportSources(tmp.newFolder("logs"), incidentList = listOf(incident)))
        assertFalse(r.partial)
        val headings = SummaryCollector.HEADINGS.map { "--- $it ---" }
        val at = headings.map { s.indexOf(it) }
        assertTrue("all headings present: $at", at.all { it >= 0 })
        assertEquals("in order", at.sorted(), at)
        assertTrue(s.startsWith("=== Diagnostic summary ===\n"))
        // Report information
        assertTrue(s, s.contains("Report ID     : 7K3M-QX2D"))
        assertTrue(s, s.contains("App version   : 1.4 (14) debug"))
        assertTrue(s, s.contains("Git commit    : abc1234"))
        assertTrue(s, s.contains("Android       : 15 (API 35)"))
        assertTrue(s, s.contains("Generated     : 2026-11-21T20:00:00Z UTC / 2026-11-21T15:00:00.000-05:00 local (America/New_York)"))
        assertTrue(s, s.contains("Session       : bbb222"))
        // Reported problem: the note is kept, coordinates and the e-mail are scrubbed.
        assertTrue(s, s.contains("Incident      : AB12CD34 (current session)"))
        assertTrue(s, s.contains("Marked at     : 2026-11-21T19:01:00Z UTC / 2026-11-21T14:01:00.000-05:00 local"))
        assertTrue(s, s.contains("Screen        : nav_open_in_ar"))
        assertTrue(s, s.contains("Description   : \"Models jumped at [redacted]"))
        assertFalse(s, s.contains("41.1234567") || s.contains("tester@example.com"))
        // Findings from the fake log: corrections lost 1 min before the mark (possible cause), the previous crash.
        val findings = s.substringAfter("--- Important findings ---").substringBefore("--- Subsystem status ---")
        assertTrue(findings, findings.contains("[CRITICAL] App crashed"))
        assertTrue(findings, findings.contains("RTK corrections stale or missing  — OBSERVED"))
        assertTrue(findings, findings.contains("POSSIBLE_CAUSE of the reported problem: corrections went stale 30 s before it was marked"))
        assertTrue(findings, findings.contains("2026-11-21T14:00:00.000-05:00  app-log-current.txt  W CORRECTIONS Corrections stream lost"))
        assertTrue(findings, findings.contains("Next step: "))
        // Subsystem status: one line each.
        val status = s.substringAfter("--- Subsystem status ---").substringBefore("--- Where to investigate ---").trim().lines()
        assertEquals(listOf("GNSS", "Receiver connection", "RTK corrections", "AR tracking", "Models", "Database & storage", "Permissions", "Network"),
            status.map { it.substringBefore(" :").trim() })
        // Where to investigate names files with times.
        val where = s.substringAfter("--- Where to investigate ---").substringBefore("--- Export completeness ---")
        assertTrue(where, where.contains("- app-log-current.txt around 2026-11-21T14:00:00.000-05:00 — RTK corrections stale or missing"))
        assertTrue(where, where.contains("- incidents.txt — incident AB12CD34"))
        // Completeness
        val comp = s.substringAfter("--- Export completeness ---")
        assertTrue(comp, comp.contains("Verdict          : COMPLETE"))
        assertTrue(comp, comp.contains("Failed or missing: (none)"))
        assertTrue(comp, comp.contains("Logs             : complete (nothing left out)"))
        // The older blocks follow.
        assertTrue(s.indexOf("=== First look ===") > s.indexOf("--- Export completeness ---"))
        assertTrue(s.contains("Report ID        : 7K3M-QX2D"))                        // the app's own summary
    }

    @Test fun `failed collector and no incident - completeness lists it, problem section says none marked`() = runBlocking {
        val src = FakeReportSources(tmp.newFolder("logs"),
            failing = mapOf(FakeReportSources.Keys.MODELS to IllegalStateException("db closed"),
                FakeReportSources.Keys.DATA_HEALTH to IllegalStateException("no db")))
        val (s, r) = summary(src)
        assertTrue(r.partial)
        assertTrue(SummaryCollector.HEADINGS.all { s.contains("--- $it ---") })
        assertTrue(s, s.contains("No problem was marked."))
        val comp = s.substringAfter("--- Export completeness ---")
        assertTrue(comp, comp.contains("Verdict          : PARTIAL"))
        assertTrue(comp, comp.contains("  - model-integrity.txt FAILED (IllegalStateException)"))
        assertTrue(comp, comp.contains("  - data-health.txt FAILED (IllegalStateException)"))
        assertTrue(comp, comp.contains("Details          : export-health.txt"))
        // Without an incident the findings cover the current session (crash before it is still in the last 24 h).
        assertTrue(s, s.contains("[CRITICAL] App crashed"))
        assertFalse(s, s.contains("POSSIBLE_CAUSE of the reported problem"))
    }

    @Test fun `offline device - export works and the network finding says so`() = runBlocking {
        val (s, r) = summary(FakeReportSources(tmp.newFolder("logs"), health = FakeReportSources.FAKE_HEALTH.copy(network = "none")))
        assertFalse(r.partial)
        assertTrue(s, s.contains("No network connection at export"))
        assertTrue(s, s.lines().any { it.startsWith("Network ") && it.endsWith(" : none") })
    }

    @Test fun `unknown network and database integrity failure come out as findings`() = runBlocking {
        val (s, _) = summary(FakeReportSources(tmp.newFolder("logs"), health = FakeReportSources.FAKE_HEALTH.copy(network = null),
            integrity = "*** in database main *** Page 7 is never used"))
        assertTrue(s, s.contains("[HIGH] Database integrity check failed  — OBSERVED"))
        assertTrue(s, s.contains("Network state unknown"))                     // corrections were lost in the log
    }

    @Test fun `a failing summary source still leaves the new sections`() = runBlocking {
        val (s, r) = summary(FakeReportSources(tmp.newFolder("logs"), failing = mapOf(FakeReportSources.Keys.SUMMARY to IllegalStateException("x"))))
        assertTrue(r.partial)
        assertTrue(SummaryCollector.HEADINGS.all { s.contains("--- $it ---") })
        assertTrue(s, s.contains("  - diagnostic-summary.txt FAILED (IllegalStateException)"))
    }

    @Test fun `incidents and timeline files carry the incident`() = runBlocking {
        val zip = File(tmp.root, "i.zip")
        ReportAssembler.write(zip, FakeReportSources(tmp.newFolder("logs"), incidentList = listOf(incident)), flush = { true })
        ZipFile(zip).use { z ->
            val inc = z.getInputStream(z.getEntry("incidents.txt")).reader().readText()
            assertTrue(inc, inc.contains("--- Latest: incident AB12CD34 (current session) ---"))
            assertTrue(inc, inc.contains("   2026-11-21T14:00:00.000-05:00 W CORRECTIONS [diag] Corrections stream lost"))
            assertTrue(inc, inc.contains(">> 2026-11-21T14:01:00.000-05:00 ===== PROBLEM MARKED here: incident AB12CD34 ====="))
            assertFalse(inc, inc.contains("tester@example.com"))
            val tl = z.getInputStream(z.getEntry("timeline.txt")).reader().readText()
            assertTrue(tl, tl.contains(">> 2026-11-21T14:00:01.000-05:00 W GNSS [diag] Correction age over 10 s (12.0 s)"))
            assertTrue(tl, tl.contains("\n2026-11-21T13:05:00.000-05:00 I LIFECYCLE [main] App foreground"))   // outside: no prefix
        }
    }
}
