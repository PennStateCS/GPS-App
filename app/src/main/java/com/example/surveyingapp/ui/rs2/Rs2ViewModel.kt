package com.example.surveyingapp.ui.rs2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Exposes what the RS2 screen needs without depending on parsers or sockets.
 * Subscribe to fixes and sky; compute small bits of presentation state here.
 */
@HiltViewModel
class Rs2ViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateRepository: CoordinateRepository
) : ViewModel() {

    val fixes: SharedFlow<Fix> = fixSwitchboard.fixes

    val sky: StateFlow<SkySnapshot> = fixSwitchboard.sky

    /** Short status string for the header line. */
    val header: StateFlow<String> = combine(
        fixSwitchboard.fixes.map { it.rtkStatus.name }.onStart { emit("NONE") },
        fixSwitchboard.sky.map { s ->
            val used = s.usedByConstellation.values.sum()
            val vis = s.visibleByConstellation.values.sum()
            "$used/$vis"
        }.onStart { emit("0/0") }
    ) { mode, sats -> "RS2 • $mode • $sats" }
        .stateIn(viewModelScope, SharingStarted.Lazily, "RS2 • — • —")
}
