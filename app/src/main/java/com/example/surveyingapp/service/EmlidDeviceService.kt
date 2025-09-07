package com.example.surveyingapp.service

import android.util.Log
import com.example.surveyingapp.domain.model.EmlidDeviceInfo
import com.example.surveyingapp.domain.model.SelfTest
import com.example.surveyingapp.domain.model.TestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

class EmlidDeviceService {

    companion object {
        private const val TAG = "EmlidDeviceService"
        private const val TIMEOUT_MS = 5000
        // Some Reach images expose /info, others /system/info (and sometimes both).
        private val INFO_PATHS = listOf("/info", "/system/info")
    }

    suspend fun getDeviceInfo(ipAddress: String, port: Int = 80): EmlidDeviceInfo? = withContext(Dispatchers.IO) {
        val base = "http://$ipAddress" + if (port == 80) "" else ":$port"
        for ((i, path) in INFO_PATHS.withIndex()) {
            val url = URL("$base$path")
            val json = fetchJson(url)
            if (json != null) {
                parseDeviceInfo(json, ipAddress)?.let { return@withContext it }
            }
            if (i == 0) Log.d(TAG, "Primary info path $path failed or unrecognized; trying fallback…")
        }
        null
    }

    private fun fetchJson(url: URL): String? {
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
                conn.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
                }
            } else {
                // drain error stream so the connection can be reused by the stack
                try { conn.errorStream?.close() } catch (_: Exception) {}
                Log.w(TAG, "GET ${url.path} -> HTTP $code (${url.host})")
                null
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Timeout contacting ${url.host}${url.path}", e); null
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused ${url.host}${url.path}", e); null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching ${url.host}${url.path}: ${e.message}", e); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Accepts multiple JSON shapes observed on Reach devices:
     *  A) { "device": {...}, "firmware": {...} }
     *  B) { "system": { "model": "...", "serial": "...", "version": "...", ... } }
     */
    private fun parseDeviceInfo(jsonResponse: String, ipAddress: String): EmlidDeviceInfo? = try {
        val root = JSONObject(jsonResponse)

        // Shape A (preferred)
        val device = root.optJSONObject("device")
        val firmware = root.optJSONObject("firmware")

        // Shape B (fallback)
        val system = root.optJSONObject("system")

        val deviceName =
            device?.optStringOrNull("name")
                ?: system?.optStringOrNull("name")
                ?: "Emlid Device"

        val firmwareVersion =
            firmware?.optStringOrNull("version_full")
                ?: firmware?.optStringOrNull("version")
                ?: system?.optStringOrNull("version")

        val serialNumber =
            device?.optStringOrNull("serial_number")
                ?: system?.optStringOrNull("serial")

        val model =
            device?.optStringOrNull("type")
                ?: system?.optStringOrNull("model")

        val uptime =
            device?.optStringOrNull("uptime")
                ?: system?.optStringOrNull("uptime")

        // Not generally exposed by /info
        val temperature: Double? = root.optJSONObject("sensors")?.optDoubleOrNull("temperature")
        val batteryLevel: Double? = root.optJSONObject("battery")?.optDoubleOrNull("state_of_charge")

        // Self tests (present on some images under device.self_tests)
        val selfTests = mutableListOf<SelfTest>()
        device?.optJSONObject("self_tests")?.let { testsJson ->
            val it = testsJson.keys()
            while (it.hasNext()) {
                val raw = it.next()
                val result = testsJson.optBooleanOrNull(raw)
                val status = when (result) {
                    true -> TestStatus.PASSED
                    false -> TestStatus.FAILED
                    null -> TestStatus.UNKNOWN
                }
                selfTests.add(SelfTest(raw.humanizeTestName(), status, /*detail*/ null))
            }
        }

        Log.d(TAG, "Parsed: name=$deviceName model=$model fw=$firmwareVersion serial=$serialNumber tests=${selfTests.size}")

        EmlidDeviceInfo(
            deviceName = deviceName,
            ipAddress = ipAddress,
            selfTests = selfTests,
            firmwareVersion = firmwareVersion,
            serialNumber = serialNumber,
            model = model,
            uptime = uptime,
            temperature = temperature,   // likely null on /info
            batteryLevel = batteryLevel  // likely null on /info
        )
    } catch (e: Exception) {
        Log.e(TAG, "Device info parse error: ${e.message}\nBody: $jsonResponse", e)
        null
    }
}

/* ---------------- JSON helpers ---------------- */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name)) null else optString(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    if (!has(name)) null else try { getBoolean(name) } catch (_: Exception) { null }

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name)) null else try {
        val v = getDouble(name)
        if (java.lang.Double.isNaN(v)) null else v
    } catch (_: Exception) { null }

/* ---------------- String helpers ---------------- */

private fun String.humanizeTestName(): String =
    this.replace('_', ' ')
        .replace(Regex("\\b(detected|present|ok)\\b", RegexOption.IGNORE_CASE), "")
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
