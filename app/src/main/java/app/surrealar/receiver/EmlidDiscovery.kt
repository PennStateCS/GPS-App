package app.surrealar.receiver

import android.content.Context
import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import io.github.pennstatecs.geokit.receiver.emlid.ReachDevice
import app.surrealar.util.ReachDiscoveryHelper
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds Emlid Reach receivers with [ReachDiscoveryHelper]: mDNS via NsdManager (Android, so it stays in
 * the app), then geokit-receiver-emlid's HTTP sweep of the Wi-Fi subnet.
 *
 * A scan cannot tell an RS2+ from an RS4 or RS4 Pro, so results carry no profile (`profileKey = null`): the model is
 * known only after connecting, when the receiver reports it (Phase 5 identification). Show results as
 * [UNCONFIRMED_MODEL_LABEL].
 */
class EmlidDiscovery(private val context: Context) : ReceiverDiscovery {

    override suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> {
        val found = mutableListOf<ReachDevice>()
        withTimeoutOrNull(timeoutMs) {
            ReachDiscoveryHelper.discoverReachDevices(context).collect { found += it }
        }
        return found.distinctBy { it.ip }.map { it.toDiscoveredReceiver(profileKey = null) }
    }

    companion object {
        /** How a discovered Reach is described before it has reported its model. */
        const val UNCONFIRMED_MODEL_LABEL = "Emlid Reach (model confirmed after connecting)"
    }
}
