package app.surrealar.receiver

import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDevice
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import app.surrealar.settings.model.ExternalReceiverProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A receiver with nothing to report beyond NMEA: no capabilities, every flow null. */
object GenericNmeaDevice : ReceiverDevice {
    override val capabilities: ReceiverCapabilities = ReceiverCapabilities.NONE
    override fun start(host: String) = Unit
    override fun stop() = Unit
    override val battery: StateFlow<ReceiverBattery?> = MutableStateFlow(null)
    override val deviceInfo: StateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null)
    override val corrections: StateFlow<ReceiverCorrections?> = MutableStateFlow(null)
}

/**
 * Owns the [ReceiverDevice] for the current GNSS source and receiver profile, and exposes its status
 * flows to the UI (toolbar battery, Settings, Developer page) so no UI code knows the vendor.
 *
 * Start/stop timing is the former ReachDeviceRepository's: a device runs while the active source is
 * External TCP, and stops (flows cleared) when the source changes away. In addition:
 * - the device comes from the profile's vendor ([deviceFor]): Emlid → Emlid HTTP device, Generic →
 *   [GenericNmeaDevice] (nothing polled);
 * - a host change restarts the device on the new host; switching between profiles of the same vendor
 *   (e.g. RS2+ → RS4) keeps it running.
 * Only one device runs at a time; the previous one is always stopped first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverDeviceManager(
    externalSourceActive: Flow<Boolean>,
    profile: Flow<ExternalReceiverProfile>,
    host: Flow<String?>,
    private val deviceFor: (ReceiverVendor) -> ReceiverDevice,
    private val discoveryFor: (ExternalReceiverProfile) -> ReceiverDiscovery?,
    scope: CoroutineScope,
) {
    private data class Target(val external: Boolean, val profile: ExternalReceiverProfile, val host: String?)

    private val _activeDevice = MutableStateFlow<ReceiverDevice>(GenericNmeaDevice)
    /** The device for the current source/profile ([GenericNmeaDevice] when nothing to poll). */
    val activeDevice: StateFlow<ReceiverDevice> = _activeDevice.asStateFlow()

    private val _profile = MutableStateFlow(ExternalReceiverProfile.DEFAULT)

    /** What the active receiver reports (all false for internal GNSS / generic NMEA). */
    val capabilities: StateFlow<ReceiverCapabilities> =
        _activeDevice.map { it.capabilities }.stateIn(scope, SharingStarted.Eagerly, ReceiverCapabilities.NONE)

    /** Battery of the active receiver; null when unknown or unsupported. */
    val battery: StateFlow<ReceiverBattery?> =
        _activeDevice.flatMapLatest { it.battery }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Device information of the active receiver; null when unknown or unsupported. */
    val deviceInfo: StateFlow<ReceiverDeviceInfo?> =
        _activeDevice.flatMapLatest { it.deviceInfo }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Correction-link status of the active receiver; null when unknown or unsupported. */
    val corrections: StateFlow<ReceiverCorrections?> =
        _activeDevice.flatMapLatest { it.corrections }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Capabilities of the *selected* profile, whether or not its source is active (Settings shows or
     * hides the receiver's status rows by this, so they don't flicker when the source changes).
     */
    val profileCapabilities: StateFlow<ReceiverCapabilities> =
        _profile.map { it.capabilities }.stateIn(scope, SharingStarted.Eagerly, ExternalReceiverProfile.DEFAULT.capabilities)

    /** Network discovery for the selected profile, or null when it has none. */
    val discovery: StateFlow<ReceiverDiscovery?> =
        _profile.map { p -> if (p.capabilities.supportsDiscovery) discoveryFor(p) else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private var current: ReceiverDevice = GenericNmeaDevice
    private var currentVendor: ReceiverVendor? = null
    private var currentHost: String? = null

    init {
        scope.launch {
            combine(externalSourceActive, profile, host) { ext, p, h ->
                Target(ext, p, h?.trim()?.takeIf { it.isNotEmpty() })
            }.distinctUntilChanged().collect(::apply)
        }
        // Diagnostics only: corrections link up/down and station changes (no base position).
        scope.launch { corrections.collect { app.surrealar.util.diagnostics.GnssFeedLogger.onCorrections(it) } }
    }

    private fun apply(t: Target) {
        _profile.value = t.profile
        app.surrealar.util.DiagnosticsLogger.logOnChange(app.surrealar.util.diagnostics.DiagTags.RECEIVER, "rx.profile", t.profile.label) {
            "External receiver profile changed profile=\"${t.profile.label}\" accuracyClass=${t.profile.accuracyClass}"
        }
        if (!t.external) {
            switchTo(null)
            return
        }
        val vendor = t.profile.vendor
        if (vendor != currentVendor) switchTo(vendor)
        when {
            t.host == null -> if (currentHost != null) { current.stop(); currentHost = null }
            t.host != currentHost -> { current.stop(); current.start(t.host); currentHost = t.host }
        }
    }

    /** Stops the running device and makes the one for [vendor] (or none) active, not yet started. */
    private fun switchTo(vendor: ReceiverVendor?) {
        if (vendor == currentVendor && vendor != null) return
        current.stop()
        currentHost = null
        current = vendor?.let(deviceFor) ?: GenericNmeaDevice
        currentVendor = vendor
        _activeDevice.value = current
        if (vendor != null) GnssLog.d(TAG, "Receiver device: $vendor (${current.capabilities})")
        app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.RECEIVER,
            if (vendor == null) "External receiver device selected: none, Device GNSS or generic NMEA vendor=none"
            else "External receiver device selected vendor=$vendor profile=\"${_profile.value.label}\"")
    }

    private companion object { const val TAG = "ReceiverDevice" }
}
