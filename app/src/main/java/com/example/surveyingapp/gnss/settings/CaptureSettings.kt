package com.example.surveyingapp.gnss.settings

import kotlinx.coroutines.flow.StateFlow

class CaptureSettings(
    val minDurationSec: StateFlow<Int>,
    val maxDurationSec: StateFlow<Int>,
    val minSamples: StateFlow<Int>,
    val requireMinStatus: StateFlow<String>,   // "FLOAT" or "FIX"
    val maxFixAgeSec: StateFlow<Int>,
    val maxDiffAgeSec: StateFlow<Int>
)

class AccuracySettings(
    val overrideUere: StateFlow<Boolean>
    // Expose per-mode UERE overrides here if you want them user-configurable.
)
