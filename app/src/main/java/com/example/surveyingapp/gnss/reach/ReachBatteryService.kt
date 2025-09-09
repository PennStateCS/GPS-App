package com.example.surveyingapp.gnss.reach

import org.json.JSONObject

data class BatteryStatus(
    val percent: Int,
    val voltageV: Double?,
    val currentA: Double?,
    val temperatureC: Double?,
    val chargerStatus: String?
)

/**
 * Tries /battery then /status/battery. Tolerates different JSON shapes.
 */
class ReachBatteryService(private val client: ReachHttpClient) {
    suspend fun read(): BatteryStatus? {
        val text = runCatching { client.get("/battery") }
            .getOrElse { client.get("/status/battery") }

        val j = JSONObject(text)
        fun anyDouble(key: String) = j.optDouble(key).takeIf { !it.isNaN() }
        fun soc(): Int {
            val raw = j.opt("state_of_charge")
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.removeSuffix("%").toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }.let { v -> when {
                v.isNaN() -> 0
                v <= 1.0 -> (v * 100).toInt()
                else -> v.toInt()
            }.coerceIn(0, 100) }
        }

        return BatteryStatus(
            percent = soc(),
            voltageV = anyDouble("voltage_v"),
            currentA = anyDouble("current_a"),
            temperatureC = anyDouble("temperature_c"),
            chargerStatus = j.optString("charger_status", null)
        )
    }
}
