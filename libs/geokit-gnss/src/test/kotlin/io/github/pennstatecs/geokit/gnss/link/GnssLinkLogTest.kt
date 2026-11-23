package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GnssLinkLogTest {

    private class Recording : GnssLogger {
        val lines = mutableListOf<String>()
        override val enabled: Boolean = true
        override fun d(tag: String, message: String) { lines += "D $tag $message" }
        override fun i(tag: String, message: String) { lines += "i $tag $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "w $tag $message" }
        override fun e(tag: String, message: String, throwable: Throwable?) { lines += "e $tag $message" }
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {
            lines += "${level.name} $tag $message" + (throwable?.let { " +${it.javaClass.simpleName}" } ?: "")
        }
        override fun isDue(key: String, intervalMs: Long): Boolean = true
        override fun trace(area: String, message: String) {}
    }

    private val rec = Recording()
    private var now = 0L
    private val log = GnssLinkLog { now }

    @Before fun setUp() = GnssLog.install(rec)
    @After fun tearDown() = GnssLog.uninstall()

    @Test fun `levels map to the GnssLog diagnostic channel, debug to d`() {
        log.d("RECEIVER") { "detail" }
        log.i("RECEIVER", "connected")
        log.w("RECEIVER", "failed", IllegalStateException("x"))
        assertEquals(
            listOf("D RECEIVER detail", "INFO RECEIVER connected", "WARN RECEIVER failed +IllegalStateException"),
            rec.lines,
        )
    }

    @Test fun `logOnChange writes only when the value changes`() {
        assertTrue(log.logOnChange("RECEIVER", "k", "a") { "one" })
        assertFalse(log.logOnChange("RECEIVER", "k", "a") { "two" })
        assertTrue(log.logOnChange("RECEIVER", "k", "b", LinkLogLevel.WARN) { "three" })
        assertTrue(log.logOnChange("RECEIVER", "k", null) { "four" })
        assertFalse(log.logOnChange("RECEIVER", "k", null) { "five" })
        assertEquals(listOf("INFO RECEIVER one", "WARN RECEIVER three", "INFO RECEIVER four"), rec.lines)
    }

    @Test fun `an info line with a throwable names the exception`() {
        log.logOnChange("RECEIVER", "k", 1, throwable = RuntimeException("why")) { "msg" }
        assertEquals(listOf("INFO RECEIVER msg (RuntimeException: why)"), rec.lines)
    }

    @Test fun `every throttles per key on the injected clock`() {
        assertTrue(log.every("NMEA", "e", 60_000L, LinkLogLevel.ERROR) { "first" })
        now = 59_999L
        assertFalse(log.every("NMEA", "e", 60_000L, LinkLogLevel.ERROR) { "throttled" })
        now = 60_000L
        assertTrue(log.every("NMEA", "e", 60_000L, LinkLogLevel.ERROR) { "again" })
        assertEquals(listOf("ERROR NMEA first", "ERROR NMEA again"), rec.lines)
    }

    @Test fun `resetThrottles forgets only keys with the prefix`() {
        log.logOnChange("RECEIVER", "rx.retry.a", true) { "a" }
        log.logOnChange("RECEIVER", "other", true) { "o" }
        log.resetThrottles("rx.retry.")
        assertTrue(log.logOnChange("RECEIVER", "rx.retry.a", true) { "a again" })
        assertFalse(log.logOnChange("RECEIVER", "other", true) { "o again" })
    }
}
