package com.example.surveyingapp.gnss.reach

import org.json.JSONObject

data class ReachDeviceInfo(
    val name: String?,
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val uptimeSec: Long?
)

/**
 * Tries /info then /system/info. Picks the fields we actually show.
 */
class ReachDeviceService(private val client: ReachHttpClient) {
    suspend fun read(): ReachDeviceInfo? {
        val text = runCatching { client.get("/info") }
            .getOrElse { client.get("/system/info") }
        val j = JSONObject(text)

        val device = j.optJSONObject("device")
        val firmware = j.optJSONObject("firmware")
        val system = j.optJSONObject("system")

        return ReachDeviceInfo(
            name = device?.optString("name") ?: system?.optString("name"),
            model = device?.optString("model") ?: system?.optString("model"),
            firmware = firmware?.optString("version") ?: system?.optString("version"),
            serial = device?.optString("serial") ?: system?.optString("serial"),
            uptimeSec = device?.optLong("uptime") ?: system?.optLong("uptime")
        )
    }
}
