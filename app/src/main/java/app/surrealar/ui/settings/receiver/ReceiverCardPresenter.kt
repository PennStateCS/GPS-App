package app.surrealar.ui.settings.receiver

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ProfileMismatch
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.settings.model.ProfileOrigin
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities

/** Where a value on the receiver screens comes from (shown as a tag next to it). */
enum class ValueOrigin {
    /** The connected receiver reported it (Emlid device info, verified for the saved host). */
    REPORTED,
    /** The user chose or entered it. */
    SET_BY_USER,
    /** The app's default (nobody chose it). */
    DEFAULT,
    /** The profile was set from a receiver report earlier, but the receiver is not confirming it now. */
    EARLIER_REPORT,
    /** Not known. */
    UNKNOWN,
}

/** The connection state chip on the receiver card. */
sealed interface CardConnection {
    /** Tablet (device) GNSS is the source. */
    data object DeviceGnss : CardConnection
    /** Test before switch: probing a receiver (nothing saved yet). */
    data object Testing : CardConnection
    data object Connecting : CardConnection
    data object Connected : CardConnection
    data object Reconnecting : CardConnection
    /** External selected but nothing is streaming (and no failed test for the saved receiver). */
    data object Unavailable : CardConnection
    /** The test for the saved receiver failed. */
    data class Failed(val error: ConnectError) : CardConnection
}

/** Fix quality shown on the card. */
enum class CardFix { NO_FIX, SINGLE, DGPS, FLOAT, FIX, DEAD_RECKONING }

/**
 * Everything the receiver card shows, from the selection core plus live GNSS. Pure: strings are resolved by
 * [ReceiverCardBinder].
 */
data class ReceiverCardModel(
    val external: Boolean,
    /** Receiver display name (reported name, saved name or host); null for tablet GNSS or nothing saved. */
    val receiverName: String?,
    /** host:port of the saved receiver (null when none). */
    val target: ReceiverTarget?,
    /** Model/profile label and where it comes from (null for tablet GNSS). */
    val modelLabel: String?,
    val modelOrigin: ValueOrigin?,
    val connection: CardConnection,
    /** Fix quality and satellites; null when no live position from the selected source. */
    val fix: CardFix?,
    val satsUsed: Int?,
    val satsVisible: Int?,
    /** Battery percent, only when the receiver reports a battery. */
    val batteryPercent: Int?,
    val batteryCharging: Boolean,
    val mismatch: ProfileMismatch?,
    /** A failed switch to ANOTHER receiver (the current one keeps running): shown as an inline note. */
    val failedSwitch: ConnectionAttempt.Failed?,
    /** Retry is offered when the saved receiver is not streaming. */
    val canRetry: Boolean,
    val busy: Boolean,
)

/** Builds [ReceiverCardModel] (pure, unit-tested). */
object ReceiverCardPresenter {

    fun present(
        s: ReceiverSelectionState,
        fix: Fix?,
        sky: SkySnapshot?,
        battery: ReceiverBattery?,
        capabilities: ReceiverCapabilities,
    ): ReceiverCardModel {
        val external = s.selectedSource == LocationSourceType.EXTERNAL
        val connection = connection(s, external)
        val streaming = connection == CardConnection.DeviceGnss || connection == CardConnection.Connected ||
            connection == CardConnection.Reconnecting
        // The fix shown must come from the selected source (a stale device-GNSS fix never shows as the receiver's).
        val liveFix = fix?.takeIf { f ->
            streaming && validLatLon(f) && (if (external) f.provider != Provider.INTERNAL else f.provider == Provider.INTERNAL)
        }
        val used = sky?.takeIf { liveFix != null && it.totalVisible > 0 }?.totalUsed ?: liveFix?.satsUsed
        val visible = sky?.takeIf { liveFix != null && it.totalVisible > 0 }?.totalVisible ?: liveFix?.satsVisible ?: used
        val identity = s.identity
        val (modelLabel, modelOrigin) = when {
            !external -> null to null
            identity != null && identity.modelVerified ->
                (identity.recognizedProfile?.label ?: identity.reportedModel ?: s.profile.label) to ValueOrigin.REPORTED
            else -> s.profile.label to when (s.profileOrigin) {
                ProfileOrigin.USER -> ValueOrigin.SET_BY_USER
                ProfileOrigin.DEFAULT -> ValueOrigin.DEFAULT
                ProfileOrigin.RECEIVER -> ValueOrigin.EARLIER_REPORT
            }
        }
        val name = if (!external) null else
            identity?.name?.takeIf { it.isNotBlank() } ?: s.savedName ?: s.savedTarget?.host
        val failed = s.attempt as? ConnectionAttempt.Failed
        val showBattery = external && capabilities.hasBattery && battery != null &&
            s.activeProvider == ProviderChoice.EXTERNAL_TCP
        return ReceiverCardModel(
            external = external,
            receiverName = name,
            target = if (external) s.savedTarget else null,
            modelLabel = modelLabel,
            modelOrigin = modelOrigin,
            connection = connection,
            fix = liveFix?.let { fixOf(it.rtkStatus) },
            satsUsed = if (liveFix != null) used else null,
            satsVisible = if (liveFix != null) visible else null,
            batteryPercent = if (showBattery) battery!!.percent else null,
            batteryCharging = showBattery && battery!!.isCharging,
            mismatch = if (external) s.mismatch else null,
            failedSwitch = failed?.takeIf { connection !is CardConnection.Failed },
            canRetry = external && s.savedTarget != null && !s.busy &&
                (connection == CardConnection.Unavailable || connection is CardConnection.Failed),
            busy = s.busy,
        )
    }

    fun connection(s: ReceiverSelectionState, external: Boolean = s.selectedSource == LocationSourceType.EXTERNAL): CardConnection {
        when (s.attempt) {
            is ConnectionAttempt.Testing -> return CardConnection.Testing
            is ConnectionAttempt.Switching -> return CardConnection.Connecting
            else -> Unit
        }
        if (!external) return CardConnection.DeviceGnss
        val failed = s.attempt as? ConnectionAttempt.Failed
        if (s.activeProvider != ProviderChoice.EXTERNAL_TCP) {
            return if (failed != null && (s.savedTarget == null || failed.target == s.savedTarget)) CardConnection.Failed(failed.error)
            else CardConnection.Unavailable
        }
        return when (s.link) {
            ConnectionState.CONNECTED -> CardConnection.Connected
            ConnectionState.CONNECTING -> CardConnection.Connecting
            ConnectionState.RECONNECTING -> CardConnection.Reconnecting
            else -> CardConnection.Unavailable
        }
    }

    fun fixOf(status: RtkStatus): CardFix = when (status) {
        RtkStatus.FIX -> CardFix.FIX
        RtkStatus.FLOAT -> CardFix.FLOAT
        RtkStatus.DGPS -> CardFix.DGPS
        RtkStatus.SINGLE -> CardFix.SINGLE
        RtkStatus.DEAD_RECKONING -> CardFix.DEAD_RECKONING
        else -> CardFix.NO_FIX
    }

    /** Fix liveness = a valid lat/lon (never wall-clock age against the GNSS timestamp). */
    private fun validLatLon(f: Fix): Boolean {
        val lat = f.latDeg
        val lon = f.lonDeg
        return !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
    }
}
