package app.surrealar.ui.development

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.gnss.receiver.ReceiverBattery
import app.surrealar.gnss.receiver.ReceiverCapabilities
import app.surrealar.gnss.receiver.ReceiverDeviceInfo
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.bus.SkyBus
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.diagnostics.DiagnosticsService
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.SkySnapshot
import app.surrealar.gnss.source.SourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the developer screen: exposes live fix/sky/diagnostics streams from the GNSS
 * buses and `DiagnosticsService`. Read-only with respect to captured data.
 */
@HiltViewModel
class DevelopmentViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val skyBus: SkyBus,
    private val sourceSettings: SourceSettings,
    private val diagnosticsService: DiagnosticsService,
    private val receiverDevices: ReceiverDeviceManager,
    coordinateSystemService: app.surrealar.coords.CoordinateSystemService,
) : ViewModel() {

    /**
     * External receiver datum (Settings → Coordinates). When NAD83(2011), external fixes are
     * converted to WGS84 as they enter the feed, so the panel's lat/lon differ from the receiver's.
     */
    val receiverDatum: StateFlow<app.surrealar.settings.model.ReceiverDatum> = coordinateSystemService.settingsFlow
        .map { it.externalReceiverDatum }
        .stateIn(viewModelScope, SharingStarted.Eagerly, coordinateSystemService.settings.externalReceiverDatum)

    /** Latest fix from the active GNSS bus. Null until the first fix arrives. */
    private val _latestFix = MutableStateFlow<Fix?>(null)
    val latestFix: StateFlow<Fix?> = _latestFix.asStateFlow()

    /** Current satellite sky snapshot (empty until provider supplies GSV data). */
    val skySnapshot: StateFlow<SkySnapshot> = skyBus.sky
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkySnapshot())

    /** Human-readable name of the currently active GNSS provider. */
    val activeProviderLabel: StateFlow<String> = sourceSettings.activeProvider
        .map { choice ->
            when (choice) {
                SourceSettings.ProviderChoice.INTERNAL     -> "Internal GNSS Receiver"
                SourceSettings.ProviderChoice.EXTERNAL_TCP -> "RS2+ External GNSS Receiver (TCP)"
                SourceSettings.ProviderChoice.REPLAY       -> "NMEA Replay"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Unknown")

    /** Live NMEA processing stats (lines/sec, error rate, sentence history). */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticData())

    /** Receiver battery (null when internal GNSS is active, unsupported, or the device is unreachable). */
    val batteryInfo: StateFlow<ReceiverBattery?> = receiverDevices.battery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Receiver device info (null when internal GNSS is active, unsupported, or unreachable). */
    val deviceInfo: StateFlow<ReceiverDeviceInfo?> = receiverDevices.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What the active receiver reports; drives which Developer sections are shown. */
    val receiverCapabilities: StateFlow<ReceiverCapabilities> = receiverDevices.capabilities


    init {
        viewModelScope.launch {
            // currentFix clears on a provider switch / lost signal, so the panel never shows a frozen fix.
            fixSwitchboard.currentFix.collect { fix -> _latestFix.value = fix }
        }
    }

    /** Reset DiagnosticsService counters (lines, errors, sentence history). */
    fun resetDiagnostics() = diagnosticsService.reset()
}

