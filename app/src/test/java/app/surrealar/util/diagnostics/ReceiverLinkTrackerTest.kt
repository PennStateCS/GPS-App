package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiverLinkTrackerTest {
    private val lines = mutableListOf<String>()
    private val t = ReceiverLinkTracker { level, tag, m -> lines += "$level $tag $m" }

    @Test fun `link up, first data, drop, reconnects`() {
        t.onConnectionState("CONNECTING", 0)
        t.onConnectionState("CONNECTED", 1_000)
        t.onData(1_850); t.onData(1_900)
        t.onConnectionState("RECONNECTING", 61_000)
        t.onConnectionState("RECONNECTING", 61_000)
        t.onConnectionState("ERROR", 62_000)
        t.onConnectionState("RECONNECTING", 65_000)
        t.onConnectionState("CONNECTED", 70_000)
        assertEquals(listOf(
            "I RECEIVER Link up",
            "I RECEIVER First data 850 ms after connect",
            "W RECEIVER Link down: reconnecting (attempt 1), was up 60s",
            "I RECEIVER Link up after 2 attempts",
        ), lines)
        assertEquals(2, t.reconnects)
    }

    @Test fun `corrections up, station change, lost - never the base position`() {
        val c = ReceiverCorrections(isReceiving = true, channel = "NTRIP", stationId = "PAWB", ageS = 1.0,
            baseLatDeg = 41.24567, baseLonDeg = -75.88123, baseHeightM = 180.5)
        t.onCorrections(c); t.onCorrections(c.copy(ageS = 2.0))
        t.onCorrections(c.copy(stationId = "PAHZ")); t.onCorrections(c.copy(isReceiving = false, stationId = "PAHZ"))
        assertEquals(listOf(
            "I CORRECTIONS Corrections stream connected channel=NTRIP station=PAWB",
            "W CORRECTIONS Base station ID PAWB → PAHZ",
            "W CORRECTIONS Corrections stream lost",
        ), lines)
        assertFalse(lines.any { it.contains("41.2") || it.contains("75.8") || it.contains("180.5") })
        assertEquals("not receiving", t.correctionsSummary)
    }
}