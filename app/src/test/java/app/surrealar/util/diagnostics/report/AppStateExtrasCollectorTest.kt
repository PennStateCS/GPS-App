package app.surrealar.util.diagnostics.report

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateExtrasCollectorTest {
    @Test fun `golden app state extras`() {
        assertEquals("""

            --- Receiver / corrections ---
            Receiver device        : EmlidReachDevice
            Receiver profile       : Emlid Reach RS2+
            Receiver link          : CONNECTED
            Corrections            : receiving station=PAWB age=1.2s

            --- Clock / heading / screen ---
            Clock offset dev−GNSS  : +0.4 s
            Heading source         : rotation vector + declination (LIVE_FIX)
            Current screen         : nav_render_map
        """.trimIndent() + "\n", AppStateExtrasCollector.render(AppStateExtrasCollector.Extras(
            "EmlidReachDevice", "Emlid Reach RS2+", "CONNECTED", "receiving station=PAWB age=1.2s",
            400L, "rotation vector + declination (LIVE_FIX)", "nav_render_map")))
    }

    @Test fun `unknowns are spelled out`() {
        val s = AppStateExtrasCollector.render(AppStateExtrasCollector.Extras(null, null, null, null, null, null, null))
        assertEquals(true, s.contains("Clock offset dev−GNSS  : unknown (no GNSS-timed fix yet)"))
        assertEquals(true, s.contains("Heading source         : not used this session"))
    }
}