package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class ReachNetworkScanTest {

    private class Lines : LinkLog {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun d(tag: String, message: () -> String) {}
        override fun i(tag: String, message: String) { lines += "I $tag $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W $tag $message" }
        override fun logOnChange(tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String) = true
        override fun every(tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String) = true
        override fun resetThrottles(keyPrefix: String) {}
    }

    /** A loopback HTTP server answering every request with [body]. */
    private fun httpServer(body: String): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { c ->
                    c.getInputStream().bufferedReader().readLine()
                    c.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n$body\r\n".toByteArray())
                }
            }
        }
        return server
    }

    @Test fun `a Reach web page is recognised, any other page is not`() = runBlocking {
        val scan = ReachNetworkScan(log = Lines())
        httpServer("<title>Emlid Flow</title>").use { s -> assertTrue(scan.validateHttpService("127.0.0.1", s.localPort)) }
        httpServer("<title>Router admin</title>").use { s -> assertFalse(scan.validateHttpService("127.0.0.1", s.localPort)) }
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        assertFalse("nothing listening", scan.validateHttpService("127.0.0.1", closed))
    }

    @Test fun `port probe returns within its timeout`() = runBlocking {
        // Whether 5000/9001 are open on this machine isn't known; the probe must just not hang.
        val t0 = System.nanoTime()
        ReachNetworkScan().probeBothPorts("127.0.0.1")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun `sweep without a local address is skipped and says why`() = runBlocking {
        val log = Lines()
        val scan = ReachNetworkScan(log = log)
        scan.sweep(null, null, ReachDeviceSet(log))
        scan.sweep("0.0.0.0", "255.255.255.0", ReachDeviceSet(log))
        assertEquals(
            listOf(
                "W RECEIVER External receiver discovery HTTP sweep skipped: no valid local IP",
                "W RECEIVER External receiver discovery HTTP sweep skipped: no valid local IP",
            ),
            log.lines.toList(),
        )
    }

    @Test fun `discover with no network finds nothing`() = runBlocking {
        val log = Lines()
        assertTrue(ReachNetworkScan(localNetwork = { null to null }, log = log).discover(1_000).isEmpty())
    }

    @Test fun `device set merges finds of the same IP and logs only changes`() {
        val log = Lines()
        val set = ReachDeviceSet(log)
        val mdns = ReachDevice("192.168.1.50", hostname = "reach-a.local", port9001Open = true, discoveryMethod = "mdns")
        assertEquals(mdns, set.add(mdns))
        assertNull("same device again: nothing new", set.add(mdns))
        val sweep = ReachDevice("192.168.1.50", port5000Open = true, discoveryMethod = "http_sweep")
        val merged = set.add(sweep)!!
        assertEquals("reach-a.local", merged.hostname)
        assertTrue(merged.port5000Open && merged.port9001Open)
        assertEquals("http_sweep", merged.discoveryMethod)
        assertEquals(listOf(merged), set.all())
        assertEquals(
            listOf(
                "I RECEIVER External receiver discovered ip=192.168.1.50 method=mdns port5000=false port9001=true",
                "I RECEIVER External receiver discovered ip=192.168.1.50 method=http_sweep port5000=true port9001=true",
            ),
            log.lines.toList(),
        )
    }

    @Test fun `as a DiscoveredReceiver`() {
        val d = ReachDevice("192.168.1.50", hostname = "reach-a.local", port9001Open = true, discoveryMethod = "mdns")
            .toDiscoveredReceiver("reach_rs2_plus")
        assertEquals("192.168.1.50", d.host)
        assertEquals("reach-a", d.label)
        assertEquals("reach_rs2_plus", d.profileKey)
        assertEquals(9001, d.nmeaPort)
        val bare = ReachDevice("10.0.0.2", hostname = " ", discoveryMethod = "http_sweep").toDiscoveredReceiver(null)
        assertEquals("10.0.0.2", bare.label)
        assertNull(bare.nmeaPort)
        assertNull(bare.profileKey)
    }

    @Test fun `toString keeps its compact form`() {
        assertEquals(
            "ReachDevice(ip=10.0.0.2, host=r, 5000=true, 9001=false, via=mdns, ssid=s)",
            ReachDevice("10.0.0.2", "r", true, false, "mdns", "s").toString(),
        )
    }
}
