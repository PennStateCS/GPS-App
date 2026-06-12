package com.example.surveyingapp.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.diagnostics.DiagnosticData
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.TimestampSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsService: DiagnosticsService,
    private val fixBus: FixBus
) : ViewModel() {

    /**
     * Exposes diagnostic data including lines/sec, parse error rate, and sentence history.
     */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiagnosticData()
    )

    /**
     * Live GNSS fix snapshot reflecting whichever provider is currently active
     * (internal GPS or external TCP). Mapped from [FixBus.fixes] so it updates
     * in real-time during normal surveying — not just during NMEA replay.
     */
    val fixSnapshot: StateFlow<FixSnapshot> = fixBus.fixes
        .map { fix ->
            FixSnapshot(
                timestampMillis     = fix.timeUtc.toEpochMilli(),
                timestampSource     = fix.timestampSource,
                lat                 = fix.latDeg,
                lon                 = fix.lonDeg,
                altMsl              = fix.altMslM,
                geoidSeparation     = fix.geoidSeparationM,
                altEllipsoidal      = fix.altEllipsoidalM,
                speedMps            = fix.speedMps,
                courseDeg           = fix.courseDeg,
                satsUsed            = fix.satsUsed,
                hdop                = fix.hDop,
                vDop                = fix.vDop,
                pDop                = fix.pDop,
                satellitesInView    = fix.satsVisible,
                horizontalAccuracyM = fix.hAccM,
                verticalAccuracyM   = fix.vAccM,
                correctionAgeS      = fix.diffAgeS,
                correctionStationId = fix.correctionStationId,
                multipathIndex      = fix.multipathIndex,
                rtkStatus           = fix.rtkStatus.name,
                stdLatM             = null,
                stdLonM             = null,
                stdAltM             = null
            )
        }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = FixSnapshot(
                timestampMillis = System.currentTimeMillis(),
                timestampSource = TimestampSource.DEVICE,
                lat = null, lon = null,
                altMsl = null, geoidSeparation = null, altEllipsoidal = null,
                speedMps = null, courseDeg = null,
                satsUsed = null, hdop = null,
                vDop = null, pDop = null,
                satellitesInView = null, horizontalAccuracyM = null,
                verticalAccuracyM = null, correctionAgeS = null,
                correctionStationId = null, multipathIndex = null,
                rtkStatus = null, stdLatM = null, stdLonM = null, stdAltM = null
            )
        )

    /**
     * Resets all diagnostic counters and metrics.
     */
    fun resetDiagnostics() {
        diagnosticsService.reset()
    }
}
