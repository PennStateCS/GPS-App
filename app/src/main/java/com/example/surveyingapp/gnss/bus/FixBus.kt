package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public GNSS streams used by the rest of the app.
 *
 * Sources and adapters publish fixes and sky state internally. View models,
 * repositories, and UI components should consume these buses instead of
 * depending on a specific GNSS source implementation.
 */
interface FixBus {
    val fixes: SharedFlow<Fix>
}

interface SkyBus {
    val sky: StateFlow<SkySnapshot>
}