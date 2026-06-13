package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * Gates that control when a capture session starts collecting, keeps collecting,
 * and finishes. All values can be overridden at construction time.
 *
 * The session runs until both [minDurationSec] and [minSamples] are satisfied,
 * or until [maxDurationSec] is hit — whichever comes first.
 */
data class AveragingPolicy(
    val minDurationSec: Int = 60,
    val maxDurationSec: Int = 120,
    val minSamples: Int = 150,
    val requiredMinStatus: RtkStatus = RtkStatus.FLOAT, // Reject any fix below this quality
    val maxFixAgeSec: Int = 3,                          // Pause if the fix is older than this
    val maxDiffAgeSec: Int = 10                         // Pause if corrections are older than this
)
