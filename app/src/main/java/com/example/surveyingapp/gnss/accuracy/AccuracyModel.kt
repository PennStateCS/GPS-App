package com.example.surveyingapp.gnss.accuracy

import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus

/**
 * UERE values are modelled per provider and RTK mode for DOP-based fallback.
 * Values are conservative defaults and can be overridden in settings.
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
    fun uere(provider: Provider, mode: RtkStatus): Double =
        when (provider) {
            Provider.INTERNAL -> internal[mode] ?: 4.0
            Provider.RS2_EXTERNAL -> rs2[mode] ?: 1.0
            Provider.RS2_BT -> rs2[mode] ?: 1.0
            Provider.RS2_TCP -> rs2[mode] ?: 1.0
            Provider.OTHER -> internal[mode] ?: 4.0
        }
}
