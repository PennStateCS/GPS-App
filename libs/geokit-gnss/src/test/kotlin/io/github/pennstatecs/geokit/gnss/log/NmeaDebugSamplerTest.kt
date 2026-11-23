package io.github.pennstatecs.geokit.gnss.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaDebugSamplerTest {

    private class Traces(override val enabled: Boolean) : GnssLogger {
        val traces = mutableListOf<Pair<String, String>>()
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {}
        override fun isDue(key: String, intervalMs: Long): Boolean = true
        override fun trace(area: String, message: String) { traces += area to message }
    }

    @After fun tearDown() = GnssLog.uninstall()

    @Test fun `dumps the latest sentence of each kind once per interval`() {
        val t = Traces(enabled = true).also { GnssLog.install(it) }
        var now = 1L
        val s = NmeaDebugSampler("nmea-ext", intervalMs = 1_000L) { now }
        s.accept("\$GNGGA,1,old")
        s.accept("\$GNGGA,2,new")
        s.accept("\$GPGSV,3,2,12,x,1")          // not the first page: skipped
        s.accept("\$GPGSV,3,1,12,x,1*55")
        s.accept("\$PEMLID,ETC,1")               // proprietary: kept as "other"
        assertTrue(t.traces.isEmpty())
        now = 1_001L
        s.accept("\$GNRMC,1,a") { "host:9001" }
        assertEquals(1, t.traces.size)
        val (area, text) = t.traces[0]
        assertEquals("nmea-ext", area)
        assertEquals(
            listOf("---- raw sample (host:9001) ----", "\$GNGGA,2,new", "\$GPGSV,3,1,12,x,1*55", "\$PEMLID,ETC,1", "\$GNRMC,1,a"),
            text.lines(),
        )
    }

    @Test fun `does nothing while tracing is disabled`() {
        val t = Traces(enabled = false).also { GnssLog.install(it) }
        var now = 1L
        val s = NmeaDebugSampler("nmea-int", intervalMs = 1L) { now }
        s.accept("\$GNGGA,1"); now = 10L; s.accept("\$GNGGA,2")
        assertTrue(t.traces.isEmpty())
    }
}
