package app.surrealar.receiver

import android.content.Context
import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import io.github.pennstatecs.geokit.receiver.emlid.ReachDevice
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.util.ReachDiscoveryHelper
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds Emlid Reach receivers with [ReachDiscoveryHelper]: mDNS via NsdManager (Android, so it stays in
 * the app), then geokit-receiver-emlid's HTTP sweep of the Wi-Fi subnet.
 */
class EmlidDiscovery(private val context: Context) : ReceiverDiscovery {

    override suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> {
        val found = mutableListOf<ReachDevice>()
        withTimeoutOrNull(timeoutMs) {
            ReachDiscoveryHelper.discoverReachDevices(context).collect { found += it }
        }
        // The scan can't tell an RS2+ from an RS4; the default Emlid profile is the safe guess.
        return found.distinctBy { it.ip }.map { it.toDiscoveredReceiver(ExternalReceiverProfile.REACH_RS2_PLUS.prefKey) }
    }
}
