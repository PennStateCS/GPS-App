package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import io.socket.client.IO
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** Base-station coordinates and raw broadcast JSON never reach release logs; debug logs are throttled. */
class ReachCorrectionsLoggingTest {

    private class Recording(override var enabled: Boolean) : GnssLogger {
        val lines = mutableListOf<String>()
        private val due = mutableSetOf<String>()
        override fun d(tag: String, message: String) { lines += "D/$tag: $message" }
        override fun i(tag: String, message: String) { lines += "I/$tag: $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W/$tag: $message" }
        override fun e(tag: String, message: String, throwable: Throwable?) { lines += "E/$tag: $message" }
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) { lines += "DIAG/$tag: $message" }
        override fun isDue(key: String, intervalMs: Long): Boolean = due.add(key)   // once per key
        override fun trace(area: String, message: String) { lines += "[$area] $message" }
    }

    private class NullSocket : CorrectionsSocket {
        val listeners = mutableMapOf<String, (Array<out Any?>) -> Unit>()
        override fun on(event: String, listener: (Array<out Any?>) -> Unit) { listeners[event] = listener }
        override fun off(event: String) { listeners.remove(event) }
        override fun connect() {}
        override fun disconnect() {}
        override fun connected(): Boolean = true.also { }
    }

    @After fun tearDown() = GnssLog.uninstall()

    private val navigation = JSONObject(
        """{"name":"navigation","payload":{"aod":1.2,"solution":"fix","baseline":4594.8,"satellites":{"base":18},
           "base_position":{"coordinates":{"lat":41.2345678,"lon":-76.1234567,"h":380.1}}}}"""
    )

    private fun broadcastMany(logger: Recording) {
        GnssLog.install(logger)
        val socket = NullSocket()
        val svc = ReachCorrectionsService("10.0.0.2", socketFactory = { _: URI, _: IO.Options -> socket })
        svc.connect()
        repeat(20) { socket.listeners.getValue("broadcast").invoke(arrayOf(navigation)) }
    }

    @Test fun `release builds log no base coordinates or raw JSON per broadcast`() {
        val r = Recording(enabled = false)
        broadcastMany(r)
        assertTrue(r.lines.joinToString("\n"), r.lines.none { "41.234" in it || "-76.123" in it || "base_position" in it })
        assertTrue("no debug lines in release", r.lines.none { it.startsWith("D/") })
    }

    @Test fun `debug builds trace each broadcast kind at most once per interval, under the corrections area`() {
        val r = Recording(enabled = true)
        broadcastMany(r)
        assertEquals(1, r.lines.count { it.startsWith("[corrections] navigation broadcast:") })
        assertEquals(1, r.lines.count { it.startsWith("[corrections] navigation parsed:") })
        // Coordinates appear only in the GNSS debug trace, never in a plain debug line.
        assertTrue(r.lines.filter { it.startsWith("D/") }.none { "41.234" in it || "base_position" in it })
    }

    @Test fun `the CORRECTIONS line prints the metre baseline in km`() {
        val r = Recording(enabled = false)
        broadcastMany(r)
        val line = r.lines.single { it.startsWith("DIAG/CORRECTIONS: Corrections RTK solution changed solution=fix") }
        assertTrue(line, "baselineKm=4.59 " in line)
    }

    @Test fun `formatBaselineKm converts metres and marks unknown`() {
        assertEquals("4.59", formatBaselineKm(4594.8))
        assertEquals("0.00", formatBaselineKm(0.0))
        assertEquals("unknown", formatBaselineKm(null))
    }
}
