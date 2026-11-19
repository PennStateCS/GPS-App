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
package app.surrealar.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.gnss.accumulator.FixSnapshot
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.diagnostics.DiagnosticsService
import app.surrealar.gnss.diagnostics.NmeaLogger
import app.surrealar.gnss.diagnostics.NmeaLogStats
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.SkySnapshot
import app.surrealar.domain.repository.CoordinateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the Home screen, bridging the live GNSS/source data layer to the Home UI.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val diagnosticsService: DiagnosticsService
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
    private val emptySnapshot = FixSnapshot(
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

    // Derived from currentFix (not the fixes replay buffer): it goes null on a provider switch and
    // when fixes stop, so the badge / Field Status never present a frozen position as live.
    val fixSnapshot: StateFlow<FixSnapshot> = fixSwitchboard.currentFix
        .map { it?.toFixSnapshot() ?: emptySnapshot }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = fixSwitchboard.currentFix.value?.toFixSnapshot() ?: emptySnapshot
        )

    /**
     * NMEA stream health for the FixBadge. Sourced from [DiagnosticsService] (the live feed both
     * sources record into) â€” [NmeaLogger] is never fed in production, so it always read 0/s.
     */
    val nmeaStats: StateFlow<NmeaLogStats> = diagnosticsService.diagnosticData
        .map { d -> NmeaLogStats(totalLines = d.totalLinesProcessed, linesPerSecond = d.linesPerSecond, parseErrors = d.totalParseErrors) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NmeaLogStats()
        )

    /**
     * Live NMEA throughput/parse stats from the same [DiagnosticsService] singleton that both
     * [app.surrealar.gnss.external.TcpNmeaSource] and the internal source feed (and that
     * Developer Tools reads). This is the *real* live stream rate â€” unlike [NmeaLogger], whose
     * logging path is not wired into the production NMEA flow. Used by the Home Field Status card.
     */
    val diagnosticData: StateFlow<DiagnosticData> = diagnosticsService.diagnosticData.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = diagnosticsService.diagnosticData.value
    )

    /**
     * Live sky snapshot (satellite counts) following the active provider â€” the same source the
     * app-wide GNSS toolbar uses, so the Field Status "Sats" chip matches the toolbar exactly.
     */
    val sky: StateFlow<SkySnapshot> = fixSwitchboard.sky.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = fixSwitchboard.sky.value
    )
}
