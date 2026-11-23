package io.github.pennstatecs.geokit.gnss.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The [GnssLog] channels not covered by GnssLogTest: diagnostic levels, dEvery, event, w/e, NoOp. */
class GnssLogChannelsTest {

    private class Recording(override var enabled: Boolean = true) : GnssLogger {
        val lines = mutableListOf<String>()
        val throwables = mutableListOf<Throwable?>()
        private val due = mutableSetOf<String>()
        override fun d(tag: String, message: String) { lines += "D/$tag: $message" }
        override fun i(tag: String, message: String) { lines += "I/$tag: $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W/$tag: $message"; throwables += throwable }
        override fun e(tag: String, message: String, throwable: Throwable?) { lines += "E/$tag: $message"; throwables += throwable }
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {
            lines += "DIAG-${level.name}/$tag: $message"; throwables += throwable
        }
        override fun isDue(key: String, intervalMs: Long): Boolean = due.add(key)
        override fun trace(area: String, message: String) { lines += "[$area] $message" }
    }

    @After fun tearDown() = GnssLog.uninstall()

    @Test fun diagnosticLevels_andThrowablesArePassedThrough() {
        val r = Recording(enabled = false)    // diagnostics are written regardless of tracing
        GnssLog.install(r)
        val boom = IllegalStateException("x")
        GnssLog.diagnostic("NMEA", "info")
        GnssLog.diagnostic("NMEA", "err", DiagnosticLevel.ERROR, boom)
        GnssLog.diagnosticWarn("NMEA", "warn")
        GnssLog.diagnosticError("NMEA", "error", boom)
        GnssLog.w("T", "w", boom)
        GnssLog.e("T", "e")
        assertEquals(
            listOf("DIAG-INFO/NMEA: info", "DIAG-ERROR/NMEA: err", "DIAG-WARN/NMEA: warn", "DIAG-ERROR/NMEA: error",
                "W/T: w", "E/T: e"), r.lines
        )
        assertEquals(listOf(null, boom, null, boom, boom, null), r.throwables)
    }

    @Test fun dEvery_isThrottledPerKey_andSilentWhileDisabled() {
        val r = Recording()
        GnssLog.install(r)
        var built = 0
        GnssLog.dEvery("T", "k") { built++; "one" }
        GnssLog.dEvery("T", "k") { built++; "two" }
        GnssLog.dEvery("T", "other", intervalMs = 1L) { built++; "three" }
        assertEquals(listOf("D/T: one", "D/T: three"), r.lines)
        assertEquals(2, built)
        r.enabled = false
        GnssLog.dEvery("T", "new") { built++; "four" }
        assertEquals(2, built)
    }

    @Test fun event_isUnthrottled_andOnlyWhileEnabled() {
        val r = Recording()
        GnssLog.install(r)
        GnssLog.event("fuser") { "a" }
        GnssLog.event("fuser") { "a" }
        r.enabled = false
        GnssLog.event("fuser") { error("must not be built") }
        assertEquals(listOf("[fuser] a", "[fuser] a"), r.lines)
    }

    @Test fun lambdaInfoAndWarn_onlyWhileEnabled() {
        val r = Recording()
        GnssLog.install(r)
        GnssLog.i("T") { "i" }
        GnssLog.w("T") { "w" }
        r.enabled = false
        GnssLog.i("T") { error("no") }
        GnssLog.w("T") { error("no") }
        GnssLog.d("T", "plain")
        assertEquals(listOf("I/T: i", "W/T: w"), r.lines)
        assertFalse(GnssLog.enabled)
    }

    @Test fun noOp_discardsEverything() {
        val n = GnssLogger.NoOp
        assertFalse(n.enabled)
        n.d("t", "m"); n.i("t", "m"); n.w("t", "m", null); n.e("t", "m", null)
        n.diagnostic("t", "m", DiagnosticLevel.WARN, null)
        n.trace("a", "m")
        assertFalse(n.isDue("k", 1))
        GnssLog.uninstall()
        GnssLog.diagnosticWarn("t", "nothing happens")
        assertTrue(GnssLog.logger === GnssLogger.NoOp)
    }

    @Test fun diagnosticLevel_values() {
        assertEquals(listOf("INFO", "WARN", "ERROR"), DiagnosticLevel.entries.map { it.name })
    }
}
