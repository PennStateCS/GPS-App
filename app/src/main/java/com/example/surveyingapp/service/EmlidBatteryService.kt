package com.example.surveyingapp.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Simple HTTP client for Reach RS2+ battery endpoint. */
class EmlidBatteryService {
    data class BatteryStatus(
        val chargerStatus: String?,
        val currentA: Double?,
        val otg: Boolean?,
        val stateOfCharge: Int?,
        val temperatureC: Double?,
        val usbChargerCurrentA: Double?,
        val usbChargerVoltageV: Double?,
        val voltageV: Double?
    )

    companion object {
        private const val TAG = "EmlidBatteryService"
        private const val TIMEOUT_MS = 4000
        // Some firmware exposes /battery; others /status/battery. We'll try both.
        private val PATHS = listOf("/battery", "/status/battery")
    }

    /**
     * Fetch battery status from RS2+ HTTP API. Returns null on error.
     * - Does IO on Dispatchers.IO.
     * - Ensures connection is disconnected in all cases.
     * - Tries both /battery and /status/battery once each.
     */
    suspend fun getBattery(ipAddress: String, port: Int = 80): BatteryStatus? = withContext(Dispatchers.IO) {
        val base = if (port == 80) "http://$ipAddress" else "http://$ipAddress:$port"
        for ((idx, path) in PATHS.withIndex()) {
            val url = URL("$base$path")
            val result = runCatching { fetchBattery(url) }.getOrNull()
            if (result != null) return@withContext result
            // Only log the first failure; the second will log if it also fails
            if (idx == 0) Log.d(TAG, "Primary path $path failed; trying fallback if available…")
        }
        null
    }

    private fun fetchBattery(url: URL): BatteryStatus? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SurveyingApp")
                doInput = true
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
                }
                parseBattery(body)
            } else {
                // Drain error stream to allow socket reuse; ignore body
                try { conn.errorStream?.close() } catch (_: Exception) {}
                Log.w(TAG, "Battery HTTP $code from ${url.host}")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Battery fetch failed for ${url.host}: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseBattery(json: String): BatteryStatus? = try {
        val obj = JSONObject(json)
        BatteryStatus(
            chargerStatus = obj.optStringOrNull("charger_status"),
            currentA = obj.optDoubleOrNull("current"),
            otg = obj.optBooleanOrNull("otg"),
            stateOfCharge = obj.optSocPercent("state_of_charge")
                ?: obj.optSocPercent("soc")
                ?: obj.optSocPercent("stateOfCharge"),
            temperatureC = obj.optDoubleOrNull("temperature"),
            usbChargerCurrentA = obj.optDoubleOrNull("usb_charger_current"),
            usbChargerVoltageV = obj.optDoubleOrNull("usb_charger_voltage"),
            voltageV = obj.optDoubleOrNull("voltage")
        )
    } catch (e: Exception) {
        Log.d(TAG, "Battery JSON parse error: ${e.message}; body=$json")
        null
    }
}

/* ---------- JSON helpers (robust null/fallback parsing) ---------- */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name)) null else optString(name).trim().ifEmpty { null }

private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    if (!has(name)) null else try { getBoolean(name) } catch (_: Exception) { null }

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name)) null else try {
        // getDouble parses numeric strings too
        val v = getDouble(name)
        if (java.lang.Double.isNaN(v)) null else v
    } catch (_: Exception) { null }

/**
 * Robustly parse SOC to percent:
 * - int (0..100), double fraction (0..1), double percent (0..100),
 * - or string like "73" / "73%".
 */
private fun JSONObject.optSocPercent(name: String): Int? {
    if (!has(name)) return null
    return try {
        val any = get(name)
        val pct = when (any) {
            is Int    -> any.toDouble()
            is Long   -> any.toDouble()
            is Double -> any
            is Number -> any.toDouble()
            is String -> any.trim().removeSuffix("%").toDoubleOrNull() ?: return null
            else      -> return null
        }
        val normalized = if (pct <= 1.0) pct * 100.0 else pct
        normalized.toInt().coerceIn(0, 100)
    } catch (_: Exception) { null }
}
