package app.surrealar.gnss.external

import org.json.JSONObject

data class BatteryStatus(
    val percent: Int,
    val voltageV: Double?,
    val currentA: Double?,
    val temperatureC: Double?,
    val chargerStatus: String?,
    val isCharging: Boolean,
    val otg: Boolean? = null,
    val usbChargerCurrentA: Double? = null,
    val usbChargerVoltageV: Double? = null
)

/**
 * Parses RS2+ battery status. Tries multiple endpoints and JSON layouts:
 *
 * 1. GET /battery — dedicated endpoint (ReachView 3 newer firmware)
 *    JSON (flat at root):
 *    { "charger_status": "Not charging", "current": 0.0, "otg": false,
 *      "state_of_charge": 100, "temperature": 32.6, "voltage": 6.86 }
 *
 * 2. GET /status — combined status endpoint
 *    a) Battery fields nested under "battery" key
 *    b) Battery fields at root level (older firmware / ReachView 2 layout)
 */
class ReachBatteryService(private val client: ReachHttpClient) {

    suspend fun read(): BatteryStatus? =
        tryEndpoint("/battery") ?: tryEndpoint("/status")

    private suspend fun tryEndpoint(path: String): BatteryStatus? {
        val text = runCatching { client.get(path) }.getOrNull() ?: return null
        val root = try { JSONObject(text) } catch (_: Exception) { return null }

        // For /status the battery fields may be nested under a "battery" key;
        // for /battery (or older firmware) they are at the root level.
        val j = root.optJSONObject("battery") ?: root

        fun anyDouble(vararg keys: String): Double? {
            for (key in keys) {
                val v = j.optDouble(key)
                if (!v.isNaN()) return v
            }
            return null
        }

        fun soc(): Int {
            val raw = j.opt("state_of_charge")
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.removeSuffix("%").toDoubleOrNull() ?: Double.NaN
                else      -> Double.NaN
            }.let { v ->
                when {
                    v.isNaN() -> return@let -1      // sentinel: field absent
                    v <= 1.0  -> (v * 100).toInt()
                    else      -> v.toInt()
                }.coerceIn(0, 100)
            }
        }

        val percent = soc()
        if (percent < 0) return null          // field not present in this endpoint/layout

        val chargerStatus = j.optString("charger_status").takeIf { it.isNotBlank() }
        val isCharging = chargerStatus
            ?.lowercase()
            ?.let { it.contains("charging") && !it.contains("not") }
            ?: false
        val otg = j.optBoolean("otg", false)

        return BatteryStatus(
            percent            = percent,
            voltageV           = anyDouble("voltage", "voltage_v"),
            currentA           = anyDouble("current", "current_a"),
            temperatureC       = anyDouble("temperature", "temperature_c"),
            chargerStatus      = chargerStatus,
            isCharging         = isCharging,
            otg                = otg,
            usbChargerCurrentA = anyDouble("usb_charger_current"),
            usbChargerVoltageV = anyDouble("usb_charger_voltage")
        )
    }
}
