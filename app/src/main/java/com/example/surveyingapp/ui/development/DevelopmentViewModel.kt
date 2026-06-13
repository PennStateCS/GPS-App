package com.example.surveyingapp.ui.development

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.domain.repository.ReachDeviceRepository
import com.example.surveyingapp.domain.repository.ReachBatteryInfo
import com.example.surveyingapp.domain.repository.ReachDeviceInfo
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.SkyBus
import com.example.surveyingapp.gnss.diagnostics.DiagnosticData
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.settings.SourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevelopmentViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val skyBus: SkyBus,
    private val sourceSettings: SourceSettings,
    private val diagnosticsService: DiagnosticsService,
    private val reachDeviceRepository: ReachDeviceRepository
) : ViewModel() {

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
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Unknown")

    /** Live NMEA processing stats (lines/sec, error rate, sentence history). */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticData())

    /** RS2+ battery info (null when internal GPS is active or device unreachable). */
    val batteryInfo: StateFlow<ReachBatteryInfo?> = reachDeviceRepository.batteryInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** RS2+ device info (null when internal GPS is active or device unreachable). */
    val deviceInfo: StateFlow<ReachDeviceInfo?> = reachDeviceRepository.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)


    init {
        viewModelScope.launch {
            fixSwitchboard.fixes.collect { fix -> _latestFix.value = fix }
        }
    }

    /** Reset DiagnosticsService counters (lines, errors, sentence history). */
    fun resetDiagnostics() = diagnosticsService.reset()
}

