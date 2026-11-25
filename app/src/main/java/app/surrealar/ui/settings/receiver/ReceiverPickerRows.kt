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
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import java.util.Locale

/** Status shown on a picker row. */
sealed interface PickerRowStatus {
    data object Active : PickerRowStatus
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
        items += PickerItem.Tablet(if (tabletActive) PickerRowStatus.Active else null)

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
        if (s.selectedSource == LocationSourceType.EXTERNAL && saved != null && same(saved, t) &&
            s.activeProvider == ProviderChoice.EXTERNAL_TCP) return PickerRowStatus.Active
        if (scanHosts != null && norm(t.host) !in scanHosts) return PickerRowStatus.NotFoundInScan
        return null
    }

    private fun same(a: ReceiverTarget, b: ReceiverTarget) = a.port == b.port && norm(a.host) == norm(b.host)
    private fun norm(h: String) = h.trim().lowercase(Locale.ROOT)
}
