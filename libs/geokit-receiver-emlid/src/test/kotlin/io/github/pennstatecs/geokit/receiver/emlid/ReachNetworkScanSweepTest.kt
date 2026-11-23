package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * [ReachNetworkScan] sweep / discover over a tiny loopback subnet (127.0.0.1–2, port 80 is whatever
 * the machine has: the assertions only rely on the sweep summary), the "subnet not computable" skip,
 * and [ReachNetworkScan.validateHttpService] response shapes against an in-process server.
 */
class ReachNetworkScanSweepTest {

    private class Lines : LinkLog {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun d(tag: String, message: () -> String) {}
        override fun i(tag: String, message: String) { lines += "I $tag $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W $tag $message" }
        override fun logOnChange(tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String) = true
        override fun every(tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String) = true
        override fun resetThrottles(keyPrefix: String) {}
    }

    /** A loopback server answering every request with [response] verbatim. */
    private fun rawServer(response: String): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { c ->
                    c.getInputStream().bufferedReader().readLine()
                    c.getOutputStream().write(response.toByteArray())
                    c.getOutputStream().flush()
                }
            }
        }
        return server
    }

    @Test fun sweep_unparsableAddress_isSkippedWithReason() = runBlocking {
        val log = Lines()
        val scan = ReachNetworkScan(log = log)
        scan.sweep("not-an-ip", "255.255.255.0", ReachDeviceSet(log))
        scan.sweep("192.168.1.10", "garbage", ReachDeviceSet(log))
        assertEquals(
            listOf(
                "W RECEIVER External receiver discovery HTTP sweep skipped: subnet not computable mask=255.255.255.0",
                "W RECEIVER External receiver discovery HTTP sweep skipped: subnet not computable mask=garbage",
            ),
            log.lines.toList(),
        )
    }

    @Test fun sweep_triesEveryHostOfTheSubnet_andLogsOneSummary() = runBlocking {
        val log = Lines()
        val found = ReachDeviceSet(log)
        val announced = Collections.synchronizedList(mutableListOf<ReachDevice>())
        withContext(Dispatchers.IO) {
            ReachNetworkScan(log = log).sweep("127.0.0.1", "255.255.255.252", found) { announced += it }
        }
        val summary = log.lines.single { it.contains("HTTP sweep completed") }
        assertTrue(summary, summary.startsWith("I RECEIVER External receiver discovery HTTP sweep completed swept=2 found="))
        val hits = summary.substringAfter("found=").toInt()
        assertEquals(hits, found.all().size)
        assertEquals(hits, announced.size)
        assertTrue(found.all().all { it.discoveryMethod == ReachNetworkScan.METHOD_HTTP_SWEEP })
    }

    @Test fun discover_sweepsTheLocalNetwork_andMapsToDiscoveredReceivers() = runBlocking {
        val log = Lines()
        val scan = ReachNetworkScan(localNetwork = { "127.0.0.1" to "255.255.255.252" }, profileKey = "reach_rs2_plus", log = log)
        val result = scan.discover(5_000)
        assertTrue(log.lines.toString(), log.lines.any { it.contains("HTTP sweep completed swept=2") })
        assertTrue(result.all { it.profileKey == "reach_rs2_plus" })
    }

    @Test fun discover_timeout_returnsWhatWasFoundSoFar() = runBlocking {
        val scan = ReachNetworkScan(localNetwork = { "127.0.0.1" to "255.255.255.0" }, log = Lines())
        val t0 = System.nanoTime()
        val result = scan.discover(1)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
        assertTrue(result.size <= 254)
    }

    @Test fun validate_keywordOnALaterLine_orRs2_isAReach() = runBlocking {
        val scan = ReachNetworkScan(log = Lines())
        rawServer("HTTP/1.0 200 OK\r\nServer: x\r\n\r\n<html>\r\n<body>Reach RS4</body>\r\n").use {
            assertTrue(scan.validateHttpService("127.0.0.1", it.localPort))
        }
        rawServer("HTTP/1.0 200 OK\r\n\r\nmodel: RS2+\r\n").use {
            assertTrue(scan.validateHttpService("127.0.0.1", it.localPort))
        }
    }

    @Test fun validate_onlyTheFirstTenLinesAreRead() = runBlocking {
        val scan = ReachNetworkScan(log = Lines())
        val filler = (1..12).joinToString("") { "line $it\r\n" }
        rawServer("HTTP/1.0 200 OK\r\n$filler<title>Emlid</title>\r\n").use {
            assertFalse("the keyword after line 10 is never read", scan.validateHttpService("127.0.0.1", it.localPort))
        }
        rawServer("").use {
            assertFalse("an empty response is not a Reach", scan.validateHttpService("127.0.0.1", it.localPort))
        }
    }
}
