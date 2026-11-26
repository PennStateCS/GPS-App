package app.surrealar.receiver.selection

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real TCP probe against local sockets: cancelling it closes its socket promptly (review fix L10). */
class SocketReceiverProbeTest {

    @Test fun `cancelling the probe closes its socket at once when the receiver never answers`() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val closedByPeer = CountDownLatch(1)
            val readResult = AtomicInteger(Int.MIN_VALUE)
            // A "receiver" that accepts and never sends anything.
            val serverThread = Thread {
                runCatching {
                    server.accept().use { s ->
                        accepted.countDown()
                        s.soTimeout = 10_000
                        readResult.set(runCatching { s.getInputStream().read() }.getOrDefault(-2))
                        closedByPeer.countDown()
                    }
                }
            }.apply { isDaemon = true; start() }

            val probe = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                SocketReceiverProbe.tcp("127.0.0.1", server.localPort)
            }
            assertTrue("probe connected", accepted.await(5, TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            probe.cancel()
            withTimeout(2_000) { probe.join() }      // without the fix: up to the 5 s read window
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("probe ended ${elapsedMs} ms after cancel", elapsedMs < 1_500)
            assertTrue("the receiver saw the socket close", closedByPeer.await(2, TimeUnit.SECONDS))
            assertEquals("EOF (closed), not a timeout", -1, readResult.get())
            assertTrue(probe.isCancelled)
            serverThread.join(1_000)
        }
    }

    /** A one-shot "receiver" that writes [text] and keeps the socket open for [holdMs]. */
    private fun serve(server: ServerSocket, text: String, holdMs: Long = 500) {
        Thread {
            runCatching {
                server.accept().use { s ->
                    s.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                    Thread.sleep(holdMs)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Test fun `a receiver that streams NMEA is still recognised`() = runBlocking {
        ServerSocket(0).use { server ->
            // (This used the textbook GPGGA checksum *47, which does not verify for GNGGA: item 9 now rejects it.)
            serve(server, "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*59\r\n")
            assertEquals(TcpProbeResult.RECEIVING_NMEA, SocketReceiverProbe.tcp("127.0.0.1", server.localPort))
        }
    }

    @Test fun `a receiver without a fix is reachable with no position yet`() = runBlocking {
        ServerSocket(0).use { server ->
            serve(server, "\$GNGGA,123519,,,,,0,00,,,M,,M,,*75\r\n")
            assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, SocketReceiverProbe.tcp("127.0.0.1", server.localPort))
        }
    }

    @Test fun `dollar text that is not NMEA is reported as invalid when the peer closes`() = runBlocking {
        ServerSocket(0).use { server ->
            serve(server, "\$hello\r\n\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n", holdMs = 0)
            val t0 = System.nanoTime()
            assertEquals(TcpProbeResult.INVALID_NMEA, SocketReceiverProbe.tcp("127.0.0.1", server.localPort))
            assertTrue("decided at end of stream, not after the 5 s window", (System.nanoTime() - t0) / 1_000_000 < 4_000)
        }
    }
}
