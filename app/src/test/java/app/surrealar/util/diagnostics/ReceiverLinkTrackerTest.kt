package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLinkTrackerTest {
    private val lines = mutableListOf<String>()
    private val t = ReceiverLinkTracker { level, tag, m -> lines += "$level $tag $m" }

    /** conn ids come from a process-wide counter: name them A, B, C… in order of appearance. */
    private fun normalized(): List<String> {
        val names = LinkedHashMap<String, String>()
        return lines.map { l ->
            Regex("conn=(c\\d+)").replace(l) { m -> "conn=c" + names.getOrPut(m.groupValues[1]) { ('A' + names.size).toString() } }
        }
    }

    private fun data(from: Long, to: Long) { var n = from; while (n <= to) { t.onData(n); n += 200 } }

    private fun fields(line: String): Map<String, String> = DiagEvents.parse(line.split(' ', limit = 3)[2])!!.second

    @Test fun `link up, first data, drop, reconnects`() {
        t.onConnectionState("CONNECTING", 0)
        t.onConnectionState("CONNECTED", 1_000)
        data(1_850, 60_850)
        t.onConnectionState("RECONNECTING", 61_000)
        t.onConnectionState("RECONNECTING", 61_000)
        t.onConnectionState("ERROR", 62_000)
        t.onConnectionState("RECONNECTING", 65_000)
        t.onConnectionState("CONNECTED", 70_000)
        assertEquals(listOf(
            "I RECEIVER RECEIVER_LINK state=CONNECTING conn=cA",
            "I RECEIVER RECEIVER_LINK state=CONNECTED conn=cA (External receiver link up)",
            "I RECEIVER External receiver first data received delayMs=850 conn=cA",
            "W RECEIVER RECEIVER_LINK state=DISCONNECTED conn=cA upS=60 reason=reconnect attempt=1 (External receiver link down)",
            "I RECEIVER RECEIVER_LINK state=CONNECTING conn=cB reason=reconnect",
            "W RECEIVER RECEIVER_LINK state=UNREACHABLE conn=cB afterS=1 attempts=1 (External receiver unreachable)",
            "I RECEIVER RECEIVER_LINK state=CONNECTED conn=cC attempts=2 (External receiver link up)",
        ), normalized())
        assertEquals(2, t.reconnects)
        lines.filter { it.contains("RECEIVER_LINK") }.forEach { assertEquals(DiagEvents.RECEIVER_LINK, DiagEvents.parse(it.split(' ', limit = 3)[2])!!.first) }
    }

    @Test fun `socket up but no NMEA is NO_DATA, logged once per outage and kind, not per retry`() {
        t.onConnectionState("CONNECTING", 0)
        t.onSocketConnected(500)
        t.onConnectionState("ERROR", 10_500)
        repeat(5) { i ->
            val at = 12_000L + i * 12_000L
            t.onConnectionState("RECONNECTING", at)
            t.onSocketConnected(at + 300)
            t.onConnectionState("ERROR", at + 10_300)
        }
        t.onConnectionState("RECONNECTING", 80_000)
        t.onConnectionState("ERROR", 80_100)          // no socket this time: UNREACHABLE (kind changed)
        t.onConnectionState("RECONNECTING", 82_000)
        t.onConnectionState("ERROR", 82_100)          // same kind again: nothing
        val failures = lines.filter { it.startsWith("W") }
        assertEquals(2, failures.size)
        assertEquals("NO_DATA", fields(failures[0])["state"]); assertEquals("10", fields(failures[0])["afterS"])
        assertEquals("UNREACHABLE", fields(failures[1])["state"])
        // The NO_DATA line is the first attempt's: same conn as its CONNECTING line.
        assertEquals(fields(lines[0])["conn"], fields(failures[0])["conn"])
    }

    @Test fun `data stopping before a drop is NO_DATA, a plain disconnect is not`() {
        t.onConnectionState("CONNECTING", 0)
        t.onConnectionState("CONNECTED", 1_000)
        data(1_000, 20_000)
        t.onConnectionState("ERROR", 50_000)
        val down = lines.last()
        assertTrue(down, down.startsWith("W RECEIVER RECEIVER_LINK state=NO_DATA"))
        assertEquals("30", fields(down)["afterS"]); assertEquals("49", fields(down)["upS"])

        t.onConnectionState("RECONNECTING", 51_000)
        t.onConnectionState("CONNECTED", 52_000)
        data(52_000, 60_000)
        t.onConnectionState("DISCONNECTED", 60_100)
        assertTrue(lines.last(), lines.last().startsWith("I RECEIVER RECEIVER_LINK state=DISCONNECTED"))
        assertEquals("disconnected", fields(lines.last())["reason"])
    }

    @Test fun `repeated drops log RECEIVER_RECONNECTS once per burst`() {
        var now = 0L
        fun upAndDrop() {
            t.onConnectionState("RECONNECTING", now); t.onConnectionState("CONNECTED", now + 1_000)
            data(now + 1_000, now + 30_000); t.onConnectionState("ERROR", now + 30_100)
            now += 60_000
        }
        repeat(5) { upAndDrop() }                       // 5 drops in 5 minutes
        assertEquals(1, lines.count { it.contains("RECEIVER_RECONNECTS") })
        now += 900_000                                  // quiet for 15 minutes
        repeat(3) { upAndDrop() }
        val bursts = lines.filter { it.contains("RECEIVER_RECONNECTS") }
        assertEquals(2, bursts.size)
        assertEquals("3", fields(bursts[0])["count"]); assertEquals("600", fields(bursts[0])["windowS"])
        assertNotEquals(fields(bursts[0])["conn"], fields(bursts[1])["conn"])
    }

    @Test fun `corrections up, station change, lost - never the base position`() {
        val c = ReceiverCorrections(isReceiving = true, channel = "NTRIP", stationId = "PAWB", ageS = 1.0,
            baseLatDeg = 41.24567, baseLonDeg = -75.88123, baseHeightM = 180.5)
        t.onCorrections(c); t.onCorrections(c.copy(ageS = 2.0))
        t.onCorrections(c.copy(stationId = "PAHZ")); t.onCorrections(c.copy(isReceiving = false, stationId = "PAHZ"))
        assertEquals(listOf(
            "I CORRECTIONS CORRECTIONS_STATE state=OK via=receiver channel=NTRIP station=PAWB (Corrections stream connected)",
            "W CORRECTIONS Corrections base station PAWB → PAHZ",
            "W CORRECTIONS CORRECTIONS_STATE state=NONE via=receiver (Corrections stream lost)",
        ), lines)
        assertFalse(lines.any { it.contains("41.2") || it.contains("75.8") || it.contains("180.5") })
        assertEquals("not receiving", t.correctionsSummary)
    }
}
