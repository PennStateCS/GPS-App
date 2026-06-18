package com.example.surveyingapp.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.CoordinateFactory
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.settings.toAveragingPolicy
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.capture.AveragingPolicy
import com.example.surveyingapp.gnss.capture.ObservationSession
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.gnss.settings.CaptureSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val captureSettings: CaptureSettings
) : ViewModel() {

    private var session: ObservationSession? = null
    private var sessionForwardJob: Job? = null

    private val _state = MutableStateFlow<ObservationSession.State>(ObservationSession.State.Idle)
    val state: StateFlow<ObservationSession.State> = _state.asStateFlow()

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

    /**
     * Loads the saved GNSS Capture settings and starts an averaging session with that policy.
     * Falls back to safe defaults if settings cannot be read.
     */
    fun startWithSavedPolicy() {
        viewModelScope.launch {
            val policy = runCatching {
                SurveyingApp.settingsRepo.gnssCaptureSettings.first().toAveragingPolicy()
            }.getOrDefault(AveragingPolicy())
            start(policy)
        }
    }

    fun start(policy: AveragingPolicy) {
        val s = ObservationSession(viewModelScope, fixSwitchboard.fixes, policy)
        session = s
        sessionForwardJob?.cancel()
        sessionForwardJob = viewModelScope.launch {
            s.state.collect { _state.value = it }
        }
        s.start()
    }

    fun cancel() {
        session?.cancel()
        session = null
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        _state.value = ObservationSession.State.Idle
    }

    override fun onCleared() {
        super.onCleared()
        sessionForwardJob?.cancel()
    }

    suspend fun save(name: String, note: String?, color: Int, iconId: String) {
        val finished = (state.value as? ObservationSession.State.Complete)?.result ?: return

        val src = SurveyingApp.settingsRepo.locationSource.first()
        val provider = when (src) {
            LocationSourceType.INTERNAL  -> Provider.INTERNAL
            LocationSourceType.EXTERNAL  -> Provider.RS2_EXTERNAL
            LocationSourceType.SIMULATOR -> Provider.OTHER
        }

        val captureMethod = when (src) {
            LocationSourceType.INTERNAL  -> "internal_gps"
            LocationSourceType.EXTERNAL  -> "external_gnss"
            LocationSourceType.SIMULATOR -> "averaged"
        }

        val sourceDevice = when (src) {
            LocationSourceType.EXTERNAL -> {
                val deviceName = SurveyingApp.settingsRepo.externalTcpName.first()?.takeIf { it.isNotBlank() }
                val host = SurveyingApp.settingsRepo.externalTcpHost.first()
                deviceName ?: host ?: "External GNSS"
            }
            LocationSourceType.INTERNAL  -> "Internal GPS"
            LocationSourceType.SIMULATOR -> null
        }

        val coordinate = CoordinateFactory.fromCaptureResult(
            id            = UUID.randomUUID().toString(),
            name          = name,
            note          = note,
            color         = color,
            iconId        = iconId,
            provider      = provider,
            result        = finished,
            captureMethod = captureMethod,
            sourceDevice  = sourceDevice
        )

        coordinateRepository.insert(coordinate)
    }
}
