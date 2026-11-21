package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.log.GnssLog
import org.json.JSONObject

/**
 * Raw device-info parsed from the Reach device's HTTP/JSON response. Service-layer DTO — mapped into
 * the receiver-neutral `ReceiverDeviceInfo` (EmlidMapping).
 */
public data class ReachDeviceInfoDto(
    val name: String?,
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val uptime: String?,
    val gnssReceiverFirmware: String?,
    val modemImei: String?,
    val storageTotalMb: Long?,       // storage.total (MB)
    val storageFreeMb: Long?,        // storage.free  (MB)
    val hostname: String?,           // device.local_address e.g. "Reach6.local"
    /** First 300 chars of the raw HTTP body – used for diagnostics in dev tools. */
    val rawResponse: String? = null,
    /** Uptime in seconds; null when absent or in a text form that couldn't be parsed. */
    val uptimeSec: Long? = null
)

private const val TAG = "ReachDeviceService"

/**
 * Parses RS2+ device info. Tries /info first, then /status.
 * Handles nested (Layout A) and flat-root (Layout B) JSON layouts,
 * plus the "general" wrapper used in some ReachView 3 builds.
 */
internal class ReachDeviceService(private val client: ReachHttpClient) {

    suspend fun read(): ReachDeviceInfoDto? =
        tryEndpoint("/info") ?: tryEndpoint("/status")

    private suspend fun tryEndpoint(path: String): ReachDeviceInfoDto? {
        val text = runCatching { client.get(path) }
            .onFailure {
                GnssLog.w(TAG, "GET $path failed: ${it.message}")
                GnssLog.diagnosticWarn("CORRECTIONS",
                    "device endpoint $path failed: ${it.javaClass.simpleName} ${it.message}")
            }
            .getOrNull() ?: return null
        return parseReachDeviceInfo(path, text)
    }

    /**
     * Pure JSON→DTO parse for a device-info response body. Handles the nested (device/general/
     * firmware) and flat-root firmware layouts, with safe fallbacks for missing/null fields and
     * malformed JSON. `internal` so unit tests can exercise it without a live HTTP client.
     */
    internal fun parseReachDeviceInfo(path: String, text: String): ReachDeviceInfoDto? {
        GnssLog.d(TAG, "GET $path → ${text.length} chars: ${text.take(300)}")

        val root = try { JSONObject(text) } catch (e: Exception) {
            GnssLog.w(TAG, "JSON parse failed for $path: ${e.message}")
            GnssLog.diagnosticWarn("CORRECTIONS", "device endpoint $path returned non-JSON: ${e.message}")
            return null
        }

        // ── helpers ──────────────────────────────────────────────────────────
        fun JSONObject.str(vararg keys: String): String? {
            for (key in keys) {
                val v = optString(key, "")
                if (v.isNotBlank() && v != "null") return v
            }
            return null
        }

        // ── Candidate sub-objects (different firmware layouts) ────────────────
        val deviceObj   = root.optJSONObject("device")
        val generalObj  = root.optJSONObject("general")   // some ReachView 3 builds
        val firmwareObj = root.optJSONObject("firmware")
        val gnssObj     = root.optJSONObject("gnss_receiver")
        val modemObj    = root.optJSONObject("modem")

        // ── Resolve each field: nested first, then root ───────────────────────
        val name = deviceObj?.str("name")
                ?: generalObj?.str("name", "device_name", "id")
                ?: root.str("name", "device_name", "id", "receiver_name")

        val model = deviceObj?.str("type", "model", "device_type")
                 ?: generalObj?.str("type", "model", "device_type", "receiver")
                 ?: root.str("type", "model", "receiver", "device_type", "product")

        val firmware = firmwareObj?.str("version", "firmware_version")
                    ?: generalObj?.str("firmware_version", "version")
                    ?: root.str("firmware_version", "version", "fw_version")

        val serial = deviceObj?.str("serial_number", "serial", "sn")
                  ?: generalObj?.str("serial_number", "serial", "sn")
                  ?: root.str("serial_number", "serial", "sn", "device_serial")

        // Uptime arrives either as a number of seconds (86400) or as text ("2 days, 1:08:08").
        // Check for a JSON number first: optString() would turn 86400 into "86400", which the
        // text parser can't read.
        fun JSONObject.uptimeNumber(): Long? =
            (opt("uptime") as? Number)?.toLong()?.takeIf { it >= 0 }
        val uptimeText = listOfNotNull(deviceObj, generalObj, root).firstNotNullOfOrNull { o ->
            o.takeIf { it.opt("uptime") !is Number }?.str("uptime")
        }
        val uptimeSec: Long? = deviceObj?.uptimeNumber()
            ?: generalObj?.uptimeNumber()
            ?: root.uptimeNumber()
            ?: uptimeText?.let { parseUptimeTextToSeconds(it) }
        val uptime: String? = uptimeText ?: uptimeSec?.let { formatUptimeSeconds(it) }

        val gnssReceiverFw = gnssObj?.str("firmware_version", "version")
                          ?: root.str("gnss_firmware", "gnss_receiver_firmware",
                                      "gnss_fw", "receiver_firmware")

        val modemImei = modemObj?.str("imei")
                     ?: root.str("modem_imei", "imei")

        // Storage – JSON: "storage": { "total": 12374, "free": 1444 }  (values in MB)
        val storageObj   = root.optJSONObject("storage")
        val storageTotalMb = storageObj?.let { s ->
            if (!s.isNull("total")) s.optLong("total", -1L).takeIf { it >= 0 } else null
        }
        val storageFreeMb = storageObj?.let { s ->
            if (!s.isNull("free")) s.optLong("free", -1L).takeIf { it >= 0 } else null
        }

        // Hostname – device.local_address e.g. "Reach6.local"
        val hostname = deviceObj?.str("local_address")
                    ?: root.str("local_address", "hostname")

        GnssLog.d(TAG, "Parsed [$path]: name=$name  model=$model  fw=$firmware  serial=$serial  uptime=$uptime  storage=${storageTotalMb}/${storageFreeMb} MB")

        // If nothing at all was parsed, this endpoint doesn't have device info
        if (name == null && model == null && firmware == null && serial == null) {
            GnssLog.w(TAG, "No device fields found in $path response – top-level keys: ${root.keys().asSequence().toList()}")
            GnssLog.diagnosticWarn("CORRECTIONS", "device endpoint $path had no device fields (endpoint may be missing/404)")
            return null
        }

        return ReachDeviceInfoDto(
            name                 = name,
            model                = model,
            firmware             = firmware,
            serial               = serial,
            uptime               = uptime,
            gnssReceiverFirmware = gnssReceiverFw,
            modemImei            = modemImei,
            storageTotalMb       = storageTotalMb,
            storageFreeMb        = storageFreeMb,
            hostname             = hostname,
            rawResponse          = text.take(300),
            uptimeSec            = uptimeSec
        )
    }

    internal companion object {
        /**
         * Parses uptime text into seconds: "2 days, 1:08:08", "1:08:08", or a bare number "86400".
         * Returns null — not 0 — when the text matches none of these forms.
         */
        internal fun parseUptimeTextToSeconds(text: String): Long? {
            val s = text.trim()
            s.toLongOrNull()?.let { return it.takeIf { v -> v >= 0 } }
            val days = Regex("""(\d+)\s+days?""").find(s)?.groupValues?.get(1)?.toLongOrNull()
            val hms = Regex("""(\d+):(\d+):(\d+)""").find(s)
            if (days == null && hms == null) return null
            var total = (days ?: 0L) * 86400
            if (hms != null) {
                val (h, m, sec) = hms.destructured
                total += h.toLong() * 3600 + m.toLong() * 60 + sec.toLong()
            }
            return total
        }
    }

    private fun formatUptimeSeconds(sec: Long): String {
        val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
        return when {
            d > 0  -> "${d}d ${h}h ${m}m"
            h > 0  -> "${h}h ${m}m"
            else   -> "${m}m"
        }
    }
}
