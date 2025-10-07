package com.example.surveyingapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // NMEA logging settings
    val nmeaLoggingEnabled: StateFlow<Boolean> = settingsRepository.nmeaLoggingEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val nmeaLogMaxFileSizeMB: StateFlow<Int> = settingsRepository.nmeaLogMaxFileSizeMB.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 5
    )

    fun setNmeaLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNmeaLoggingEnabled(enabled)
        }
    }

    fun setNmeaLogMaxFileSizeMB(sizeMB: Int) {
        viewModelScope.launch {
            settingsRepository.setNmeaLogMaxFileSizeMB(sizeMB)
        }
    }
}
