package com.example.surveyingapp.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.diagnostics.DiagnosticData
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsService: DiagnosticsService,
    private val fixAccumulator: FixAccumulator
) : ViewModel() {

    /**
     * Exposes diagnostic data including lines/sec, parse error rate, and sentence history
     */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DiagnosticData()
    )

    /**
     * Exposes current fix snapshot with all GNSS data fields
     */
    val fixSnapshot: StateFlow<FixSnapshot> = fixAccumulator.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FixSnapshot(
            timestampMillis = System.currentTimeMillis(),
            timestampSource = com.example.surveyingapp.gnss.model.TimestampSource.DEVICE,
            lat = null,
            lon = null,
            altMsl = null,
            geoidSeparation = null,
            altEllipsoidal = null,
            speedMps = null,
            courseDeg = null,
            satsUsed = null,
            hdop = null,
            vDop = null,
            pDop = null,
            satellitesInView = null,
            horizontalAccuracyM = null,
            verticalAccuracyM = null,
            correctionAgeS = null,
            correctionStationId = null,
            multipathIndex = null,
            rtkStatus = null,
            stdLatM = null,
            stdLonM = null,
            stdAltM = null
        )
    )

    /**
     * Resets all diagnostic counters and metrics
     */
    fun resetDiagnostics() {
        diagnosticsService.reset()
    }
}
