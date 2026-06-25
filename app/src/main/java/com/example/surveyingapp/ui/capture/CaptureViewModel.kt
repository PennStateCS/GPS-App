package com.example.surveyingapp.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.CoordinateFactory
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.capture.toAveragingPolicy
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
import com.example.surveyingapp.util.DiagnosticsLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val captureSettings: CaptureSettings,
    private val sourceSettings: com.example.surveyingapp.gnss.source.SourceSettings
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
        // Cancel any in-progress averaging session when the provider switches.
        // A session collecting RS2+ fixes must not continue under Internal GPS and vice versa.
        viewModelScope.launch {
            var previousProvider: com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice? = null
            sourceSettings.activeProvider.collect { provider ->
                if (previousProvider != null && previousProvider != provider) {
                    if (session != null) {
                        DiagnosticsLogger.i("Capture", "Capture canceled/reset due to provider switch $previousProvider -> $provider")
                        cancel()
                    }
                    // Reset readiness state so a stale fix from the old provider can't keep
                    // the "OK to capture" indicator green after the source changes. The new
                    // provider must satisfy the dwell window again from scratch.
                    satisfyingStartTime = null
                    _okToCapture.value = false
                }
                previousProvider = provider
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
        // Cancel any existing session FIRST so a previous ObservationSession can't keep collecting
        // fixes or emitting state into the UI after a new capture begins.
        if (session != null) {
            DiagnosticsLogger.i("Capture", "Previous capture session canceled before new start")
        }
        stopSession()
        _state.value = ObservationSession.State.Idle

        val provider = sourceSettings.activeProvider.value
        DiagnosticsLogger.i("Capture", "Capture started source=$provider " +
            "minDur=${policy.minDurationSec}s maxDur=${policy.maxDurationSec}s " +
            "minFixes=${policy.minSamples} required=${policy.requiredMinStatus}")
        // The averaging session is only used for EXTERNAL capture. Feed it ONLY external-provider
        // fixes so internal GPS fixes can never be averaged into an external capture — even if the
        // required quality is lowered to Single/DGPS, and even during the window where External is
        // selected but the live provider is still Internal (reconnecting).
        val externalFixes = fixSwitchboard.fixes.filter {
            it.provider != com.example.surveyingapp.gnss.model.Provider.INTERNAL
        }
        val s = ObservationSession(viewModelScope, externalFixes, policy)
        session = s
        sessionForwardJob = viewModelScope.launch {
            s.state.collect { st ->
                _state.value = st
                if (st is ObservationSession.State.Complete) {
                    if (st.requirementsMet) {
                        DiagnosticsLogger.i("Capture",
                            "Capture ready (requirements met) acceptedFixes=${st.result.samples}")
                    } else {
                        DiagnosticsLogger.w("Capture",
                            "Capture timed out before requirements met acceptedFixes=${st.result.samples}/${policy.minSamples}")
                    }
                }
            }
        }
        s.start()
    }

    fun cancel() {
        if (session != null) DiagnosticsLogger.i("Capture", "Capture canceled")
        stopSession()
        _state.value = ObservationSession.State.Idle
    }

    /**
     * Tears down the current session and its state-forwarding job WITHOUT touching the public
     * state (callers set it). Forwarding is cancelled before the session so the old session's
     * final Idle emission can't leak into [_state].
     */
    private fun stopSession() {
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        session?.cancel()
        session = null
    }

    override fun onCleared() {
        super.onCleared()
        sessionForwardJob?.cancel()
    }

    suspend fun save(name: String, note: String?, color: Int, iconId: String) {
        val completed = (state.value as? ObservationSession.State.Complete) ?: return
        // Defense-in-depth: never persist a timed-out, under-sampled capture. The UI disables
        // Save in that case; this guards the data path too.
        if (!completed.requirementsMet) {
            DiagnosticsLogger.w("Capture", "Save blocked: requirements not met (timed out)")
            return
        }
        val finished = completed.result

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

        DiagnosticsLogger.i("Capture", "Coordinate saved name=\"$name\" source=${provider.name} " +
            "samples=${finished.samples} hAcc=${finished.hAccM?.let { "%.3fm".format(it) } ?: "?"} status=${finished.rtkStatus}")
        coordinateRepository.insert(coordinate)
    }
}
