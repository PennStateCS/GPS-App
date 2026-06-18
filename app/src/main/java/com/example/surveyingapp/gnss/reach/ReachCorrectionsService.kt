package com.example.surveyingapp.gnss.reach

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

private const val TAG = "ReachCorrectionsService"

/**
 * Correction status pushed from the RS2+ via socket.io "broadcast" events.
 *
 * Fields come from two broadcast names:
 *  - "navigation"    → aod, solution, satellites, baseline, base_position
 *  - "stream_status" → correctionInput (channel label, e.g. "NTRIP")
 *
 * All fields are nullable; the receiver may omit any of them depending on
 * firmware version or current fix state.
 */
data class ReachCorrectionsInfo(
    val isReceiving: Boolean,
    val channel: String?,
    val satellitesInView: Int?,
    val ageSeconds: Double?,
    val baselineKm: Double?,
    val baseLatDeg: Double?,
    val baseLonDeg: Double?,
    val baseAltM: Double?,
    val solution: String?
)

/**
 * Connects to the RS2+ socket.io endpoint and pushes live correction status
 * via [correctionsInfo].
 *
 * Usage:
 *   val svc = ReachCorrectionsService("192.168.2.174")
 *   svc.connect()
 *   // observe svc.correctionsInfo (StateFlow)
 *   svc.disconnect()
 *
 * The /battery REST endpoint is NOT touched here; keep using [ReachBatteryService] for that.
 */
class ReachCorrectionsService(private val host: String) {

    private val _correctionsInfo = MutableStateFlow<ReachCorrectionsInfo?>(null)
    val correctionsInfo: StateFlow<ReachCorrectionsInfo?> = _correctionsInfo.asStateFlow()

    private var socket: Socket? = null

    // Cached fields merged from different broadcast names so we never lose
    // navigation data when a stream_status message arrives (and vice-versa).
    private var cachedNav: NavSnapshot? = null
    private var cachedChannel: String? = null
    private var cachedIsReceiving: Boolean = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected to $host — ignoring duplicate connect()")
            return
        }
        try {
            val options = IO.Options().apply {
                transports = arrayOf("polling")   // Reach RS2+ supports polling transport
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 2_000
                reconnectionDelayMax = 15_000
            }
            val uri = URI.create("http://$host")
            val s = IO.socket(uri, options)

            s.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "socket.io connected to $host")
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.firstOrNull()?.toString() ?: "unknown"
                Log.e(TAG, "socket.io connect error: $msg")
            }
            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                Log.d(TAG, "socket.io disconnected: $reason")
            }
            s.on("broadcast", broadcastListener)

            s.connect()
            socket = s
            Log.d(TAG, "socket.io connecting to http://$host")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket.io connection to $host: ${e.message}", e)
        }
    }

    fun disconnect() {
        socket?.let { s ->
            s.off("broadcast", broadcastListener)
            s.disconnect()
            Log.d(TAG, "socket.io disconnected from $host")
        }
        socket = null
        cachedNav = null
        cachedChannel = null
        cachedIsReceiving = false
    }

    // ── Socket.IO listener ────────────────────────────────────────────────────

    private val broadcastListener = Emitter.Listener { args ->
        val payload = args.firstOrNull() as? JSONObject ?: run {
            Log.w(TAG, "broadcast: unexpected args type — ${args.firstOrNull()?.javaClass?.simpleName}")
            return@Listener
        }
        val name = payload.optString("name", "")
        val data = payload.optJSONObject("payload") ?: run {
            Log.w(TAG, "broadcast '$name': missing payload object")
            return@Listener
        }

        when (name) {
            "navigation"    -> handleNavigation(data)
            "stream_status" -> handleStreamStatus(data)
            else -> { /* ignore unrelated broadcasts */ }
        }
    }

    // ── Payload handlers ──────────────────────────────────────────────────────

    private fun handleNavigation(data: JSONObject) {
        Log.d(TAG, "navigation broadcast: ${data.toString().take(300)}")

        val aod      = data.optDoubleOrNull("aod")
        val solution = data.optString("solution", "").takeIf { it.isNotBlank() }
        val baseline = data.optDoubleOrNull("baseline")

        val satsObj = data.optJSONObject("satellites")
        val satsBase = satsObj?.optIntOrNull("base")

        val basePosObj   = data.optJSONObject("base_position")
        val coordsObj    = basePosObj?.optJSONObject("coordinates")
        val baseLat      = coordsObj?.optDoubleOrNull("lat")
        val baseLon      = coordsObj?.optDoubleOrNull("lon")
        val baseH        = coordsObj?.optDoubleOrNull("h")

        cachedNav = NavSnapshot(
            aod      = aod,
            solution = solution,
            baseline = baseline,
            satsBase = satsBase,
            baseLat  = baseLat,
            baseLon  = baseLon,
            baseH    = baseH
        )

        Log.d(TAG, "navigation parsed: aod=$aod sol=$solution baseline=$baseline " +
                "satsBase=$satsBase baseLat=$baseLat baseLon=$baseLon baseH=$baseH")

        pushUpdate()
    }

    private fun handleStreamStatus(data: JSONObject) {
        Log.d(TAG, "stream_status broadcast: ${data.toString().take(300)}")

        // correction_input may be an array; pick the first active entry
        val corrInput = data.optJSONArray("correction_input")
            ?: data.optJSONObject("correction_input")?.let { JSONArray().apply { put(it) } }

        if (corrInput != null && corrInput.length() > 0) {
            val entry = corrInput.optJSONObject(0)
            if (entry != null) {
                val connected = entry.optBoolean("connected", false)
                val type      = entry.optString("type", "").takeIf { it.isNotBlank() }
                               ?: entry.optString("name", "").takeIf { it.isNotBlank() }

                cachedIsReceiving = connected
                cachedChannel     = type

                Log.d(TAG, "stream_status parsed: connected=$connected channel=$type")
            }
        }

        pushUpdate()
    }

    // ── State merge & emit ────────────────────────────────────────────────────

    private fun pushUpdate() {
        val nav = cachedNav
        _correctionsInfo.value = ReachCorrectionsInfo(
            isReceiving      = cachedIsReceiving,
            channel          = cachedChannel,
            satellitesInView = nav?.satsBase,
            ageSeconds       = nav?.aod,
            baselineKm       = nav?.baseline,
            baseLatDeg       = nav?.baseLat,
            baseLonDeg       = nav?.baseLon,
            baseAltM         = nav?.baseH,
            solution         = nav?.solution
        )
    }

    // ── Internal snapshot ─────────────────────────────────────────────────────

    private data class NavSnapshot(
        val aod: Double?,
        val solution: String?,
        val baseline: Double?,
        val satsBase: Int?,
        val baseLat: Double?,
        val baseLon: Double?,
        val baseH: Double?
    )
}

// ── JSONObject helpers ────────────────────────────────────────────────────────

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    val v = optDouble(key, Double.NaN)
    return if (v.isFinite()) v else null
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    val v = optInt(key, Int.MIN_VALUE)
    return if (v != Int.MIN_VALUE) v else null
}
