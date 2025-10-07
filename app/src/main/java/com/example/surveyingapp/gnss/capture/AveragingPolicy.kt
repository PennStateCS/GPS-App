package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * Policy for when to start/continue/finish an observation session.
 */
data class AveragingPolicy(
    val minDurationSec: Int = 60,                       // Minimum observation time (1 minute) to ensure sufficient data collection
    val maxDurationSec: Int = 120,                      // Maximum observation time (2 minutes) to prevent excessively long sessions
    val minSamples: Int = 150,                          // Minimum number of GNSS measurements required for statistical reliability
    val requiredMinStatus: RtkStatus = RtkStatus.FLOAT, // Minimum RTK solution quality required (FLOAT precision ~10cm)
    val maxFixAgeSec: Int = 3,                          // Maximum age of GNSS fix before pausing (ensures fresh positioning data)
    val maxDiffAgeSec: Int = 10                         // Maximum age of differential corrections before requiring refresh
)
