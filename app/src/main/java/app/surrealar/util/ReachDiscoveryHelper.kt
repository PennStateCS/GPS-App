package app.surrealar.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Discover Emlid Reach RS2/RS2+ receivers on local Wi-Fi via:
 * 1) mDNS (NsdManager, service type "_http._tcp.") for 3-5 seconds with HTTP validation
 * 2) Subnet scan fallback probing port 80 with 200-300ms timeouts and content validation
 *
 * Permissions (Manifest):
 * - android.permission.ACCESS_WIFI_STATE
 * - android.permission.ACCESS_NETWORK_STATE
 * - android.permission.INTERNET
 * - android.permission.CHANGE_WIFI_MULTICAST_STATE (optional but recommended for mDNS)
 */

object ReachDiscoveryHelper {
    private const val TAG = "ReachDiscoveryHelper"
    private const val MDNS_SERVICE_TYPE = "_http._tcp."
    private const val MDNS_TIMEOUT_MS = 5000L       // 5 seconds for mDNS discovery
    private const val HTTP_CONNECT_TIMEOUT_MS = 250  // 250ms for HTTP connections
    private const val HTTP_READ_TIMEOUT_MS = 300     // 300ms for HTTP reads
    private const val SUBNET_CONCURRENCY = 32

    /** Public API: Discover devices as a cold Flow that emits each device once. */
    fun discoverReachDevices(context: Context): Flow<ReachDevice> = callbackFlow {
        val discovered = ConcurrentHashMap<String, ReachDevice>()
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var mdnsFoundAny = false

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // --- Phase 1: mDNS discovery ---
        Log.d(TAG, "Starting Phase 1: mDNS discovery...")
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val multicastLock = try {
            wifiManager.createMulticastLock("reach-mdns-lock").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}"); null
        }

        val mdnsJob = ioScope.launch {
            val mdnsCompleted = CompletableDeferred<Boolean>()
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "mDNS discovery started: $serviceType") }
                override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "mDNS discovery stopped"); mdnsCompleted.complete(mdnsFoundAny) }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val type = serviceInfo.serviceType.trimEnd('.')
                    if (type != MDNS_SERVICE_TYPE.trimEnd('.')) return
                    val name = serviceInfo.serviceName ?: return
                    Log.d(TAG, "Found mDNS service: $name")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            val hostname = resolved.serviceName
                            val port = resolved.port.takeIf { it > 0 } ?: 80
                            Log.d(TAG, "Resolved: $hostname @ $host:$port")
                            ioScope.launch {
                                val isReachDevice = validateHttpService(host, port)
                                if (isReachDevice) {
                                    mdnsFoundAny = true
                                    val (open5000, open9001) = probeBothPorts(host)
                                    val reachDevice = ReachDevice(
                                        ip = host,
                                        hostname = hostname,
                                        port5000Open = open5000,
                                        port9001Open = open9001,
                                        discoveryMethod = "mdns"
                                    )
                                    addOnceAndEmit(reachDevice, discovered) { trySend(it) }
                                }
                            }
                        }
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode") }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) { Log.d(TAG, "Service lost: ${serviceInfo.serviceName}") }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e(TAG, "Start discovery failed: $serviceType ($errorCode)"); mdnsCompleted.complete(false) }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e(TAG, "Stop discovery failed: $serviceType ($errorCode)"); mdnsCompleted.complete(mdnsFoundAny) }
            }
            try {
                nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                withTimeoutOrNull(MDNS_TIMEOUT_MS) { mdnsCompleted.await() }
                try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) { Log.w(TAG, "Error stopping mDNS discovery: ${e.message}") }
            } catch (e: Exception) { Log.w(TAG, "mDNS discovery failed: ${e.message}"); mdnsCompleted.complete(false) }
        }
        mdnsJob.join()
        try { multicastLock?.release() } catch (_: Exception) {}

        // --- Phase 2: HTTP sweep fallback (if no devices found via mDNS) ---
        if (!mdnsFoundAny) {
            Log.d(TAG, "Starting Phase 2: HTTP sweep...")
            ioScope.launch {
                val dhcp = wifiManager.dhcpInfo
                val localIp = dhcp?.ipAddress?.let { intToInet4StringLE(it) }
                val rawMask = dhcp?.netmask?.let { intToInet4StringLE(it) }
                val effectiveMask = deriveEffectiveMask(localIp, rawMask)

                if (localIp == null || localIp == "0.0.0.0") {
                    Log.w(TAG, "No valid local IP; skipping subnet scan.")
                    return@launch
                }

                val subnet = computeSubnet(localIp, effectiveMask)
                if (subnet == null) {
                    Log.w(TAG, "Failed to compute subnet from $localIp / $effectiveMask")
                    return@launch
                }

                val (network, startHost, endHost) = capScanRangeIfTooLarge(subnet)
                Log.d(TAG, "HTTP sweep: $network (hosts $startHost..$endHost) mask=$effectiveMask")

                val sem = Semaphore(SUBNET_CONCURRENCY)
                val jobs = mutableListOf<Job>()
                for (i in startHost..endHost) {
                    val ip = intToInet4StringBE(i)
                    jobs += launch {
                        sem.acquire()
                        try {
                            val isReachDevice = validateHttpService(ip, 80)
                            if (isReachDevice) {
                                val (open5000, open9001) = probeBothPorts(ip)
                                val reachDevice = ReachDevice(
                                    ip = ip,
                                    hostname = null,
                                    port5000Open = open5000,
                                    port9001Open = open9001,
                                    discoveryMethod = "http_sweep"
                                )
                                addOnceAndEmit(reachDevice, discovered) { trySend(it) }
                            }
                        } finally { sem.release() }
                    }
                }
                jobs.joinAll()
            }.join()
        }

        awaitClose { ioScope.cancel() }
    }.flowOn(Dispatchers.IO)

    // ---- HTTP validation ----

    private suspend fun validateHttpService(ip: String, port: Int): Boolean = withTimeoutOrNull(HTTP_READ_TIMEOUT_MS.toLong()) {
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
        } catch (e: Exception) {
            Log.d(TAG, "HTTP validation failed for $ip:$port - ${e.message}")
            false
        }
    } ?: false

    // ---- Port probing ----

    private suspend fun probeBothPorts(ip: String): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
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

    // ---- Helpers ----

    private fun addOnceAndEmit(
        device: ReachDevice,
        dedupe: ConcurrentHashMap<String, ReachDevice>,
        emit: (ReachDevice) -> Unit
    ) {
        val existing = dedupe[device.ip]
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
        val first = dedupe.put(device.ip, merged) == null
        if (first || merged != existing) {
            Log.i(TAG, "Reach discovered/updated: $merged")
            emit(merged)
        }
    }

    /** Convert DhcpInfo little-endian int to IPv4 string. */
    private fun intToInet4StringLE(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /** Convert big-endian host int to IPv4 string (for enumerating addresses). */
    private fun intToInet4StringBE(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Compute network range from local IP + netmask strings.
     * Returns (networkString, startHostIntBE, endHostIntBE).
     * Uses unsigned 32-bit math to avoid sign issues.
     */
    private fun computeSubnet(localIp: String, mask: String): Triple<String, Int, Int>? {
        val ipU = ipv4ToU32(localIp) ?: return null
        val maskU = ipv4ToU32(mask) ?: return null
        if (maskU == 0L) return null

        val networkU = ipU and maskU
        val broadcastU = networkU or (maskU.inv() and 0xFFFFFFFFL)

        // If there are < 2 host addresses, just return the network as both
        val hasHosts = (broadcastU - networkU) >= 2
        val startU = if (hasHosts) networkU + 1 else networkU
        val endU = if (hasHosts) broadcastU - 1 else broadcastU

        val networkStr = u32ToIpv4(networkU)
        val startInt = u32ToInt(startU)
        val endInt = u32ToInt(endU)

        return if (startInt <= endInt) Triple(networkStr, startInt, endInt) else Triple(networkStr, endInt, startInt)
    }

    // ---- tiny IPv4 helpers (unsigned 32-bit) ----

    private fun ipv4ToU32(ip: String): Long? = try {
        val b = InetAddress.getByName(ip).address
        if (b.size != 4) null else
            ((b[0].toLong() and 0xFF) shl 24) or
                    ((b[1].toLong() and 0xFF) shl 16) or
                    ((b[2].toLong() and 0xFF) shl 8) or
                    (b[3].toLong() and 0xFF)
    } catch (_: Exception) { null }

    private fun u32ToIpv4(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private fun u32ToInt(v: Long): Int = (v and 0xFFFFFFFFL).toInt()

    // New: ensure reasonable mask if DHCP gives 0.0.0.0 or extremely broad (e.g. /8)
    private fun deriveEffectiveMask(ip: String?, rawMask: String?): String {
        if (ip == null) return "255.255.255.0"
        val mask = when {
            rawMask == null || rawMask == "0.0.0.0" -> "255.255.255.0"
            else -> rawMask
        }
        val bits = ipv4ToU32(mask)?.let { java.lang.Long.bitCount(it) } ?: 24
        return if (bits < 20) "255.255.255.0" else mask
    }

    private fun capScanRangeIfTooLarge(subnet: Triple<String, Int, Int>): Triple<String, Int, Int> {
        val (network, start, end) = subnet
        val hostCount = (end - start + 1).coerceAtLeast(0)
        // If more than 512 hosts, cap to first /24 slice
        return if (hostCount > 512) {
            val startIp = intToInet4StringBE(start)
            val octets = startIp.split('.')
            if (octets.size == 4) {
                val base = "${octets[0]}.${octets[1]}.${octets[2]}."
                val newStart = ipv4ToU32(base + "1")?.let { u32ToInt(it) } ?: start
                val newEnd = ipv4ToU32(base + "254")?.let { u32ToInt(it) } ?: end
                Triple("${octets[0]}.${octets[1]}.${octets[2]}.0", newStart, newEnd)
            } else subnet
        } else subnet
    }
}
