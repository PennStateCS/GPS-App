package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

/** A receiver that refuses the socket for minutes logs a handful of lines, not one per retry. */
class ReachCorrectionsErrorLogTest {

    private class Recording : GnssLogger {
        override var enabled = false
        val diag = mutableListOf<String>()
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) { diag += message }
        override fun isDue(key: String, intervalMs: Long): Boolean = false
        override fun trace(area: String, message: String) {}
    }

    private class FakeSocket : CorrectionsSocket {
        val listeners = mutableMapOf<String, (Array<out Any?>) -> Unit>()
        override fun on(event: String, listener: (Array<out Any?>) -> Unit) { listeners[event] = listener }
        override fun off(event: String) { listeners.remove(event) }
        override fun connect() {}
        override fun disconnect() {}
        override fun connected(): Boolean = false
        fun fire(event: String, vararg args: Any?) = listeners[event]?.invoke(args)
    }

    @After fun tearDown() = GnssLog.uninstall()

    @Test fun `first error at once, then one line a minute, then connected after N attempts`() {
        val log = Recording().also { GnssLog.install(it) }
        var now = 0L
        val socket = FakeSocket()
        val svc = ReachCorrectionsService("192.168.2.174", nowMs = { now }, socketFactory = { _: URI, _: IO.Options -> socket })
        svc.connect()
        // 3 minutes of retries every 15 s = 13 errors.
        for (i in 0..12) { now = i * 15_000L; socket.fire(Socket.EVENT_CONNECT_ERROR, "server error") }
        now = 200_000L; socket.fire(Socket.EVENT_CONNECT)
        assertEquals(listOf(
            "connect attempt host=192.168.2.174",
            "socket connect error host=192.168.2.174 (will retry): server error",
            "socket still failing host=192.168.2.174: 5 attempts in 60s, last error: server error",
            "socket still failing host=192.168.2.174: 9 attempts in 120s, last error: server error",
            "socket still failing host=192.168.2.174: 13 attempts in 180s, last error: server error",
            "socket connected host=192.168.2.174 after 13 failed attempts",
        ), log.diag)

        // Data arrives (the link really works), so a later outage starts a fresh count.
        socket.fire("broadcast", JSONObject("{\"name\":\"time_marks\"}"))
        log.diag.clear()
        now = 300_000L; socket.fire(Socket.EVENT_CONNECT_ERROR, "timeout")
        assertEquals(listOf("socket connect error host=192.168.2.174 (will retry): timeout"), log.diag)
    }

    @Test fun `a socket that connects and fails without data does not repeat the first error`() {
        val log = Recording().also { GnssLog.install(it) }
        var now = 0L
        val socket = FakeSocket()
        val svc = ReachCorrectionsService("192.168.2.174", nowMs = { now }, socketFactory = { _: URI, _: IO.Options -> socket })
        svc.connect()
        // Flapping every 2 s for 50 s: connect, then error/disconnect, never any broadcast.
        for (i in 0 until 25) {
            now = i * 2_000L
            socket.fire(Socket.EVENT_CONNECT_ERROR, "xhr poll error")
            socket.fire(Socket.EVENT_CONNECT)
            socket.fire(Socket.EVENT_DISCONNECT, "transport error")
        }
        assertEquals(listOf(
            "connect attempt host=192.168.2.174",
            "socket connect error host=192.168.2.174 (will retry): xhr poll error",
            "socket connected host=192.168.2.174 after 1 failed attempts",
        ), log.diag)
    }
}