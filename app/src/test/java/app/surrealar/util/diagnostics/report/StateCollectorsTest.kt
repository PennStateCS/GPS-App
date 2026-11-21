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

}