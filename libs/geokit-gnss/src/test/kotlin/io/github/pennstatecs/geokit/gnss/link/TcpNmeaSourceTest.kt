package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Endpoint, line hook and connection log of the TCP source, against a loopback server. */
class TcpNmeaSourceTest {

    private class Lines : LinkLog {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun d(tag: String, message: () -> String) {}
        override fun i(tag: String, message: String) { lines += message }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += message }
        override fun logOnChange(tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String): Boolean {
            lines += message(); return true
        }
        override fun every(tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String): Boolean {
            lines += message(); return true
        }
        override fun resetThrottles(keyPrefix: String) {}
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun `no endpoint ends the attempt at once and says so`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = Lines()
            val src = TcpNmeaSource(scope, endpoint = { null }, registry = DefaultNmeaRegistry.create(), linkLog = log)
            src.start()
            assertTrue(waitUntil(2_000) { !src.isConnectionActive() })
            assertEquals(listOf("External receiver connect skipped: no host or port configured"), log.lines.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `sentences reach onLine and become fixes, other lines are ignored`() {
        val gga = "\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001"
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { c ->
                    val out = c.getOutputStream().bufferedWriter()
                    out.write("garbage\r\n"); out.write("  $gga  \r\n"); out.flush()
                    Thread.sleep(500)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = Lines()
            val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val src = TcpNmeaSource(
                scope, endpoint = { "127.0.0.1" to server.localPort },
                registry = DefaultNmeaRegistry.create(verifyChecksum = false), onLine = { seen += it }, linkLog = log,
            )
            src.start()
            assertTrue("fix", waitUntil(5_000) { src.parsedFixes().replayCache.isNotEmpty() })
            assertEquals(listOf(gga), seen.toList())
            assertTrue("remote close ends it", waitUntil(5_000) { !src.isConnectionActive() })
            val port = server.localPort
            assertEquals(
                listOf(
                    "External receiver connecting host=127.0.0.1 port=$port",
                    "External receiver TCP socket connected host=127.0.0.1 port=$port",
                    "External receiver first NMEA sentence received host=127.0.0.1 port=$port",
                    "External receiver TCP connection closed by remote host=127.0.0.1 port=$port",
                    "External receiver TCP disconnected host=127.0.0.1 port=$port",
                ),
                log.lines.toList(),
            )
            src.stop()
            assertFalse(src.isConnectionActive())
            assertTrue("stop clears the replayed fix", src.parsedFixes().replayCache.isEmpty())
        } finally {
            scope.cancel(); server.close()
        }
    }
}
