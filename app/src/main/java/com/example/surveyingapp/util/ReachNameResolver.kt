package com.example.surveyingapp.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolve the Reach (Emlid) receiver "Name" (HTTP mDNS service name) for a known IP address.
 * Returns null if not found within timeout.
 */
object ReachNameResolver {
    private const val TAG = "ReachNameResolver"
    private const val SERVICE_TYPE = "_http._tcp."

    suspend fun resolveReachName(
        context: Context,
        targetIp: String,
        timeoutMs: Long = 5000L
    ): String? = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val lock = try {
            wifi.createMulticastLock("reach-mdns").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock failed: ${e.message}")
            null
        }

        val result = CompletableDeferred<String?>()
        var stopped = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) { if (!result.isCompleted) result.complete(null) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (!result.isCompleted) result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { if (!result.isCompleted) result.complete(null) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType.trimEnd('.')
                if (type != SERVICE_TYPE.trimEnd('.')) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        if (host == targetIp) {
                            if (!result.isCompleted) result.complete(resolved.serviceName)
                            stop()
                        }
                    }
                })
            }
            private fun stop() {
                if (stopped) return
                stopped = true
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: Exception) {
            Log.w(TAG, "NSD start failed: ${e.message}")
            null
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }
    }
}
