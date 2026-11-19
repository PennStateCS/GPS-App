package app.surrealar.gnss.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticsService] is THE live NMEA-rate source for the UI (see CLAUDE.md: wiring the UI to
 * NmeaLogger instead caused the "NMEA 0/s" bug). Metrics publish at most once per second, so the
 * tests that check published values wait just over 1 s once.
 */
class DiagnosticsServiceTest {

    private fun waitForNextPublishWindow() = Thread.sleep(1_050)

    @Test
    fun initialState_isZeroed() {
        val d = DiagnosticsService().diagnosticData.value
        assertEquals(0.0, d.linesPerSecond, 0.0)
        assertEquals(0L, d.totalLinesProcessed)
        assertEquals(0L, d.totalParseErrors)
        assertTrue(d.lastTwentySentences.isEmpty())
    }

    @Test
    fun linesWithinFirstSecond_areNotPublishedYet() {
        val svc = DiagnosticsService()
        repeat(5) { svc.recordLine("\$GNGGA,$it") }
        // Throttled: nothing published until a full second has elapsed since construction.
        assertEquals(0L, svc.diagnosticData.value.totalLinesProcessed)
    }

    @Test
    fun publishesTotalsErrorRateRateAndCappedHistory() {
        val svc = DiagnosticsService()
        repeat(24) { svc.recordLine("  \$GNGGA,line$it  ") }
        svc.recordParseError("\$GNBAD,oops")
        waitForNextPublishWindow()
        svc.recordLine("\$GNRMC,last")   // triggers the publish

        val d = svc.diagnosticData.value
        assertEquals(26L, d.totalLinesProcessed)       // parse errors count as lines too
        assertEquals(1L, d.totalParseErrors)
        assertEquals(100.0 / 26.0, d.parseErrorRate, 1e-9)
        assertTrue("rate should be > 0, was ${d.linesPerSecond}", d.linesPerSecond > 0.0)
        assertTrue("rate should be < 26/s, was ${d.linesPerSecond}", d.linesPerSecond < 26.0)

        assertEquals(20, d.lastTwentySentences.size)
        assertEquals("\$GNRMC,last", d.lastTwentySentences.last())
        assertTrue(d.lastTwentySentences.none { it.startsWith(" ") || it.endsWith(" ") })  // trimmed
        assertTrue(d.lastTwentySentences.any { it.contains("\$GNBAD,oops") && !it.startsWith("\$") })  // error marked
        assertTrue(d.lastTwentySentences.none { it.endsWith("line0") })  // oldest evicted
    }

    @Test
    fun reset_clearsEverything() {
        val svc = DiagnosticsService()
        repeat(3) { svc.recordLine("x") }
        svc.recordParseError("y")
        svc.reset()

        val d = svc.diagnosticData.value
        assertEquals(0L, d.totalLinesProcessed)
        assertEquals(0L, d.totalParseErrors)
        assertTrue(d.lastTwentySentences.isEmpty())

        // Counters really restarted (not just the published snapshot).
        waitForNextPublishWindow()
        svc.recordLine("after")
        assertEquals(1L, svc.diagnosticData.value.totalLinesProcessed)
        assertEquals(listOf("after"), svc.diagnosticData.value.lastTwentySentences)
    }
}
