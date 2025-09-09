package com.example.surveyingapp.ui.rs2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.bus.SkyBus
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.*

/**
 * Exposes what the RS2 screen needs without depending on parsers or sockets.
 * Subscribe to fixes and sky; compute small bits of presentation state here.
 */
class Rs2ViewModel(
    bus: FixBus,
    skyBus: SkyBus
) : ViewModel() {

    val fixes: SharedFlow<Fix> = bus.fixes

    val sky: StateFlow<SkySnapshot> = skyBus.sky

    /** Short status string for the header line. */
    val header: StateFlow<String> = combine(
        bus.fixes.map { it.rtkStatus.name }.onStart { emit("NONE") },
        skyBus.sky.map { s ->
            val used = s.usedByConstellation.values.sum()
            val vis = s.visibleByConstellation.values.sum()
            "$used/$vis"
        }.onStart { emit("0/0") }
    ) { mode, sats -> "RS2 • $mode • $sats sats" }
        .stateIn(viewModelScope, SharingStarted.Lazily, "RS2 • — • —")
}
