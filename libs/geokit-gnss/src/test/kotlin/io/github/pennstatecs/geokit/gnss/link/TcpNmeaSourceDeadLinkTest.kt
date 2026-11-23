package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A half-open link (receiver gone, socket never closed) is detected after two silent read timeouts
 * (~10 s) so the adapter's reconnect starts; a normal 1 Hz stream never trips it. Real sockets, real
 * time, default timeouts.
 */
class TcpNmeaSourceDeadLinkTest {

    private val gga = "\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001"

    /** Accepts one client and runs [script] on it; the client socket is never closed by the server. */
    private fun server(script: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { runCatching { val c = server.accept(); script(c) } }
        return server
    }

    private fun source(scope: CoroutineScope, port: Int): TcpNmeaSource =
        TcpNmeaSource(scope, endpoint = { "127.0.0.1" to port }, registry = DefaultNmeaRegistry.create(verifyChecksum = false))

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    @Test fun `a silent half-open link ends the connection within about 10 s`() {
        val lastSentNs = java.util.concurrent.atomic.AtomicLong(0L)
        val server = server { c ->
            val out = c.getOutputStream().bufferedWriter()
            repeat(3) { out.write(gga + "\r\n"); out.flush(); lastSentNs.set(System.nanoTime()); Thread.sleep(1_000) }
            Thread.sleep(60_000)                     // then silence, socket left open
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val src = source(scope, server.localPort)
            src.start()
            assertTrue("connected", waitUntil(5_000) { src.isConnectionActive() && lastSentNs.get() != 0L })
            assertTrue("connection ended", waitUntil(16_000) { !src.isConnectionActive() })
            val detectedAfterS = (System.nanoTime() - lastSentNs.get()) / 1e9
            println("dead link detected %.1f s after the last NMEA line".format(detectedAfterS))
            assertTrue("detected after %.1f s (expected ~10-12 s)".format(detectedAfterS), detectedAfterS in 8.0..12.5)
        } finally {
            scope.cancel(); server.close()
        }
    }

    @Test fun `a normal 1 Hz stream never trips the dead-link check`() {
        val server = server { c ->
            val out = c.getOutputStream().bufferedWriter()
            repeat(14) { out.write(gga + "\r\n"); out.flush(); Thread.sleep(1_000) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val src = source(scope, server.localPort)
            src.start()
            assertTrue(waitUntil(5_000) { src.isConnectionActive() })
            val endNs = System.nanoTime() + 12_000_000_000L
            while (System.nanoTime() < endNs) {
                assertTrue("1 Hz stream must stay connected", src.isConnectionActive())
                Thread.sleep(200)
            }
        } finally {
            scope.cancel(); server.close()
        }
    }
}
