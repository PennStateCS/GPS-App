package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The device endpoints are polled every 30 s: an outcome is logged when it changes, not per poll. */
class ReachDeviceServiceLogTest {

    private class Recording : GnssLogger {
        override val enabled = false
        val diag = mutableListOf<String>()
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) { diag += "$level $message" }
        override fun isDue(key: String, intervalMs: Long): Boolean = false
        override fun trace(area: String, message: String) {}
    }

    @After fun tearDown() = GnssLog.uninstall()

    @Test fun `repeated failures log once per path, recovery once, first success silent`() {
        val log = Recording().also { GnssLog.install(it) }
        val svc = ReachDeviceService(ReachHttpClient("127.0.0.1"))
        svc.logOutcome("/status", "ok") { "status ok" }
        repeat(5) { svc.logOutcome("/info", "no-fields") { "info no fields" } }
        repeat(3) { svc.logOutcome("/status", "failed:SocketTimeoutException") { "status failed" } }
        svc.logOutcome("/status", "ok") { "status ok again" }
        svc.logOutcome("/status", "ok") { "status ok again" }
        assertEquals(listOf("WARN info no fields", "WARN status failed", "INFO status ok again"), log.diag)
    }
}
