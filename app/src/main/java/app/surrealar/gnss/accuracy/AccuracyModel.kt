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
 */
data class UereTable(
    val internal: Map<RtkStatus, Double> = mapOf(
        RtkStatus.NONE to 4.0,
        RtkStatus.DGPS to 2.0,
        RtkStatus.FLOAT to 2.0,
        RtkStatus.FIX to 2.0
    ),
    val rs2: Map<RtkStatus, Double> = mapOf(
        RtkStatus.NONE to 1.0,
        RtkStatus.DGPS to 0.5,
        RtkStatus.FLOAT to 0.25,
        RtkStatus.FIX to 0.02
    )
) {
    /**
     * Returns the configured UERE value for the active provider and RTK mode.
     *
     * Internal and unknown providers use the internal defaults. RS2 connection
     * types share the same table because they represent the same receiver class
     * over different transports.
     */
    fun uere(provider: Provider, mode: RtkStatus): Double =
        when (provider) {
            Provider.INTERNAL -> internal[mode] ?: INTERNAL_FALLBACK_UERE_M
            Provider.RS2_EXTERNAL -> rs2[mode] ?: RS2_FALLBACK_UERE_M
            Provider.RS2_BT -> rs2[mode] ?: RS2_FALLBACK_UERE_M
            Provider.RS2_TCP -> rs2[mode] ?: RS2_FALLBACK_UERE_M
            Provider.OTHER,
            Provider.MODEL -> internal[mode] ?: INTERNAL_FALLBACK_UERE_M
        }

    private companion object {
        const val INTERNAL_FALLBACK_UERE_M = 4.0
        const val RS2_FALLBACK_UERE_M = 1.0
    }
}