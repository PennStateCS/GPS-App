package com.example.surveyingapp.ui.development

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.diagnostics.DiagnosticData
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.Fix
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevelopmentViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val diagnosticsService: DiagnosticsService
) : ViewModel() {

    /** Latest fix from the active GNSS bus. Null until the first fix arrives. */
    private val _latestFix = MutableStateFlow<Fix?>(null)
    val latestFix: StateFlow<Fix?> = _latestFix.asStateFlow()

    /** Live NMEA processing stats (lines/sec, error rate, sentence history). */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticData())

    init {
        viewModelScope.launch {
            fixSwitchboard.fixes.collect { fix -> _latestFix.value = fix }
        }
    }

    /** Reset DiagnosticsService counters (lines, errors, sentence history). */
    fun resetDiagnostics() = diagnosticsService.reset()
}
