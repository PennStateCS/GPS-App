package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * Defines the quality and timing rules for a GNSS averaging capture session.
 *
 * A capture session collects accepted fixes until the minimum duration and minimum
 * sample count have both been met. The session can also finish earlier if the
 * maximum duration is reached.
 *
 * Fixes below [requiredMinStatus] are rejected. Fixes with stale timestamps or
 * stale correction data are paused/rejected according to [maxFixAgeSec] and
 * [maxDiffAgeSec].
 */
data class AveragingPolicy(
    /** Minimum number of seconds to collect before the session is allowed to finish. */
    val minDurationSec: Int = 60,

    /** Maximum number of seconds to collect before the session is forced to finish. */
    val maxDurationSec: Int = 120,

    /** Minimum number of accepted fixes required before the session is allowed to finish. */
    val minSamples: Int = 150,

    /**
     * Minimum RTK quality required for a fix to be accepted.
     *
     * For the default policy, FLOAT or FIX fixes are accepted, while lower-quality
     * fixes such as SINGLE or DGPS are rejected.
     */
    val requiredMinStatus: RtkStatus = RtkStatus.FLOAT,

    /**
     * Maximum allowed age of the fix timestamp, in seconds.
     *
     * This prevents the capture from accepting old GNSS data when the receiver
     * stream has stalled or the app has not received a recent update.
     */
    val maxFixAgeSec: Int = 3,

    /**
     * Maximum allowed age of differential/RTK correction data, in seconds.
     *
     * Stale corrections can make an RTK solution unreliable even when the reported
     * RTK status still looks acceptable.
     */
    val maxDiffAgeSec: Int = 10
)