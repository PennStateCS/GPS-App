package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.receiver.AccuracyClass

/**
 * Default UERE values (1σ ranging error, metres) used when the receiver reports no accuracy of its
 * own. The estimator multiplies them by DOP (see [AccuracyEstimator]). Keyed by the receiver's
 * [AccuracyClass] and fix mode.
 *
 * These defaults are intentionally conservative. They should be treated as configuration values,
 * not survey-grade calibration constants.
 *
 * SINGLE (standalone, no corrections) — reasoning:
 *  - Signal-in-space range error alone is ~0.5–1 m RMS for modern GPS/Galileo; tropospheric
 *    residuals and multipath add roughly another 1–2 m for a single-point solution.
 *  - [surveyMultiband] (e.g. RS2+): multi-band (L1/L2), so the ionosphere is largely removed →
 *    2.5 m, conservative. (The values formerly used for every external receiver.)
 *  - [device] (SM-X940 class, small antenna, strong multipath) → 4.0 m.
 *  - [singleFrequency] → 5.0 m: an L1-only receiver cannot remove the ionosphere (1–5 m of range
 *    error, more at solar maximum), and a "generic NMEA" source could be anything, so it is set
 *    above the device value rather than below it.
 *
 * Corrected modes for [singleFrequency] — an L1-only receiver needs longer to fix and false-fixes
 * more often than a multi-band one, so each value is several times the multi-band one:
 *  - DGPS 2.0 m (SBAS/DGPS code corrections, same as the device row),
 *  - FLOAT 1.0 m (L1 float solutions commonly sit at decimetres to a metre),
 *  - FIX 0.10 m (L1 RTK fixed is cm-level on short baselines; 5× the multi-band 0.02 m for the
 *    weaker fix validation).
 *
 * DEAD_RECKONING (GGA quality 6) is an estimate with no satellite solution; its error grows without
 * bound, so it gets a large value (10 m) in every class — it is also never accepted for capture.
 * NONE / INVALID have no position solution at all: [uere] returns null, so no accuracy is shown or
 * estimated instead of a made-up number.
 */
public data class UereTable(
    val device: Map<RtkStatus, Double> = mapOf(
        RtkStatus.DEAD_RECKONING to 10.0,
        RtkStatus.SINGLE to 4.0,
        RtkStatus.DGPS to 2.0,
        RtkStatus.FLOAT to 2.0,
        RtkStatus.FIX to 2.0
    ),
    val surveyMultiband: Map<RtkStatus, Double> = mapOf(
        RtkStatus.DEAD_RECKONING to 10.0,
        RtkStatus.SINGLE to 2.5,
        RtkStatus.DGPS to 0.5,
        RtkStatus.FLOAT to 0.25,
        RtkStatus.FIX to 0.02
    ),
    val singleFrequency: Map<RtkStatus, Double> = mapOf(
        RtkStatus.DEAD_RECKONING to 10.0,
        RtkStatus.SINGLE to 5.0,
        RtkStatus.DGPS to 2.0,
        RtkStatus.FLOAT to 1.0,
        RtkStatus.FIX to 0.10
    ),
) {
    /**
     * The UERE for [accuracyClass] in [mode], or null when the mode has no position solution
     * (NONE / INVALID).
     */
    public fun uere(accuracyClass: AccuracyClass, mode: RtkStatus): Double? {
        if (mode == RtkStatus.NONE || mode == RtkStatus.INVALID) return null
        return when (accuracyClass) {
            AccuracyClass.SURVEY_MULTIBAND -> surveyMultiband[mode] ?: SURVEY_FALLBACK_UERE_M
            AccuracyClass.SINGLE_FREQUENCY -> singleFrequency[mode] ?: SINGLE_FREQUENCY_FALLBACK_UERE_M
            AccuracyClass.DEVICE -> device[mode] ?: DEVICE_FALLBACK_UERE_M
        }
    }

    /** As [uere] with the class assumed for [provider] ([AccuracyClass.defaultFor]). */
    public fun uere(provider: Provider, mode: RtkStatus): Double? =
        uere(AccuracyClass.defaultFor(provider), mode)

    private companion object {
        /** Used only when a custom table omits a mode: the standalone values above. */
        const val DEVICE_FALLBACK_UERE_M = 4.0
        const val SURVEY_FALLBACK_UERE_M = 2.5
        const val SINGLE_FREQUENCY_FALLBACK_UERE_M = 5.0
    }
}
