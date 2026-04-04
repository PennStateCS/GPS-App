package com.example.surveyingapp.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.capture.AveragingPolicy
import com.example.surveyingapp.gnss.capture.ObservationSession
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.gnss.settings.CaptureSettings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.Duration

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val captureSettings: CaptureSettings
) : ViewModel() {

    private var session: ObservationSession? = null
    private lateinit var _state: StateFlow<ObservationSession.State>
    val state: StateFlow<ObservationSession.State> get() = _state

    // Track when fixes start satisfying capture settings
    private var satisfyingStartTime: Long? = null

    private val _okToCapture = MutableStateFlow(false)
    val okToCapture: StateFlow<Boolean> = _okToCapture.asStateFlow()

    init {
        // Monitor fixes and update okToCapture based on CaptureSettings
        viewModelScope.launch {
            fixSwitchboard.fixes.collect { fix ->
                updateOkToCapture(fix)
            }
        }
    }

    private fun updateOkToCapture(fix: Fix) {
        val currentTime = System.currentTimeMillis()

        // Check if fix satisfies capture settings
        val satisfiesSettings = checkFixSatisfiesSettings(fix)

        if (satisfiesSettings) {
            // If this is the first satisfying fix, record the start time
            if (satisfyingStartTime == null) {
                satisfyingStartTime = currentTime
            }

            // Required dwell in ms from Duration (minDwellSec -> minDwell)
            val dwellTimeMs = currentTime - (satisfyingStartTime ?: currentTime)
            val requiredDwellMs = captureSettings.minDwell.inWholeMilliseconds

            _okToCapture.value = dwellTimeMs >= requiredDwellMs
        } else {
            // Fix doesn't satisfy settings, reset
            satisfyingStartTime = null
            _okToCapture.value = false
        }
    }

    private fun checkFixSatisfiesSettings(fix: Fix): Boolean = captureSettings.accepts(fix)

    fun start(policy: AveragingPolicy) {
        val s = ObservationSession(viewModelScope, fixSwitchboard.fixes, policy)
        _state = s.state
        session = s
        s.start()
    }

    fun cancel() {
        session?.cancel()
        session = null
    }

    suspend fun save(name: String, note: String?, color: Int, iconId: String) {
        val finished = (state.value as? ObservationSession.State.Complete)?.result ?: return

        val src = SurveyingApp.settingsRepo.locationSource.first()
        val providerEnum = when (src) {
            LocationSourceType.INTERNAL -> Provider.INTERNAL
            LocationSourceType.EXTERNAL -> Provider.RS2_EXTERNAL
            LocationSourceType.SIMULATOR -> Provider.OTHER
        }

        // Create coordinate from capture result including quality metadata
        val coordinate = com.example.surveyingapp.domain.model.Coordinate(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            latitude = finished.latDeg,
            longitude = finished.lonDeg,
            altitude = finished.altEllipsoidalM, // non-null in CaptureResult
            timestamp = System.currentTimeMillis(),
            icon = iconId,
            color = color,
            provider = providerEnum.name,
            rtkStatus = finished.rtkStatus?.name,
            satsUsed = finished.satsUsed,
            hdop = finished.hdop,
            horizontalAccuracyM = finished.hAccM,
            verticalAccuracyM = finished.vAccM,
            correctionAgeS = finished.diffAgeS,
            averagedSamples = finished.samples,
            averageDurationMs = Duration.between(finished.startedAt, finished.endedAt).toMillis(),
            note = note
        )

        coordinateRepository.insert(coordinate)
    }
}
