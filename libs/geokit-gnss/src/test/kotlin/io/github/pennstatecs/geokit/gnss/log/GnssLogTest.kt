package io.github.pennstatecs.geokit.gnss.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GnssLogTest {

    private class Recording(override var enabled: Boolean) : GnssLogger {
        val lines = mutableListOf<String>()
        val dueKeys = mutableSetOf<String>()
        override fun d(tag: String, message: String) { lines += "D/$tag: $message" }
        override fun i(tag: String, message: String) { lines += "I/$tag: $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W/$tag: $message" }
        override fun e(tag: String, message: String, throwable: Throwable?) { lines += "E/$tag: $message" }
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {
            lines += if (level == DiagnosticLevel.INFO) "DIAG/$tag: $message" else "DIAG-$level/$tag: $message"
        }
        override fun isDue(key: String, intervalMs: Long): Boolean = dueKeys.add(key)
        override fun trace(area: String, message: String) { lines += "[$area] $message" }
    }

    @After fun tearDown() = GnssLog.uninstall()

    @Test fun `no-op by default`() {
        assertFalse(GnssLog.enabled)
        var evaluated = false
        GnssLog.every("k", "a") { evaluated = true; "x" }
        GnssLog.w("T", "m")
        assertFalse(evaluated)
    }

    @Test fun `routes each channel to the installed logger`() {
        val r = Recording(enabled = true).also { GnssLog.install(it) }
        GnssLog.d("T", "d"); GnssLog.i("T", "i"); GnssLog.w("T", "w"); GnssLog.e("T", "e")
        GnssLog.diagnostic("NMEA", "summary")
        GnssLog.diagnostic("CORRECTIONS", "lost", DiagnosticLevel.WARN)
        assertEquals(listOf("D/T: d", "I/T: i", "W/T: w", "E/T: e", "DIAG/NMEA: summary", "DIAG-WARN/CORRECTIONS: lost"), r.lines)
    }

    @Test fun `every is throttled per key and skips the message when disabled or not due`() {
        val r = Recording(enabled = true).also { GnssLog.install(it) }
        var evaluations = 0
        repeat(3) { GnssLog.every("k1", "fuser") { evaluations++; "one" } }
        GnssLog.every("k2", "sky") { evaluations++; "two" }
        assertEquals(listOf("[fuser] one", "[sky] two"), r.lines)
        assertEquals(2, evaluations)

        r.enabled = false
        GnssLog.every("k3", "sky") { evaluations++; "three" }
        assertEquals(2, evaluations)
    }
    @Test fun `debug lines and lambda overloads are written only while enabled`() {
        val r = Recording(enabled = false).also { GnssLog.install(it) }
        var built = 0
        GnssLog.d("T", "plain")
        GnssLog.d("T") { built++; "lazy d" }
        GnssLog.i("T") { built++; "lazy i" }
        GnssLog.w("T") { built++; "lazy w" }
        GnssLog.dEvery("T", "k") { built++; "every" }
        GnssLog.w("T", "plain warning")              // plain-string i/w/e are unconditional
        assertEquals(listOf("W/T: plain warning"), r.lines)
        assertEquals("nothing formatted while disabled", 0, built)

        r.enabled = true
        GnssLog.d("T", "plain"); GnssLog.d("T") { "lazy d" }; GnssLog.i("T") { "lazy i" }; GnssLog.w("T") { "lazy w" }
        repeat(3) { GnssLog.dEvery("T", "k2") { "every" } }
        assertEquals(listOf("W/T: plain warning", "D/T: plain", "D/T: lazy d", "I/T: lazy i", "W/T: lazy w", "D/T: every"), r.lines)
    }
}