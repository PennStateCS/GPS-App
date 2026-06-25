/**
 * ViewModel for the Home screen.
 *
 * This demonstrates the basic ViewModel pattern in Android's MVVM architecture:
 * - ViewModels survive configuration changes (screen rotation, etc.)
 * - They separate business logic from UI logic
 * - They use LiveData to automatically update the UI when data changes
 *
 * Key concepts for students:
 * - MutableLiveData: Can be changed internally by the ViewModel
 * - LiveData: Read-only view exposed to the UI (Fragment/Activity)
 * - Observer pattern: UI automatically updates when LiveData changes
 */
package com.example.surveyingapp.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.diagnostics.NmeaLogger
import com.example.surveyingapp.gnss.diagnostics.NmeaLogStats
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.domain.repository.CoordinateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val nmeaLogger: NmeaLogger
) : ViewModel() {

    // ...existing code...

    /**
     * Maps a [Fix] from the active provider (internal GPS or RS2+) to a [FixSnapshot] for the
     * FixBadgeView. This ensures the badge always reflects whichever source is selected, rather
     * than the legacy FixAccumulator which was never connected to the live data path.
     */
    private fun Fix.toFixSnapshot() = FixSnapshot(
        timestampMillis      = timeUtc.toEpochMilli(),
        timestampSource      = timestampSource,
        lat                  = latDeg,
        lon                  = lonDeg,
        altMsl               = altMslM,
        geoidSeparation      = geoidSeparationM,
        altEllipsoidal       = altEllipsoidalM,
        speedMps             = speedMps,
        courseDeg            = courseDeg,
        satsUsed             = satsUsed,
        hdop                 = hDop,
        vDop                 = vDop,
        pDop                 = pDop,
        satellitesInView     = satsVisible,
        horizontalAccuracyM  = hAccM,
        verticalAccuracyM    = vAccM,
        correctionAgeS       = diffAgeS,
        correctionStationId  = correctionStationId,
        multipathIndex       = multipathIndex,
        rtkStatus            = rtkStatus.name,
        stdLatM              = null,
        stdLonM              = null,
        stdAltM              = null
    )

    /**
     * Exposes the current GNSS fix snapshot with position data, quality metrics, and timestamp.
     * Derived from [FixSwitchboard.fixes] so it automatically reflects the active provider
     * (internal GPS or RS2+) as set in Settings.
     */
    val fixSnapshot: StateFlow<FixSnapshot> = fixSwitchboard.fixes
        .map { it.toFixSnapshot() }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = FixSnapshot(
                timestampMillis = 0L,
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
     * Exposes NMEA stream statistics for monitoring data flow health.
     */
    val nmeaStats: StateFlow<NmeaLogStats> = nmeaLogger.stats.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = nmeaLogger.stats.value
    )
}