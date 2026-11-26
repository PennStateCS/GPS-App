package app.surrealar.ui.settings.receiver

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.NetworkScanState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.receiver.selection.ScannedReceiver
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.RecentReceiver
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import java.util.Locale

/**
 * Status shown on a picker row. The saved receiver's row says what its link is actually doing (Connected / Connecting /
 * Reconnecting / Unavailable) or only "Selected" when nothing is running for it: matching the saved target alone never
 * reads as connected.
 */
sealed interface PickerRowStatus {
    /** Tablet GNSS is the selected source. */
    data object InUse : PickerRowStatus
    /** The saved receiver: the external adapter is active and its link is up. */
    data object Connected : PickerRowStatus
    data object Connecting : PickerRowStatus
    data object Reconnecting : PickerRowStatus
    /** The saved receiver: the adapter is active but the link is down (disconnected / error). */
    data object Unavailable : PickerRowStatus
    /** The saved receiver, but no connection is running for it (e.g. before the first connect). */
    data object Selected : PickerRowStatus
    data object Testing : PickerRowStatus
    data object NotFoundInScan : PickerRowStatus
    data class Failed(val error: ConnectError) : PickerRowStatus
}

/** One entry of the receiver picker sheet, in display order. */
sealed interface PickerItem {
    enum class Section { TABLET, NETWORK, RECENT }
    data class Header(val section: Section) : PickerItem
    data class Tablet(val status: PickerRowStatus?) : PickerItem
    /** Scan progress / last scan / scan button. [autoScan] = the profile is an Emlid one (scan on open). */
    data class Scan(val scan: NetworkScanState, val autoScan: Boolean) : PickerItem
    data class Scanned(val receiver: ScannedReceiver, val status: PickerRowStatus?) : PickerItem
    data class Recent(val receiver: RecentReceiver, val profile: ExternalReceiverProfile?, val status: PickerRowStatus?) : PickerItem
    data object RecentEmpty : PickerItem
    data object Manual : PickerItem
}

/** Builds the picker's sections from the selection state (pure, unit-tested). */
object ReceiverPickerRows {

    /** Scanning (Emlid discovery: HTTP sweep + mDNS) starts on open only for an Emlid profile. */
    fun autoScan(s: ReceiverSelectionState): Boolean = s.profile.vendor == ReceiverVendor.EMLID

    fun build(s: ReceiverSelectionState): List<PickerItem> {
        val scanHosts = (s.scan as? NetworkScanState.Done)?.results?.map { norm(it.host) }?.toSet()
        val items = mutableListOf<PickerItem>()
        items += PickerItem.Header(PickerItem.Section.TABLET)
        val tabletActive = s.selectedSource == LocationSourceType.INTERNAL && !s.busy
        items += PickerItem.Tablet(if (tabletActive) PickerRowStatus.InUse else null)

        items += PickerItem.Header(PickerItem.Section.NETWORK)
        items += PickerItem.Scan(s.scan, autoScan(s))
        (s.scan as? NetworkScanState.Done)?.results?.forEach { r ->
            items += PickerItem.Scanned(r, status(s, ReceiverTarget(r.host, r.port), null))
        }

        items += PickerItem.Header(PickerItem.Section.RECENT)
        if (s.recent.isEmpty()) items += PickerItem.RecentEmpty
        s.recent.forEach { r ->
            val profile = ExternalReceiverProfile.entries.firstOrNull { it.prefKey == r.profileKey }
            items += PickerItem.Recent(r, profile, status(s, ReceiverTarget(r.host, r.port), scanHosts))
        }
        items += PickerItem.Manual
        return items
    }

    /** Row status for [t]; [scanHosts] (recent rows only) marks receivers the last scan did not find. */
    fun status(s: ReceiverSelectionState, t: ReceiverTarget, scanHosts: Set<String>?): PickerRowStatus? {
        when (val a = s.attempt) {
            is ConnectionAttempt.Testing -> if (same(a.target, t)) return PickerRowStatus.Testing
            is ConnectionAttempt.Switching -> if (same(a.target, t)) return PickerRowStatus.Testing
            is ConnectionAttempt.Failed -> if (same(a.target, t)) return PickerRowStatus.Failed(a.error)
            else -> Unit
        }
        val saved = s.savedTarget
        if (s.selectedSource == LocationSourceType.EXTERNAL && saved != null && same(saved, t)) {
            if (s.activeProvider != ProviderChoice.EXTERNAL_TCP) return PickerRowStatus.Selected
            return when (s.link) {
                ConnectionState.CONNECTED -> PickerRowStatus.Connected
                ConnectionState.CONNECTING -> PickerRowStatus.Connecting
                ConnectionState.RECONNECTING -> PickerRowStatus.Reconnecting
                else -> PickerRowStatus.Unavailable
            }
        }
        if (scanHosts != null && norm(t.host) !in scanHosts) return PickerRowStatus.NotFoundInScan
        return null
    }

    private fun same(a: ReceiverTarget, b: ReceiverTarget) = a.port == b.port && norm(a.host) == norm(b.host)
    private fun norm(h: String) = h.trim().lowercase(Locale.ROOT)
}
