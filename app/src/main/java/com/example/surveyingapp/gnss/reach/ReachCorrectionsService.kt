package com.example.surveyingapp.gnss.reach

import android.util.Log
import org.json.JSONObject

data class ReachCorrectionsInfo(
    val isReceiving: Boolean,
    val channel: String?,           // NTRIP, LoRa, Serial, etc.
    val satellitesInView: Int?,     // Satellites in correction stream
    val ageSeconds: Double?,        // Age of corrections
    val baselineKm: Double?,        // Baseline distance to base station
    val baseLatDeg: Double?,        // Base station latitude
    val baseLonDeg: Double?,        // Base station longitude
    val baseAltM: Double?           // Base station altitude
)

private const val TAG = "ReachCorrectionsService"

/**
 * Fetches RTK correction status from RS2+.
 * Tries several known endpoint paths; returns null if none respond with usable JSON.
 */
class ReachCorrectionsService(private val client: ReachHttpClient) {

    private val endpoints = listOf(
        "/corrections",
        "/correction_input",
        "/rtk/status",
        "/rtk",
        "/position/correction",
        "/position"
    )

    suspend fun read(): ReachCorrectionsInfo? {
        for (path in endpoints) {
            val result = tryEndpoint(path)
            if (result != null) return result
        }
        Log.w(TAG, "All corrections endpoints returned no usable data")
        return null
    }

    private suspend fun tryEndpoint(path: String): ReachCorrectionsInfo? {
        val text = runCatching { client.get(path) }
            .onFailure { Log.d(TAG, "GET $path failed: ${it.message}") }
            .getOrNull() ?: return null

        Log.d(TAG, "GET $path → ${text.length} chars: ${text.take(200)}")

        val root = try { JSONObject(text) } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed for $path: ${e.message}")
            return null
        }

        // ── Candidate sub-objects across known firmware layouts ─────────────
        val corrections = root.optJSONObject("corrections") ?: root
        val rtkObj      = root.optJSONObject("rtk") ?: corrections.optJSONObject("rtk")
        val baseObj     = root.optJSONObject("base")
                       ?: corrections.optJSONObject("base")
                       ?: rtkObj?.optJSONObject("base")

        fun JSONObject.dbl(vararg keys: String): Double? {
            for (k in keys) {
                val v = optDouble(k, Double.NaN)
                if (v.isFinite()) return v
            }
            return null
        }

        // Detect whether any meaningful corrections field is present
        val hasData = corrections.has("receiving") || corrections.has("status") ||
                corrections.has("channel") || corrections.has("source") ||
                corrections.has("age") || rtkObj != null

        if (!hasData) {
            Log.d(TAG, "No corrections fields found at $path – keys: ${root.keys().asSequence().toList()}")
            return null
        }

        val isReceiving = corrections.optBoolean("receiving", false) ||
                (corrections.optString("status", "").contains("receiv", ignoreCase = true))

        val channel = corrections.optString("channel", "").takeIf { it.isNotBlank() }
                   ?: corrections.optString("source", "").takeIf { it.isNotBlank() }

        val satsInView = corrections.optInt("satellites", -1).takeIf { it >= 0 }
                      ?: corrections.optInt("sats_in_view", -1).takeIf { it >= 0 }

        val ageSeconds = corrections.dbl("age", "age_seconds")

        val baselineKm = corrections.dbl("baseline")
                      ?: rtkObj?.dbl("baseline_m")?.let { it / 1000.0 }

        val baseLat = baseObj?.dbl("latitude", "lat")
        val baseLon = baseObj?.dbl("longitude", "lon")
        val baseAlt = baseObj?.dbl("altitude", "alt")

        Log.d(TAG, "Parsed [$path]: receiving=$isReceiving channel=$channel age=$ageSeconds baseline=$baselineKm")

        return ReachCorrectionsInfo(
            isReceiving     = isReceiving,
            channel         = channel,
            satellitesInView = satsInView,
            ageSeconds      = ageSeconds,
            baselineKm      = baselineKm,
            baseLatDeg      = baseLat,
            baseLonDeg      = baseLon,
            baseAltM        = baseAlt
        )
    }
}
