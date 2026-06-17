package com.example.surveyingapp.gnss.settings

import com.example.surveyingapp.gnss.capture.AveragingPolicy
import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * Persistent user preferences that control the GNSS averaged capture workflow.
 *
 * These settings apply only when saving an averaged GNSS capture point. They decide
 * which GNSS fixes are accepted into the average and when the capture session can
 * finish. They do not affect live AR rendering, ARCore anchor creation, or provider
 * selection.
 *
 * The policy applies to whichever provider is currently active through FixSwitchboard.
 * RTK FIX is the default because it is the minimum quality needed for survey-grade
 * external GNSS receivers. Internal phone GPS may not satisfy a FIX-only policy;
 * users who want to allow internal GPS captures should lower [requiredMinStatus].
 */
data class GnssCaptureSettings(
    val requiredMinStatus: RtkStatus = RtkStatus.FIX,
    val minDurationSec: Int = 60,
    val maxDurationSec: Int = 120,
    val minSamples: Int = 150,
    val maxFixAgeSec: Int = 3,
    val maxDiffAgeSec: Int = 10
)

/**
 * Converts persisted user settings to a runtime [AveragingPolicy].
 *
 * All values are clamped to their valid ranges here so downstream code never
 * receives out-of-range inputs even when stored preferences are corrupted.
 * maxDurationSec is additionally clamped to be at least minDurationSec.
 */
fun GnssCaptureSettings.toAveragingPolicy(): AveragingPolicy {
    val minDur = minDurationSec.coerceIn(1, 600)
    val maxDur = maxDurationSec.coerceIn(minDur, 600)
    return AveragingPolicy(
        minDurationSec    = minDur,
        maxDurationSec    = maxDur,
        minSamples        = minSamples.coerceAtLeast(1),
        requiredMinStatus = requiredMinStatus,
        maxFixAgeSec      = maxFixAgeSec.coerceAtLeast(1),
        maxDiffAgeSec     = maxDiffAgeSec.coerceAtLeast(1)
    )
}
