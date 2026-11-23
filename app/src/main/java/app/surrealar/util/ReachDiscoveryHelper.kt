package app.surrealar.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import app.surrealar.gnss.external.DiagnosticsLinkLog
import io.github.pennstatecs.geokit.receiver.emlid.Ipv4Subnet
import io.github.pennstatecs.geokit.receiver.emlid.ReachDevice
import io.github.pennstatecs.geokit.receiver.emlid.ReachDeviceSet
import io.github.pennstatecs.geokit.receiver.emlid.ReachNetworkScan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose

/**
 * Discover Emlid Reach RS2/RS2+ receivers on local Wi-Fi via:
 * 1) mDNS (NsdManager, service type "_http._tcp.") for 3-5 seconds with HTTP validation
 * 2) Subnet scan fallback probing port 80 with 200-300ms timeouts and content validation
 *
 * The HTTP validation, port probing, subnet sweep and de-duplication are geokit-receiver-emlid's
 * [ReachNetworkScan] / [ReachDeviceSet]; only mDNS and the Wi-Fi address (Android APIs) are here.
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

    private val scan = ReachNetworkScan(log = DiagnosticsLinkLog)

    /** Public API: Discover devices as a cold Flow that emits each device once. */
    fun discoverReachDevices(context: Context): Flow<ReachDevice> = callbackFlow {
        val discovered = ReachDeviceSet(DiagnosticsLinkLog)
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var mdnsFoundAny = false

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // --- Phase 1: mDNS discovery ---
        DevLog.d(TAG) { "Starting Phase 1: mDNS discovery..." }
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val multicastLock = try {
            wifiManager.createMulticastLock("reach-mdns-lock").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            DiagnosticsLogger.w("RECEIVER", "External receiver discovery multicast lock failed: mDNS may miss devices", e); null
        }

        try {
            val mdnsJob = ioScope.launch {
                val mdnsCompleted = CompletableDeferred<Boolean>()
                val discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) { DevLog.d(TAG) { "mDNS discovery started: $serviceType" } }
                    override fun onDiscoveryStopped(serviceType: String) { DevLog.d(TAG) { "mDNS discovery stopped" }; mdnsCompleted.complete(mdnsFoundAny) }
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val type = serviceInfo.serviceType.trimEnd('.')
                        if (type != MDNS_SERVICE_TYPE.trimEnd('.')) return
                        val name = serviceInfo.serviceName ?: return
                        DevLog.d(TAG) { "Found mDNS service: $name" }
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress ?: return
                                val hostname = resolved.serviceName
                                val port = resolved.port.takeIf { it > 0 } ?: 80
                                DevLog.d(TAG) { "Resolved: $hostname @ $host:$port" }
                                ioScope.launch {
                                    val isReachDevice = scan.validateHttpService(host, port)
                                    if (isReachDevice) {
                                        mdnsFoundAny = true
                                        val (open5000, open9001) = scan.probeBothPorts(host)
                                        val reachDevice = ReachDevice(
                                            ip = host,
                                            hostname = hostname,
                                            port5000Open = open5000,
                                            port9001Open = open9001,
                                            discoveryMethod = "mdns"
                                        )
                                        discovered.add(reachDevice)?.let { trySend(it) }
                                    }
                                }
                            }
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { DevLog.d(TAG) { "Resolve failed for ${serviceInfo.serviceName}: $errorCode" } }
                        })
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) { DevLog.d(TAG) { "Service lost: ${serviceInfo.serviceName}" } }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { DiagnosticsLogger.w("RECEIVER", "External receiver discovery mDNS start failed: falling back to HTTP sweep errorCode=$errorCode"); mdnsCompleted.complete(false) }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { DevLog.d(TAG) { "Stop discovery failed: $serviceType ($errorCode)" }; mdnsCompleted.complete(mdnsFoundAny) }
                }
                try {
                    nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    withTimeoutOrNull(MDNS_TIMEOUT_MS) { mdnsCompleted.await() }
                    try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) { DevLog.d(TAG) { "Error stopping mDNS discovery: ${e.message}" } }
                } catch (e: Exception) { DiagnosticsLogger.w("RECEIVER", "External receiver discovery mDNS failed: falling back to HTTP sweep", e); mdnsCompleted.complete(false) }
            }
            mdnsJob.join()
            try { multicastLock?.release() } catch (_: Exception) {}

            // --- Phase 2: HTTP sweep fallback (if no devices found via mDNS) ---
            if (!mdnsFoundAny) {
                DevLog.d(TAG) { "Starting Phase 2: HTTP sweep..." }
                ioScope.launch {
                    val dhcp = wifiManager.dhcpInfo
                    val localIp = dhcp?.ipAddress?.let { Ipv4Subnet.littleEndianIntToString(it) }
                    val rawMask = dhcp?.netmask?.let { Ipv4Subnet.littleEndianIntToString(it) }
                    scan.sweep(localIp, rawMask, discovered) { trySend(it) }
                }.join()
            }

            awaitClose { }
        } finally {
            // Also runs when the collector goes away mid-scan (user leaves Settings during mDNS): the
            // multicast lock and the scan coroutines used to leak until the process died.
            try { multicastLock?.release() } catch (_: Exception) {}
            ioScope.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
