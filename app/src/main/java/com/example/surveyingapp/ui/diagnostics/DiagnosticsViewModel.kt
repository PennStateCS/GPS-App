package com.example.surveyingapp.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.bus.SkyBus
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.settings.SourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsService: DiagnosticsService,
    private val fixBus: FixBus,
    private val skyBus: SkyBus,
    private val sourceSettings: SourceSettings
) : ViewModel() {

    // ── Source / provider ─────────────────────────────────────────────────────

    /** Human-readable name of the currently active GNSS provider. */
    val activeProviderLabel: StateFlow<String> = sourceSettings.activeProvider
        .map { choice ->
            when (choice) {
                SourceSettings.ProviderChoice.INTERNAL     -> "Internal GNSS Receiver"
                SourceSettings.ProviderChoice.EXTERNAL_TCP -> "RS2+ External GNSS Receiver (TCP)"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Unknown")

    // ── Live fix ──────────────────────────────────────────────────────────────

    /**
     * Latest [Fix] from whichever provider is active.
     * Null until the first fix arrives.
     */
    val latestFix: StateFlow<Fix?> = fixBus.fixes
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Satellite sky ─────────────────────────────────────────────────────────

    /** Current satellite sky snapshot (empty until provider supplies GSV data). */
    val skySnapshot: StateFlow<SkySnapshot> = skyBus.sky
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkySnapshot())

    // ── NMEA processing metrics (populated during replay via DiagnosticsService) ──

    val diagnosticData = diagnosticsService.diagnosticData.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = com.example.surveyingapp.gnss.diagnostics.DiagnosticData()
    )

    fun resetDiagnostics() { diagnosticsService.reset() }
}
