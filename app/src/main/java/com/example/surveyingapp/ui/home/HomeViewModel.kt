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
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.logging.NmeaLogger
import com.example.surveyingapp.gnss.logging.NmeaLogStats
import com.example.surveyingapp.domain.repository.CoordinateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository,
    private val fixAccumulator: FixAccumulator,
    private val nmeaLogger: NmeaLogger
) : ViewModel() {

    // Private MutableLiveData - only this ViewModel can change the value
    private val _text = MutableLiveData<String>().apply {
        value = "Welcome to SurveyingApp!\n\nCapture precise coordinates, manage survey points, and visualize your data with our comprehensive surveying tools.\n\nGet started by capturing coordinates or viewing your existing points."
    }

    // Public LiveData - UI can observe but not modify
    // This encapsulation protects data integrity
    val text: LiveData<String> = _text

    /**
     * Exposes the current GNSS fix snapshot with position data, quality metrics, and timestamp info.
     * This StateFlow automatically updates when new NMEA data is processed.
     */
    val fixSnapshot: StateFlow<FixSnapshot> = fixAccumulator.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = fixAccumulator.state.value
    )

    /**
     * Exposes NMEA stream statistics for monitoring data flow health.
     * Includes lines per second, parse errors, and buffer status.
     */
    val nmeaStats: StateFlow<NmeaLogStats> = nmeaLogger.stats.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = nmeaLogger.stats.value
    )
}