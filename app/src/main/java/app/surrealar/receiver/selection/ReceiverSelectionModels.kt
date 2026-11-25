package app.surrealar.receiver.selection

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.RecentReceiver
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel

/** A receiver endpoint: where the NMEA TCP stream is served. */
data class ReceiverTarget(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * What the user asked to connect to. [profile] = the receiver type the user picked for this connection (manual
 * dialog), or null to keep the stored profile. [name] = a display name to store with the host (blank = none; the
 * receiver's own name may replace it later).
 */
data class ConnectRequest(
    val host: String,
    val port: Int,
    val profile: ExternalReceiverProfile? = null,
    val name: String = "",
)

/** The synchronous answer to [ReceiverConnectionController.connect]. */
enum class ConnectStart {
    /** A test-before-switch attempt started; follow [ReceiverSelectionState.attempt]. */
    STARTED,
    /** The same target is already being tested; nothing new started. */
    DUPLICATE_IGNORED,
    /** That receiver is already the active, streaming source; nothing to test or switch. */
    ALREADY_ACTIVE,
    /**
     * A capture is running and this would change the receiver (or profile). Ask the user ("Switching receivers stops
     * the current capture") and call again with `confirmCaptureStop = true`. Nothing started.
     */
    NEEDS_CAPTURE_CONFIRMATION,
    /** Blank host or a port outside 1..65535. */
    INVALID,
    /** [ReceiverConnectionController.reconnectSaved]: no External TCP receiver is saved. */
    NOTHING_SAVED,
}

/** What the TCP NMEA sniff found on the port. */
enum class TcpProbeResult { CONNECT_FAILED, CONNECTED_NO_DATA, RECEIVING_NMEA, RECEIVING_RTCM_OR_BIN }

/** Why a connection test failed. Nothing was saved and the active source was not changed. */
sealed class ConnectError(val message: String) {
    /** Neither the NMEA port nor (Emlid only) the receiver's HTTP API answered. */
    data class Unreachable(val port: Int) :
        ConnectError("Receiver not reachable on port $port. Check the IP address, the port and the Wi-Fi network.")
    /** The Emlid HTTP API answered but the NMEA port refused the connection. */
    data class HttpOnly(val port: Int) :
        ConnectError("Receiver found but NMEA stream not available on port $port.")
    /** The port accepted the connection but sent nothing within the test window. */
    data class NoData(val port: Int) :
        ConnectError("Connected to port $port but no NMEA arrived. Check that position streaming outputs NMEA on this port.")
    /** The port sends data that is not NMEA (RTCM or binary). */
    data class NotNmea(val port: Int) :
        ConnectError("Port $port sends data that is not NMEA (RTCM or binary). Choose the NMEA output port.")
    /** Saving the settings or another unexpected step failed. */
    data class Unexpected(val detail: String) : ConnectError("Couldn't connect: $detail")
}

/** The connection-test lifecycle (test-before-switch). */
sealed interface ConnectionAttempt {
    data object Idle : ConnectionAttempt
    /** Probing [target] (TCP NMEA sniff; Emlid also the HTTP API). Nothing is saved yet. */
    data class Testing(val id: Int, val target: ReceiverTarget, val profile: ExternalReceiverProfile?) : ConnectionAttempt
    /** The probe received NMEA: saving the settings and activating the source. */
    data class Switching(val id: Int, val target: ReceiverTarget) : ConnectionAttempt
    /** [target] is now the saved and active receiver. */
    data class Succeeded(val id: Int, val target: ReceiverTarget, val httpReachable: Boolean?) : ConnectionAttempt
    /** The test failed; settings and the active source are unchanged. */
    data class Failed(val id: Int, val target: ReceiverTarget, val error: ConnectError) : ConnectionAttempt
}

/**
 * What the connected receiver itself reported (Emlid device info) for [host], the saved host of the active external
 * source. [model] is set only when [reportedModel] is a recognised model ([EmlidReachModel.fromReportedModel]);
 * [recognizedProfile] is the app profile for it (null for models without one). Nothing here says which receiver
 * features are enabled.
 */
data class ReceiverIdentity(
    val host: String,
    val reportedModel: String?,
    val model: EmlidReachModel?,
    val recognizedProfile: ExternalReceiverProfile?,
    val name: String?,
    val serial: String?,
    val firmware: String?,
) {
    /** True when the receiver explicitly reported a model the app recognises. */
    val modelVerified: Boolean get() = model != null
}

/** Why an identified profile was not applied automatically. */
enum class MismatchReason {
    /** The stored profile was chosen by the user (or by an install from before Phase 5). */
    PROFILE_SET_BY_USER,
    /** A capture is running: a profile change would stop it. */
    CAPTURE_ACTIVE,
}

/** "Receiver reports [reportedModel]; profile is [currentProfile]" (banner: Use [reportedProfile] / Keep). */
data class ProfileMismatch(
    val host: String,
    val reportedModel: String,
    val reportedProfile: ExternalReceiverProfile,
    val currentProfile: ExternalReceiverProfile,
    val reason: MismatchReason,
)

/** One receiver found by a network scan (Emlid discovery; the model is only known after connecting). */
data class ScannedReceiver(val host: String, val port: Int, val label: String, val description: String)

sealed interface NetworkScanState {
    data object Idle : NetworkScanState
    data object Scanning : NetworkScanState
    data class Done(val results: List<ScannedReceiver>, val atMs: Long) : NetworkScanState
    /** No discovery available, or it failed. */
    data class Failed(val message: String) : NetworkScanState
}

/**
 * Everything receiver selection shows, from one place.
 *
 * - Selected source / saved target / profile + origin: what is persisted.
 * - [activeProvider] / [link]: what is actually streaming (the active provider can lag the selected source).
 * - [attempt]: the current test-before-switch attempt.
 * - [identity] / [mismatch]: what the receiver reported and whether it disagrees with the profile.
 */
data class ReceiverSelectionState(
    val selectedSource: LocationSourceType = LocationSourceType.INTERNAL,
    val savedTarget: ReceiverTarget? = null,
    val savedName: String? = null,
    val profile: ExternalReceiverProfile = ExternalReceiverProfile.DEFAULT,
    val profileOrigin: ProfileOrigin = ProfileOrigin.USER,
    val activeProvider: ProviderChoice = ProviderChoice.INTERNAL,
    val link: ReconnectingNmeaAdapter.ConnectionState? = null,
    val attempt: ConnectionAttempt = ConnectionAttempt.Idle,
    val identity: ReceiverIdentity? = null,
    val mismatch: ProfileMismatch? = null,
    val captureActive: Boolean = false,
    val recent: List<RecentReceiver> = emptyList(),
    val scan: NetworkScanState = NetworkScanState.Idle,
) {
    /** True while a test or switch is in progress. */
    val busy: Boolean get() = attempt is ConnectionAttempt.Testing || attempt is ConnectionAttempt.Switching
}

/** One-off notices for the UI (snackbar). */
sealed interface ReceiverSelectionEvent {
    /** The receiver reported [reportedModel] and the profile was changed [from] → [to] automatically. */
    data class ProfileAutoApplied(
        val from: ExternalReceiverProfile,
        val to: ExternalReceiverProfile,
        val reportedModel: String,
    ) : ReceiverSelectionEvent
}

/** [ReceiverConnectionController.applyReportedProfile] outcome. */
enum class ApplyProfileResult { APPLIED, NEEDS_CAPTURE_CONFIRMATION, NO_MISMATCH }
