package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus

/**
 * Default UERE values used when receiver-reported accuracy is not available.
 *
 * UERE represents the expected ranging error for a given receiver/source and fix
 * mode. The accuracy estimator combines this value with DOP to produce a fallback
 * horizontal or vertical accuracy estimate.
 *
 * These defaults are intentionally conservative. They should be treated as
 * configuration values, not survey-grade calibration constants.
 *
 * SINGLE (standalone, no corrections) — reasoning for the values:
 *  - Signal-in-space range error alone is ~0.5–1 m RMS for modern GPS/Galileo; tropospheric
 *    residuals and multipath add roughly another 1–2 m for a single-point solution.
 *  - RS2+: multi-band (L1/L2), so the ionosphere is largely removed → 2.5 m 1σ, conservative.
 *  - Device GNSS (SM-X940 class, small antenna, strong multipath) → 4.0 m.
 * DEAD_RECKONING (GGA quality 6) is an estimate with no satellite solution; its error grows
 * without bound, so it gets a large value (10 m) — it is also never accepted for capture.
 * NONE / INVALID have no position solution at all: [uere] returns null, so no accuracy is
 * shown or estimated instead of a made-up number.
 */
data class UereTable(
    val internal: Map<RtkStatus, Double> = mapOf(
        RtkStatus.DEAD_RECKONING to 10.0,
        RtkStatus.SINGLE to 4.0,
        RtkStatus.DGPS to 2.0,
        RtkStatus.FLOAT to 2.0,
        RtkStatus.FIX to 2.0
    ),
    val rs2: Map<RtkStatus, Double> = mapOf(
        RtkStatus.DEAD_RECKONING to 10.0,
        RtkStatus.SINGLE to 2.5,
        RtkStatus.DGPS to 0.5,
        RtkStatus.FLOAT to 0.25,
        RtkStatus.FIX to 0.02
    )
) {
    /**
     * Returns the configured UERE value for the active provider and RTK mode, or null when the
     * mode has no position solution (NONE / INVALID).
     *
     * Internal and unknown providers use the internal defaults. RS2 connection
     * types share the same table because they represent the same receiver class
     * over different transports.
     */
    fun uere(provider: Provider, mode: RtkStatus): Double? {
        if (mode == RtkStatus.NONE || mode == RtkStatus.INVALID) return null
        return when (provider) {
            Provider.RS2_EXTERNAL,
            Provider.RS2_BT,
            Provider.RS2_TCP -> rs2[mode] ?: RS2_FALLBACK_UERE_M
            Provider.INTERNAL,
            Provider.OTHER,
            Provider.MODEL -> internal[mode] ?: INTERNAL_FALLBACK_UERE_M
        }
    }

    private companion object {
        /** Used only when a custom table omits a mode: the standalone values above. */
        const val INTERNAL_FALLBACK_UERE_M = 4.0
        const val RS2_FALLBACK_UERE_M = 2.5
    }
}
