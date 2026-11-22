package app.surrealar.util.diagnostics

import app.surrealar.util.diagnostics.MainThreadWatchdog.Detector
import app.surrealar.util.diagnostics.MainThreadWatchdog.Detector.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class MainThreadWatchdogTest {
    @Test fun `a stall is reported once, again when long, then recovery`() {
        val d = Detector(stallMs = 5_000, longStallMs = 30_000)
        val events = mutableListOf<Event>()
        var lastAck = 0L
        for (now in 1_000L..60_000L step 1_000L) {
            if (now <= 10_000L) lastAck = now                       // healthy for 10 s
            if (now >= 50_000L) lastAck = now                       // main thread comes back at 50 s and stays healthy
            d.check(now, lastAck)?.let { events += it }
        }
        assertEquals(listOf(
            Event.Stalled(5_000, long = false),
            Event.Stalled(30_000, long = true),
            Event.Recovered(40_000),
        ), events)
    }

    @Test fun `short hiccups are ignored`() {
        val d = Detector()
        assertEquals(null, d.check(4_900, 0))
    }
}