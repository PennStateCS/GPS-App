package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * Policy for when to start/continue/finish an observation session.
 */
data class AveragingPolicy(
    val minDurationSec: Int = 60,
    val maxDurationSec: Int = 120,
    val minSamples: Int = 150,
    val requiredMinStatus: RtkStatus = RtkStatus.FLOAT, // e.g., require at least FLOAT
    val maxFixAgeSec: Int = 3,                          // pause if fixes get stale
    val maxDiffAgeSec: Int = 10                         // require fresh corrections when available
)
