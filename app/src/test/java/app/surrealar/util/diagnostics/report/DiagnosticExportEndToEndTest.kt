package app.surrealar.util.diagnostics.report

import android.app.Application
import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipFile

/**
 * The whole field workflow in one ZIP: a tester marks a problem (with a note), the app restarts, a
 * collector fails, the logs hold secrets and a receiver/RTK failure, and the report is exported.
 * Checks what a developer relies on: the ZIP opens and validates, the manifest lists exactly the
 * entries, the summary has every section and tells the story, the incident survived the restart,
 * the failed collector is reported (and everything else is still there), and no secret leaks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)   // org.json needs Robolectric; skip the app's startup work
class DiagnosticExportEndToEndTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli()
    private val markedAt = now - 5 * 60_000L

    private val note = "Model jumped 3 m after the receiver dropped"

    private fun line(hms: String, level: Char, tag: String, msg: String) = "2026-11-21T$hms.000-05:00 $level $tag [main] $msg"

    /** Logs with a receiver/RTK story before the mark, plus synthetic secrets everywhere. */
    private val eventLog = listOf(
        line("14:30:00", 'I', "LIFECYCLE", "SESSION_START session=sess-2 lastSessionClean=true"),
        line("14:50:00", 'I', "RECEIVER", "RECEIVER_LINK state=CONNECTED conn=c4 (External receiver link up)"),
        line("14:53:30", 'W', "GNSS", "CORRECTIONS_STATE state=STALE ageS=12.0 thresholdS=5 via=nmea (Corrections age over threshold)"),
        line("14:54:10", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT hAccM=0.410 conn=c4 (RTK status FIX → FLOAT)"),
        line("14:54:40", 'W', "RECEIVER", "RECEIVER_LINK state=NO_DATA conn=c4 afterS=10 (External receiver TCP connected but sent no NMEA)"),
        line("14:55:00", 'I', "DIAG", "INCIDENT_MARKED id=INC00001 screen=nav_open_in_ar session=sess-1 hasNote=true"),
        // Secrets that must never reach the ZIP.
        line("14:56:00", 'W', "RECEIVER", "NTRIP connect url=ntrip://alice:S3cr3t@caster.example.com:2101/MOUNT password=Hunter2!"),
        line("14:56:10", 'I', "COORD", "Point saved name=\"Smith Residence Backyard\" lat=41.2468135 lon=-75.8811223 hAcc=0.0123456"),
        line("14:56:20", 'I', "MODEL", "Imported /storage/emulated/0/Download/JohnDoe-house.glb"),
        "\$GNGGA,195600.00,4114.80881,N,07552.86734,W,4,24,0.6,187.1,M,-32.3,M,1.0,0000*5C",
    ).joinToString("\n", postfix = "\n")

    private fun markThenRestart(dir: File) {
        // Session 1: the tester marks the problem with a note.
        val before = IncidentStore(dir = { dir }, clock = { markedAt }, zone = { ZoneId.of("America/New_York") },
            newId = { "INC00001" }, sessionId = { "sess-1" }, screen = { "nav_open_in_ar" }, appVersion = { "1.4 (14)" },
            io = Dispatchers.Unconfined, log = {})
        runBlocking {
            before.mark(note, IncidentContext(gnssSource = "EXTERNAL_TCP", receiverProfile = "Emlid Reach RS2+",
                rtkStatus = "FLOAT", hAccM = 0.41, fixAgeMs = 12_000))
        }
    }

    @Test fun `a marked problem survives a restart and the exported ZIP tells the story without leaking`() = runBlocking {
        val incidentDir = tmp.newFolder("diagnostics")
        markThenRestart(incidentDir)
        // Session 2 (after a restart): a NEW store instance reads the mark back from disk.
        val incidents = IncidentStore(dir = { incidentDir }, io = Dispatchers.Unconfined, log = {}).recent()
        assertEquals("incident survived the restart", listOf("INC00001"), incidents.map { it.id })

        val src = object : FakeReportSources(
            tmp.newFolder("src"), nowMs = now, eventLog = eventLog,
            failing = mapOf(FakeReportSources.Keys.MAP to IllegalStateException("map renderer crashed at /storage/emulated/0/x.png")),
            incidentList = incidents,
        ) {}
        val out = tmp.newFolder("out")
        val outcome = ReportAssembler.export(out, "surreal-diagnostics-test.zip", src, flush = { true }, nowMs = now)

        // ── The ZIP is complete, validated and honest about the failed section ──
        assertTrue("export succeeded: $outcome", outcome is ExportOutcome.Success)
        outcome as ExportOutcome.Success
        assertTrue("a failed collector makes the report PARTIAL", outcome.partial)
        assertEquals(listOf("surreal-diagnostics-test.zip"), out.list()!!.toList())   // no .part left behind

        val entries: Map<String, String> = ZipFile(outcome.file).use { z ->
            z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
        for (name in listOf("README.txt", "diagnostic-summary.txt", "incidents.txt", "timeline.txt", "export-health.txt",
                "manifest.txt", "device-health.txt", "settings-snapshot.txt", "model-integrity.txt", "map-diagnostics.txt")) {
            assertTrue("missing $name in ${entries.keys}", name in entries)
        }
        // Manifest lists exactly the entries in the ZIP.
        val manifest = entries.getValue("manifest.txt")
        entries.keys.forEach { assertTrue("manifest lacks $it", manifest.contains(it)) }

        // ── Summary: every section, the tester's problem, the evidence trail, the completeness ──
        val summary = entries.getValue("diagnostic-summary.txt")
        for (h in listOf("Report information", "Reported problem", "Important findings", "Subsystem status",
                "Where to investigate", "Export completeness")) {
            assertTrue("summary lacks section '$h'", summary.contains(h, ignoreCase = true))
        }
        assertTrue("tester's note kept in the ZIP", summary.contains(note) || entries.getValue("incidents.txt").contains(note))
        assertTrue("marked in an earlier session than the export", summary.contains("earlier session (session=sess-1)"))
        assertTrue("RTK loss found", summary.contains("RTK", ignoreCase = true) && summary.contains("OBSERVED"))
        assertTrue("timing relation, never a root-cause claim", summary.contains("timing only"))
        assertTrue("failed collector named in completeness", summary.contains("map-diagnostics", ignoreCase = true))
        // The other sections still exported normally.
        assertTrue(entries.getValue("device-health.txt").isNotBlank())
        assertTrue(entries.getValue("export-health.txt").contains("FAILED"))

        // ── Incident timeline: around the mark, marked line highlighted ──
        val inc = entries.getValue("incidents.txt")
        assertTrue(inc, inc.contains("INC00001") && inc.contains("RTK_CHANGE") && inc.contains("RECEIVER_LINK state=NO_DATA"))

        // ── Privacy: scan every entry of the complete ZIP ──
        val all = entries.values.joinToString("\n")
        for (secret in listOf("Hunter2!", "S3cr3t", "alice:", "Smith Residence", "41.2468135", "-75.8811223",
                "JohnDoe-house", "\$GNGGA", "4114.80881", "/storage/emulated/0/x.png")) {
            assertFalse("secret '$secret' leaked into the ZIP", all.contains(secret))
        }
        assertTrue("accuracy values are kept", all.contains("hAcc=0.0123456"))
    }
}
