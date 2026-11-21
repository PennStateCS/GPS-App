package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SystemEventsLoggerTest {
    @Test fun `battery is bucketed in 10 percent steps`() {
        assertEquals(listOf(0, 50, 50, 90, 100), listOf(3, 50, 59, 99, 100).map(SystemEventsLogger::batteryBucket))
    }

    @Test fun `ssid is hashed, never shown`() {
        val h = SystemEventsLogger.hashSsid("\"ReachRS2-HOTSPOT\"")
        assertEquals(8, h.length)
        assertEquals(h, SystemEventsLogger.hashSsid("ReachRS2-HOTSPOT"))
        assertNotEquals(h, SystemEventsLogger.hashSsid("Office"))
        assertEquals("unknown", SystemEventsLogger.hashSsid("<unknown ssid>"))
        assertEquals("unknown", SystemEventsLogger.hashSsid(null))
    }

    @Test fun `transport and thermal names`() {
        assertEquals("Wi-Fi", SystemEventsLogger.transportName(wifi = true, cellular = false, ethernet = false, vpn = false))
        assertEquals("VPN", SystemEventsLogger.transportName(wifi = true, cellular = false, ethernet = false, vpn = true))
        assertEquals("SEVERE", SystemEventsLogger.thermalName(3))
    }
}