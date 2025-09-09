package com.example.surveyingapp.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.capture.AveragingPolicy
import com.example.surveyingapp.gnss.capture.ObservationSession
import com.example.surveyingapp.gnss.repo.CoordinateRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class CaptureViewModel(
    private val fixBus: FixBus,                  // <-- give it a name
    private val repo: CoordinateRepository
) : ViewModel() {

    private var session: ObservationSession? = null
    private lateinit var _state: StateFlow<ObservationSession.State>
    val state: StateFlow<ObservationSession.State> get() = _state

    fun start(policy: AveragingPolicy) {
        val s = ObservationSession(viewModelScope, fixBus.fixes, policy)   // <-- use fixBus
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
        val provider = when (src) {
            LocationSourceType.INTERNAL -> Provider.INTERNAL
            LocationSourceType.EXTERNAL -> {
                when (SurveyingApp.settingsRepo.externalConnType.first()) {
                    ExternalConnectionType.TCP -> Provider.RS2_TCP
                    ExternalConnectionType.BT -> Provider.RS2_BT
                }
            }
        }

        repo.saveCapture(
            name = name,
            note = note,
            colorArgb = color,
            iconId = iconId,
            result = finished,     // <-- matches interface
            provider = provider,
            sourceDevice = null,
            appVersion = null
        )
}}
