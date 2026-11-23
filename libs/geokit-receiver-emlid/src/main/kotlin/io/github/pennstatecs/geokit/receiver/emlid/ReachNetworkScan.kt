package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.link.GnssLinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore   // suspends instead of blocking an IO thread
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Log tag for the discovery debug lines (same as when this lived in the app's ReachDiscoveryHelper). */
internal const val DISCOVERY_TAG: String = "ReachDiscoveryHelper"

/**
 * Finds Emlid Reach receivers on the local network without Android: an HTTP sweep of the subnet
 * (port 80, 250 ms connect / 300 ms read, 32 hosts at a time, content checked for "reach" / "emlid" /
 * "rs2"), then a probe of ports 5000 and 9001 on each hit.
 *
 * A host that also has mDNS (Android's NsdManager) runs that first and uses [validateHttpService],
 * [probeBothPorts] and a shared [ReachDeviceSet] so both phases report the same results; [sweep] is the
 * fallback. As a [ReceiverDiscovery], [discover] runs the sweep alone.
 *
 * @param localNetwork the device's IPv4 address and netmask as dotted strings (either may be null when
 *   unknown), asked once per [discover].
 * @param profileKey the host's receiver-profile key reported for every receiver [discover] finds.
 * @param log where the RECEIVER discovery lines go.
 */
public class ReachNetworkScan(
    private val localNetwork: () -> Pair<String?, String?> = { null to null },
    private val profileKey: String? = null,
    private val log: LinkLog = GnssLinkLog(),
) : ReceiverDiscovery {

    public companion object {
        private const val HTTP_CONNECT_TIMEOUT_MS = 250  // 250ms for HTTP connections
        private const val HTTP_READ_TIMEOUT_MS = 300     // 300ms for HTTP reads
        private const val SUBNET_CONCURRENCY = 32
        /** Discovery method of receivers found by [sweep]. */
        public const val METHOD_HTTP_SWEEP: String = "http_sweep"
    }

    override suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> {
        val found = ReachDeviceSet(log)
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val (ip, mask) = localNetwork()
                sweep(ip, mask, found)
            }
        }
        return found.all().map { it.toDiscoveredReceiver(profileKey) }
    }

    /**
     * Sweeps the subnet of [localIp] / [rawMask] (a missing or too-broad mask is taken as /24, and a
     * range over 512 hosts is cut to the first /24) and adds every Reach to [found]; [onNew] gets each
     * device [found] reports as new or changed. Returns when every host has been tried. Runs its probes
     * on the caller's dispatcher (use an IO one).
     */
    public suspend fun sweep(
        localIp: String?, rawMask: String?, found: ReachDeviceSet, onNew: (ReachDevice) -> Unit = {},
    ) {
        val effectiveMask = Ipv4Subnet.deriveEffectiveMask(localIp, rawMask)

        if (localIp == null || localIp == "0.0.0.0") {
            log.w("RECEIVER", "External receiver discovery HTTP sweep skipped: no valid local IP")
            return
        }

        val subnet = Ipv4Subnet.computeSubnet(localIp, effectiveMask)
        if (subnet == null) {
            log.w("RECEIVER", "External receiver discovery HTTP sweep skipped: subnet not computable mask=$effectiveMask")
            return
        }

        val (network, startHost, endHost) = Ipv4Subnet.capScanRangeIfTooLarge(subnet)
        GnssLog.d(DISCOVERY_TAG) { "HTTP sweep: $network (hosts $startHost..$endHost) mask=$effectiveMask" }

        coroutineScope {
            val sem = Semaphore(SUBNET_CONCURRENCY)
            val jobs = mutableListOf<Job>()
            // One summary line for the sweep instead of a line per host.
            val swept = java.util.concurrent.atomic.AtomicInteger(0)
            val hits = java.util.concurrent.atomic.AtomicInteger(0)
            for (i in startHost..endHost) {
                val ip = Ipv4Subnet.intToInet4StringBE(i)
                jobs += launch {
                    sem.acquire()
                    try {
                        val isReachDevice = validateHttpService(ip, 80)
                        swept.incrementAndGet()
                        if (isReachDevice) {
                            hits.incrementAndGet()
                            val (open5000, open9001) = probeBothPorts(ip)
                            val reachDevice = ReachDevice(
                                ip = ip,
                                hostname = null,
                                port5000Open = open5000,
                                port9001Open = open9001,
                                discoveryMethod = METHOD_HTTP_SWEEP
                            )
                            found.add(reachDevice)?.let(onNew)
                        }
                    } finally { sem.release() }
                }
            }
            jobs.joinAll()
            log.i("RECEIVER", "External receiver discovery HTTP sweep completed swept=${swept.get()} found=${hits.get()}")
        }
    }

    // ---- HTTP validation ----

    /** True if an HTTP GET / on [ip]:[port] answers with a Reach page (within 300 ms). */
    public suspend fun validateHttpService(ip: String, port: Int): Boolean = withTimeoutOrNull(HTTP_READ_TIMEOUT_MS.toLong()) {
        try {
            val socket = Socket()
            socket.soTimeout = HTTP_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(ip, port), HTTP_CONNECT_TIMEOUT_MS)

            socket.use {
                val output = it.getOutputStream()
                val input = BufferedReader(InputStreamReader(it.getInputStream()))

                // Send tiny HTTP GET request
                val request = "GET / HTTP/1.0\r\nHost: $ip\r\n\r\n"
                output.write(request.toByteArray())
                output.flush()

                // Read response and check for Reach device indicators
                val response = StringBuilder()
                var line: String?
                var lineCount = 0
                while (lineCount < 10) { // Limit lines read
                    // Note: readLine() is blocking; rely on socket.soTimeout for per-read bound.
                    line = input.readLine()
                    if (line == null) break
                    response.append(line).append('\n')
                    lineCount++

                    // Early detection of Reach device indicators
                    if (line.contains("reach", ignoreCase = true) ||
                        line.contains("emlid", ignoreCase = true) ||
                        line.contains("rs2", ignoreCase = true)) {
                        return@use true
                    }
                }

                // Check complete response for device indicators
                val content = response.toString().lowercase()
                content.contains("reach") || content.contains("emlid") || content.contains("rs2")
            }
        } catch (_: Exception) {
            // Expected for almost every host in the sweep (nothing listening): no per-host log.
            false
        }
    } ?: false

    // ---- Port probing ----

    /** Whether ports 5000 and 9001 on [ip] accept a connection, probed in parallel. */
    public suspend fun probeBothPorts(ip: String): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val p5000 = async { probePort(ip, 5000) }
        val p9001 = async { probePort(ip, 9001) }
        Pair(p5000.await(), p9001.await())
    }

    private suspend fun probePort(ip: String, port: Int): Boolean = withTimeoutOrNull(HTTP_READ_TIMEOUT_MS.toLong()) {
        try {
            Socket().use { s ->
                s.soTimeout = HTTP_CONNECT_TIMEOUT_MS
                s.connect(InetSocketAddress(ip, port), HTTP_CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    } ?: false
}

/**
 * Reach receivers found so far, one per IP, shared by every discovery phase (mDNS, HTTP sweep).
 * Thread-safe.
 */
public class ReachDeviceSet(private val log: LinkLog = GnssLinkLog()) {
    private val byIp = ConcurrentHashMap<String, ReachDevice>()

    /**
     * Adds [device], merged with an earlier find of the same IP (hostname and SSID kept if known, port
     * flags OR-ed, latest method). Returns the merged device when it is new or changed — logged once —
     * and null when nothing changed.
     */
    public fun add(device: ReachDevice): ReachDevice? {
        val existing = byIp[device.ip]
        val merged = if (existing == null) {
            device
        } else {
            // merge: prefer hostname if present; OR the port flags
            ReachDevice(
                ip = device.ip,
                hostname = existing.hostname ?: device.hostname,
                port5000Open = existing.port5000Open || device.port5000Open,
                port9001Open = existing.port9001Open || device.port9001Open,
                discoveryMethod = device.discoveryMethod, // keep latest method
                wifiSsid = existing.wifiSsid ?: device.wifiSsid
            )
        }
        val first = byIp.put(device.ip, merged) == null
        if (first || merged != existing) {
            GnssLog.d(DISCOVERY_TAG) { "Reach discovered/updated: $merged" }
            // File log: no hostname or serial.
            log.i("RECEIVER", "External receiver discovered ip=${merged.ip} method=${merged.discoveryMethod} " +
                "port5000=${merged.port5000Open} port9001=${merged.port9001Open}")
            return merged
        }
        return null
    }

    /** Everything found so far. */
    public fun all(): List<ReachDevice> = byIp.values.toList()
}
