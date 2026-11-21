package app.surrealar.receiver.emlid
import org.json.JSONObject

/**
 * Raw battery readout parsed from the Reach device's HTTP/JSON response. Service-layer DTO — mapped
 * into the receiver-neutral `ReceiverBattery` (EmlidMapping) before use.
 */
public data class BatteryStatus(
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
internal class ReachBatteryService(private val client: ReachHttpClient) {

    suspend fun read(): BatteryStatus? =
        tryEndpoint("/battery") ?: tryEndpoint("/status")

    private suspend fun tryEndpoint(path: String): BatteryStatus? {
        val text = runCatching { client.get(path) }.getOrNull() ?: return null
        return parse(text)
    }

    internal companion object {
        /**
         * Pure JSON → [BatteryStatus] parse of a /battery or /status body. Returns null for
         * malformed JSON or when no `state_of_charge` is present in this layout.
         */
        internal fun parse(text: String): BatteryStatus? {
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

            val percent = parseStateOfCharge(j.opt("state_of_charge"))
                ?: return null                // field not present in this endpoint/layout

            val chargerStatus = j.optString("charger_status").takeIf { it.isNotBlank() }

            return BatteryStatus(
                percent            = percent,
                voltageV           = anyDouble("voltage", "voltage_v"),
                currentA           = anyDouble("current", "current_a"),
                temperatureC       = anyDouble("temperature", "temperature_c"),
                chargerStatus      = chargerStatus,
                isCharging         = isChargingStatus(chargerStatus),
                otg                = j.optBoolean("otg", false),
                usbChargerCurrentA = anyDouble("usb_charger_current"),
                usbChargerVoltageV = anyDouble("usb_charger_voltage")
            )
        }

        /**
         * Converts a raw `state_of_charge` value to a 0–100 percent, or null when absent/unparseable.
         *
         * Firmware reports whole percentages (`100`, `"45%"`). A value strictly between 0 and 1
         * (e.g. `0.85`) is treated as a fraction. Whole values — including `1` / `1.0` — are
         * percentages: misreading 1 % as a fraction would show a full battery on a dying receiver.
         */
        internal fun parseStateOfCharge(raw: Any?): Int? {
            val v = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().removeSuffix("%").trim().toDoubleOrNull()
                else      -> null
            } ?: return null
            if (!v.isFinite()) return null
            val pct = if (v > 0.0 && v < 1.0) v * 100.0 else v
            return pct.toInt().coerceIn(0, 100)
        }

        /**
         * The one charging rule. True only for an explicit charging state ("Charging", "Fast
         * charging", "Trickle charging", …); never for "Not charging" or "Discharging".
         * Blank/unknown → false.
         */
        internal fun isChargingStatus(chargerStatus: String?): Boolean {
            val s = chargerStatus?.trim()?.lowercase() ?: return false
            if (s.isEmpty() || "not" in s || "dis" in s) return false
            return "charging" in s
        }
    }
}
