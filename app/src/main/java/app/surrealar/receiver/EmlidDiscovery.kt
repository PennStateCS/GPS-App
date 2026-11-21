package app.surrealar.receiver

import android.content.Context
import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.util.ReachDiscoveryHelper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds Emlid Reach receivers with [ReachDiscoveryHelper] (mDNS via NsdManager, then an HTTP sweep of
 * the Wi-Fi subnet). Android-only, so it stays in the app.
 */
class EmlidDiscovery(private val context: Context) : ReceiverDiscovery {

    override suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> {
        val found = mutableListOf<app.surrealar.util.ReachDevice>()
        withTimeoutOrNull(timeoutMs) {
            ReachDiscoveryHelper.discoverReachDevices(context).collect { found += it }
        }
        return found.distinctBy { it.ip }.map { d ->
            DiscoveredReceiver(
                host = d.ip,
                label = d.hostname?.removeSuffix(".local")?.takeIf { it.isNotBlank() } ?: d.ip,
                // The scan can't tell an RS2+ from an RS4; the default Emlid profile is the safe guess.
                profileKey = ExternalReceiverProfile.REACH_RS2_PLUS.prefKey,
                nmeaPort = if (d.port9001Open) 9001 else null,
            )
        }
    }
}
