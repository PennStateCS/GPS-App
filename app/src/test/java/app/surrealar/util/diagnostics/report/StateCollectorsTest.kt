package app.surrealar.util.diagnostics.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Golden tests for device-health.txt, captures.txt, gnss-stats.txt and data-health.txt. */
class StateCollectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `golden device health`() {
        assertEquals("""
            === Device health ===
            --- Power ---
            Battery                     : 57% (not charging)
            Power save                  : off
            Thermal status              : LIGHT
            Battery optimisation ignored: no (Android may pause the app in the background)
            --- Storage and memory ---
            Storage free / total        : 12.0 GB / 128.0 GB
            App data                    : 350 MB
            RAM available / total       : 3.0 GB / 8.0 GB
            Low memory                  : no
            --- Network and location ---
            Network                     : Wi-Fi validated=false
            GPS provider                : on
            Network location provider   : off
            Mock location app (dev opt.): not this app
            --- Permissions ---
            Fine location               : granted
            Camera                      : DENIED
        """.trimIndent() + "\n", DeviceHealthCollector.render(FakeReportSources.FAKE_HEALTH))
    }

    @Test fun `captures keeps the last 50`() {
        val out = CapturesCollector.render(CapturesCollector.read(FakeReportSources(tmp.newFolder("l")).capturesLog()))
        val rows = out.lines().filter { it.contains("Capture saved") }
        assertEquals(50, rows.size)
        assertTrue(rows.first().contains("name=\"P6\"")); assertTrue(rows.last().contains("name=\"P55\""))
        assertTrue(CapturesCollector.render(emptyList()).contains("(no captures recorded)"))
    }

    @Test fun `golden gnss stats`() {
        assertEquals("""
            === GNSS stats (this app session) ===
            --- Time in each RTK status ---
            FLOAT          :      10m 00s   16.7%
            FIX            :      50m 00s   83.3%
            --- Events ---
            RTK drops (left FIX)       : 3
            Longest correction age     : 31.0 s
            Source switches            : 1
            Fixes received             : 18000
            Receiver reconnects        : 2
            --- NMEA ---
            Lines processed            : 120000
            Checksum/parse failures    : 4
            Sentence counts (external receiver):
              GGA=3600 RMC=3600 GST=3600
        """.trimIndent() + "\n", GnssStatsCollector.render(FakeReportSources.FAKE_GNSS))
    }

    @Test fun `sentence counts are lifted from the NMEA section`() {
        val nmea = "=== NMEA Stream Diagnostics ===\n--- Sentence counts (cumulative this session) ---\nGGA=10 RMC=10\nGSV GP=4\n\n--- Next ---\nx\n"
        assertEquals("GGA=10 RMC=10\nGSV GP=4", GnssStatsCollector.sentenceCounts(nmea))
    }

    @Test fun `golden data health`() {
        assertEquals("""
            === Data health ===
            Database version : 12
            Last migration   : 2026-11-20T09:00:00.000-05:00 I DATA [io] Database migrated v11 → v12 buildToOpenMs=40
            Integrity check  : ok
            --- Rows per table ---
            coordinates         : 42
            models              : 3
        """.trimIndent() + "\n", DataHealthCollector.render(DataHealth(12, mapOf("models" to 3L, "coordinates" to 42L), "ok"),
            "2026-11-20T09:00:00.000-05:00 I DATA [io] Database migrated v11 → v12 buildToOpenMs=40"))
    }
}