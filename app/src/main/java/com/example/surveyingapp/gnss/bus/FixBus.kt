package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Central streams for position fixes and sky state.
 * UI and repositories subscribe here instead of touching sources directly.
 */
interface FixBus {
    val fixes: SharedFlow<Fix>
}

interface SkyBus {
    val sky: StateFlow<SkySnapshot>
}
