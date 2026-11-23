package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * Adversarial privacy check over the COMPLETE generated ZIP: every source the report reads (log
 * files, error files, exit traces, captures log, AR track, every text section, live state, health,
 * stats) is stuffed with synthetic secrets and personal data. None of it may appear in any entry;
 * accuracy values must survive.
 */
@RunWith(RobolectricTestRunner::class)
class ReportPrivacyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val password = "Hunter2!"
    private val ntripUrl = "ntrip://alice:S3cr3t@caster.example.com:2101/MOUNT"
    private val apiKey = "AIzaSyD3adB33fFAKEFAKEFAKEFAKE12345678"
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlZmFrZQ"
    private val gga = "\$GNGGA,142307.40,4120.8591234,N,07601.3554321,W,4,18,0.7,345.678,M,-33.0,M,1.2,0000*5C"
    private val pointName = "Smith Residence Backyard"
    private val note = "Gate code is 4471, dog in yard"
    private val email = "bob@example.com"
    private val sharedPath = "/storage/emulated/0/Download/JohnDoe-house.glb"
    private val winPath = "C:\\Users\\jdoe\\secret.txt"
    private val accuracy = "hAcc=0.0123456 vAcc=0.0217891 ageS=1.234567 east=0.1234567"

    /** Every line form a leak could take. */
    private val leaks = listOf(
        "password=$password",
        "corrections url=$ntripUrl",
        "map key $apiKey",
        "Authorization: Bearer $jwt",
        "raw $gga",
        "fix 41.2468135, -75.8811223",
        "debug lat=41.2468135 lon=-75.8811223",
        "json {\"latitude\": 41.2468135, \"longitude\": -75.8811223}",
        "Capture saved name=\"$pointName\" note=\"$note\"",
        "contact $email",
        "Model import failed path=$sharedPath",
        "copy failed $winPath",
        "Model record created file=JohnDoe-house.glb",
    )

    /** Fragments that must not appear anywhere in the ZIP. */
    private val forbidden = listOf(
        password, "Hunter2", "S3cr3t", "alice", apiKey, "AIzaSy", jwt, "eyJhbGci",
        "\$GNGGA", "GNGGA,", "4120.859", "07601.355",
        "41.2468135", "-75.8811223", "75.8811223", "41.24681", "75.88112",
        pointName, "Smith Residence", "Gate code", "dog in yard", email, "bob@",
        "JohnDoe", "/storage/emulated/0/Download", "jdoe", "secret.txt",
    )

    private fun logLines(level: Char, tag: String) = leaks.mapIndexed { i, l ->
        "2026-11-21T14:%02d:00.000-05:00 $level $tag [main] $l".format(30 + i)
    }.joinToString("\n") + "\n2026-11-21T14:59:00.000-05:00 I GNSS [main] Accuracy $accuracy\n"

    private val blob = leaks.joinToString("\n") + "\n$accuracy\n"

    private inner class SecretSources(private val dir: File) : FakeReportSources(
        dir,
        eventLog = FakeReportSources.DEFAULT_EVENT_LOG + logLines('W', "RECEIVER"),
        errorLog = FakeReportSources.DEFAULT_ERROR_LOG + logLines('E', "ERROR") +
            "java.io.FileNotFoundException: $sharedPath: open failed: ENOENT\n\tat app.surrealar.Foo.bar(Foo.kt:1)\n",
    ) {
        override suspend fun summary() = "=== Diagnostic Summary ===\n$blob"
        override suspend fun settingsSnapshot() = "=== Current Settings Snapshot ===\nntrip_password : $password\nntrip_user : alice\n$blob"
        override suspend fun permissions() = "=== Permissions Status ===\n$blob"
        override suspend fun appState() = "=== App State Summary ===\n$blob"
        override suspend fun coordinates() = "=== Coordinates ===\nid=1 name=\"$pointName\" modelId=2 file=ok(10B)\n$blob"
        override suspend fun models() = "=== Model Integrity ===\nid=2 name=\"$pointName\" file=\"JohnDoe-house.glb\" type=GLB\n$blob"
        override fun arSession() = "AR session\ncoordId=c1 name=\"$pointName\"\n$blob"
        override suspend fun mapDiagnostics() = "=== Map ===\n$blob"
        override suspend fun nmeaStream() = "=== NMEA Stream Diagnostics ===\nLast sentences:\n$gga\n$blob"
        override fun liveState() = LiveState("EXTERNAL_TCP", "FIX", "CONNECTED $ntripUrl", 1.2,
            "receiving $ntripUrl user=alice password=$password", 12L * 1024 * 1024 * 1024)
        override fun exitTraceFiles(): List<File> =
            listOf(File(dir, "20261121-133000-ANR.txt").apply { writeText("----- pid 123 -----\n$blob") })
        override fun deviceHealth() = FakeReportSources.FAKE_HEALTH.copy(network = "Wi-Fi $email $ntripUrl", mockLocationApp = sharedPath)
        override fun capturesLog(): File = File(dir, "captures.log").apply {
            writeText(leaks.joinToString("\n") { "2026-11-21T14:10:00.000-05:00 $it" } + "\n")
        }
        override fun arTrackFiles(): List<File> = listOf(File(dir, "ar-track.csv").apply {
            writeText("mono_ms,cam_x\n1200,0.12345\n# $blob")
        })
        override suspend fun gnssStats() = FakeReportSources.FAKE_GNSS.copy(sentenceCounts = "GGA=3600 last=$gga")
        override fun dataHealth() = DataHealth(12, mapOf("coordinates" to 42L), "ok $winPath $email")
    }

    private suspend fun buildZip(): Map<String, String> {
        val zip = File(tmp.root, "report.zip")
        ReportAssembler.write(zip, SecretSources(tmp.newFolder("src")), flush = {})
        return ZipFile(zip).use { z ->
            z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
    }

    @Test fun `no secret or personal value appears in any entry of the zip`() = runTest {
        val all = buildZip()
        assertTrue("expected a full report, got ${all.keys}", all.size > 15)
        for ((name, text) in all) for (secret in forbidden) {
            assertFalse("'$secret' leaked into $name", text.contains(secret))
        }
    }

    @Test fun `accuracy values and redaction evidence survive`() = runTest {
        val all = buildZip()
        val log = all.getValue("app-log-current.txt")
        assertTrue(log.contains(accuracy))
        assertTrue(all.getValue("app-errors-current.txt").contains(accuracy))
        assertTrue(all.getValue("app-state-summary.txt").contains(accuracy))
        // Redacted, not dropped: a reader can still see that something was there.
        assertTrue(log.contains("password=(redacted)"))
        assertTrue(log.contains("ntrip://(redacted)@caster.example.com"))
        assertTrue(log.contains("raw [NMEA removed]"))
        assertTrue(log.contains("lat=[coord removed]"))
        assertTrue(log.contains("[storage]/#"))
        assertTrue(log.contains(".glb"))
    }

    @Test fun `raw nmea is never attached to the ordinary report`() = runTest {
        val all = buildZip()
        assertFalse(all.keys.any { it.contains("nmea", ignoreCase = true) && it != "nmea-stream-diagnostics.txt" })
        assertFalse(all.values.any { Regex("""\$[A-Z]{5},""").containsMatchIn(it) })
    }
}
