package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.SettingsRedaction
import app.surrealar.util.diagnostics.SettingsSnapshotCollector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/** A full report from fakes full of secrets: none of them may appear anywhere in the ZIP. */
@RunWith(RobolectricTestRunner::class)
class ReportRedactionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val password = "hunter2-ntrip"
    private val apiKey = "AIzaSyD3adB33fFAKEFAKEFAKEFAKE12345678"
    private val gga = "\$GNGGA,142307.40,4120.8591234,N,07601.3554321,W,4,18,0.7,345.678,M,-33.0,M,1.2,0000*5C"
    private val lat = "41.3476987"
    private val lon = "-76.0225905"

    private inner class LeakySources(dir: File) : FakeReportSources(dir, eventLog = FakeReportSources.DEFAULT_EVENT_LOG + """
        2026-11-21T14:30:00.000-05:00 W RECEIVER [io] raw line $gga
        2026-11-21T14:30:01.000-05:00 W RECEIVER [io] ntrip connect user=surveyor password=$password mount=PAWB
        2026-11-21T14:30:02.000-05:00 I MAP [main] map key $apiKey
        2026-11-21T14:30:03.000-05:00 I CAPTURE [main] debug position lat=$lat lon=$lon
    """.trimIndent() + "\n") {
        override suspend fun settingsSnapshot() = SettingsSnapshotCollector.render(
            mapOf("ntrip_password" to password, "cloud_api_key" to apiKey, "location_source" to "EXTERNAL"),
            listOf("maps_api_key" to SettingsRedaction.describe("maps_api_key", apiKey)))
        override suspend fun appState() = "Current fix: $lat, $lon token=$password\n"
        override suspend fun coordinates() = "Total coordinates : 3\nlast: $lat,$lon\n"
        override suspend fun nmeaStream() = "Last sentences:\n$gga\n"
    }

    @Test fun `no password, key, raw NMEA or coordinate anywhere in the zip`() = runTest {
        val zip = File(tmp.root, "r.zip")
        ReportAssembler.write(zip, LeakySources(tmp.newFolder("logs")), flush = {})
        val all = ZipFile(zip).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).reader().readText() } }
        assertTrue(all.size > 15)
        for ((name, text) in all) {
            for (secret in listOf(password, apiKey, "AIzaSy", "\$GNGGA", "4120.859", "07601.355", lat, lon, "41.34769", "76.02259")) {
                assertFalse("$secret leaked into $name", text.contains(secret))
            }
        }
        // Redacted, not dropped: the evidence that something was there is kept.
        val log = all.getValue("app-log-current.txt")
        assertTrue(log.contains("raw line [NMEA removed]"))
        assertTrue(log.contains("password=(redacted)"))
        assertTrue(all.getValue("settings-snapshot.txt").contains("(set)"))
    }

    @Test fun `ordinary numbers survive`() {
        assertEquals("hAcc=0.014m age=1.2s fixTimeMs=1700000000123 ratio=0.12345",
            ReportRedactor.scrub("hAcc=0.014m age=1.2s fixTimeMs=1700000000123 ratio=0.12345"))
        assertEquals("ntrip_password          : (set)  [changed]", ReportRedactor.scrub("ntrip_password          : (set)  [changed]"))
    }
}