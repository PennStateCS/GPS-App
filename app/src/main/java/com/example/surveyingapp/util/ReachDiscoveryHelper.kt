package com.example.surveyingapp.util

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
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min

/**
 * Discover Emlid Reach RS2/RS2+ receivers on local Wi-Fi via:
 * 1) mDNS (NsdManager, service type "_http._tcp.")
 * 2) Subnet scan fallback probing ports 5000 (UI) and 9001 (TCP server)
 *
 * Permissions (Manifest):
 * - android.permission.ACCESS_WIFI_STATE
 * - android.permission.ACCESS_NETWORK_STATE
 * - android.permission.INTERNET
 * - android.permission.CHANGE_WIFI_MULTICAST_STATE (optional but recommended for mDNS)
 *
 * Notes:
 * - mDNS needs a WifiManager.MulticastLock on many devices.
 * - DhcpInfo fields are LITTLE-ENDIAN ints; convert before use.
 */

data class ReachDevice(
    val ip: String,
    val hostname: String?,    // e.g. "reach-ABCD"
    val port5000Open: Boolean,
    val port9001Open: Boolean
)

object ReachDiscoveryHelper {
    private const val TAG = "ReachDiscoveryHelper"
    private const val MDNS_SERVICE_TYPE = "_http._tcp."
    private const val CONNECT_TIMEOUT_MS = 600
    private const val PROBE_TIMEOUT_MS = 900
    private const val SUBNET_CONCURRENCY = 64

    /** Public API: Discover devices as a cold Flow that emits each device once. */
    fun discoverReachDevices(context: Context): Flow<ReachDevice> = callbackFlow {
        val discovered = ConcurrentHashMap<String, ReachDevice>()
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // Acquire multicast lock for more reliable mDNS (released in awaitClose)
        val multicastLock = try {
            wifiManager.createMulticastLock("reach-mdns-lock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
            null
        }

        // --- mDNS discovery ---
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Be tolerant: many routers/phones format serviceType with/without trailing dot
                val type = serviceInfo.serviceType.trimEnd('.')
                if (type != MDNS_SERVICE_TYPE.trimEnd('.')) return

                val name = serviceInfo.serviceName ?: return
                if (!name.contains("reach", ignoreCase = true)) {
                    // Not necessarily a Reach device; ignore
                    return
                }

                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val hostname = resolved.serviceName // usually like "reach-XXXX"
                        Log.d(TAG, "Resolved: $hostname @ $host (port ${resolved.port})")

                        ioScope.launch {
                            val (open5000, open9001) = probeBothPorts(host)
                            addOnceAndEmit(host, hostname, open5000, open9001, discovered) { trySend(it) }
                        }
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $serviceType ($errorCode)")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $serviceType ($errorCode)")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }

        // Start discovery (guard exceptions on some OEM stacks)
        try {
            nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw: ${e.message}")
        }

        // --- Subnet scan fallback ---
        ioScope.launch {
            val dhcp = wifiManager.dhcpInfo
            val localIp = dhcp?.ipAddress?.let { intToInet4StringLE(it) }
            val maskIp = dhcp?.netmask?.let { intToInet4StringLE(it) }

            if (localIp == null || localIp == "0.0.0.0") {
                Log.w(TAG, "No valid local IP; skipping subnet scan.")
                return@launch
            }

            val cidrMask = maskIp ?: "255.255.255.0" // fallback
            val subnet = computeSubnet(localIp, cidrMask)
            if (subnet == null) {
                Log.w(TAG, "Failed to compute subnet from $localIp / $cidrMask")
                return@launch
            }

            val (network, startHost, endHost) = subnet
            Log.d(TAG, "Subnet scan: $network (hosts $startHost..$endHost)")

            val sem = Semaphore(SUBNET_CONCURRENCY)
            val jobs = mutableListOf<Job>()

            for (i in startHost..endHost) {
                val ip = intToInet4StringBE(i)
                jobs += launch {
                    sem.acquire()
                    try {
                        val (open5000, open9001) = probeBothPorts(ip)
                        if (open5000 || open9001) {
                            addOnceAndEmit(ip, null, open5000, open9001, discovered) { trySend(it) }
                        }
                    } finally {
                        sem.release()
                    }
                }
            }
            jobs.joinAll()
        }

        awaitClose {
            ioScope.cancel()
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            try { multicastLock?.release() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    // ---- helpers ----

    private suspend fun probeBothPorts(ip: String): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val p5000 = async { probePort(ip, 5000) }
        val p9001 = async { probePort(ip, 9001) }
        Pair(p5000.await(), p9001.await())
    }

    private suspend fun probePort(ip: String, port: Int): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS.toLong()) {
        try {
            Socket().use { s ->
                s.soTimeout = CONNECT_TIMEOUT_MS
                s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    } ?: false

    private fun addOnceAndEmit(
        ip: String,
        hostname: String?,
        open5000: Boolean,
        open9001: Boolean,
        dedupe: ConcurrentHashMap<String, ReachDevice>,
        emit: (ReachDevice) -> Unit
    ) {
        val existing = dedupe[ip]
        val merged = if (existing == null) {
            ReachDevice(ip, hostname, open5000, open9001)
        } else {
            // merge: prefer hostname if present; OR the port flags
            ReachDevice(
                ip = ip,
                hostname = existing.hostname ?: hostname,
                port5000Open = existing.port5000Open || open5000,
                port9001Open = existing.port9001Open || open9001
            )
        }
        val first = dedupe.put(ip, merged) == null
        if (first || merged != existing) {
            Log.i(TAG, "Reach discovered/updated: $merged")
            emit(merged)
        }
    }

    /** Convert DhcpInfo little-endian int to IPv4 string. */
    private fun intToInet4StringLE(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /** Convert big-endian host int to IPv4 string (for enumerating addresses). */
    private fun intToInet4StringBE(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /** Compute network range from local IP + netmask strings. Returns (network, startHost, endHost) in BE-int form. */
    private fun computeSubnet(localIp: String, mask: String): Triple<String, Int, Int>? {
        val ipBytes = InetAddresses.ipToBytes(localIp) ?: return null
        val maskBytes = InetAddresses.ipToBytes(mask) ?: return null
        val ipInt = ByteBuffer.wrap(ipBytes).int
        val maskInt = ByteBuffer.wrap(maskBytes).int
        val networkInt = ipInt and maskInt
        val broadcastInt = networkInt or maskInt.inv()
        // avoid network/broadcast
        val start = max(networkInt + 1L, 0L).toInt()
        val end = min(broadcastInt - 1, 0xFFFFFFFFL.toInt())
        val networkStr = intToInet4StringBE(networkInt)
        return if (start <= end) Triple(networkStr, start, end) else null
    }

    // Tiny IPv4 parser
    private object InetAddresses {
        fun ipToBytes(ip: String): ByteArray? {
            return try {
                val addr = InetAddress.getByName(ip)
                val bytes = addr.address
                if (bytes.size == 4) bytes else null
            } catch (_: Exception) {
                null
            }
        }
    }
}