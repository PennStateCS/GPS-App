package app.surrealar.gnss.capture

import app.surrealar.gnss.model.RtkStatus

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
public data class AveragingPolicy(
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
    val maxDiffAgeSec: Int = 10,

    /**
     * Precision-based stop: when set, a capture is complete only once the measured horizontal spread
     * of the accepted samples (sqrt(σE² + σN²), metres) is at or below this — after the minimum time
     * and fix count. Not reached by [maxDurationSec] → completes as NOT met (cannot be saved).
     * Null = no precision requirement (time + fixes only).
     */
    val horizontalPrecisionTargetM: Double? = null,
) {
    /**
     * The correction-age gate ([maxDiffAgeSec]) applies only when the capture requires corrections
     * (DGPS, FLOAT or FIX). For SINGLE-or-better captures an old age (e.g. an SBAS age on device
     * GNSS GGA) must not reject every fix.
     */
    val requiresFreshCorrections: Boolean
        get() = requiredMinStatus.qualityRank() > RtkStatus.SINGLE.qualityRank()

    public companion object {
        /**
         * Averaging rules for INTERNAL (device / tablet) GNSS captures — the Standard preset's
         * internal policy and the Custom preset's internal fallback. Device GNSS never reaches RTK,
         * so standalone (SINGLE) quality is accepted. 30 s / 20 fixes averages out per-fix jitter and
         * single-epoch jumps; most device GNSS error is slowly varying (multipath, atmosphere), so
         * much longer sessions give diminishing returns for a field workflow. SINGLE is required, so
         * the correction-age gate does not apply ([requiresFreshCorrections] is false).
         */
        public val INTERNAL: AveragingPolicy = AveragingPolicy(
            minDurationSec = 30,
            maxDurationSec = 90,
            minSamples = 20,
            requiredMinStatus = RtkStatus.SINGLE,
            maxFixAgeSec = 3,
            maxDiffAgeSec = 10,
        )
    }
}