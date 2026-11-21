package app.surrealar.receiver.emlid

import app.surrealar.gnss.log.GnssLog
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
internal class ReachCorrectionsService(private val host: String) {

    private val _correctionsInfo = MutableStateFlow<ReachCorrectionsInfo?>(null)
    val correctionsInfo: StateFlow<ReachCorrectionsInfo?> = _correctionsInfo.asStateFlow()

    private var socket: Socket? = null

    // Cached fields merged from different broadcast names so we never lose
    // navigation data when a stream_status message arrives (and vice-versa).
    private var cachedNav: NavSnapshot? = null
    private var cachedChannel: String? = null
    private var cachedIsReceiving: Boolean = false

    // Last values written to the diagnostics log (GnssLog.diagnostic) — used to log only state CHANGES (corrections
    // broadcasts arrive continuously; logging each one would flood the diagnostic export).
    private var lastLoggedSolution: String? = null
    private var lastLoggedStreamKey: String? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        if (socket?.connected() == true) {
            GnssLog.d(TAG, "Already connected to $host — ignoring duplicate connect()")
            return
        }
        GnssLog.diagnostic("CORRECTIONS", "connect attempt host=$host")
        try {
            val options = IO.Options().apply {
                transports = arrayOf("polling")   // RS2+ supports polling; skip websocket upgrade
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 2_000
                reconnectionDelayMax = 15_000
            }
            val uri = URI.create("http://$host")
            val s = IO.socket(uri, options)

            s.on(Socket.EVENT_CONNECT) {
                GnssLog.d(TAG, "socket.io connected to $host")
                GnssLog.diagnostic("CORRECTIONS", "socket connected host=$host")
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.firstOrNull()?.toString() ?: "unknown"
                GnssLog.w(TAG, "socket.io connect error (will retry): $msg")
                GnssLog.diagnosticWarn("CORRECTIONS", "socket connect error host=$host (will retry): $msg")
            }
            s.on("reconnect_failed") {
                GnssLog.w(TAG, "socket.io gave up connecting to $host after repeated failures — corrections info unavailable")
                GnssLog.diagnosticWarn("CORRECTIONS", "corrections unavailable — gave up connecting host=$host after retries")
            }
            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                GnssLog.d(TAG, "socket.io disconnected: $reason")
                GnssLog.diagnostic("CORRECTIONS", "socket disconnected host=$host reason=$reason")
            }
            s.on("broadcast", broadcastListener)

            s.connect()
            socket = s
            GnssLog.d(TAG, "socket.io connecting to http://$host")
        } catch (e: Exception) {
            GnssLog.e(TAG, "Failed to create socket.io connection to $host: ${e.message}", e)
            GnssLog.diagnosticError("CORRECTIONS",
                "failed to create socket host=$host: ${e.javaClass.simpleName} ${e.message}", e)
        }
    }

    fun disconnect() {
        socket?.let { s ->
            s.off("broadcast", broadcastListener)
            s.disconnect()
            GnssLog.d(TAG, "socket.io disconnected from $host")
            GnssLog.diagnostic("CORRECTIONS", "disconnect requested host=$host")
        }
        socket = null
        cachedNav = null
        cachedChannel = null
        cachedIsReceiving = false
        lastLoggedSolution = null
        lastLoggedStreamKey = null
    }

    // ── Socket.IO listener ────────────────────────────────────────────────────

    private val broadcastListener = Emitter.Listener { args ->
        val payload = args.firstOrNull() as? JSONObject ?: run {
            // Some socket.io broadcasts arrive as plain strings (handshake/other events) — expected.
            GnssLog.d(TAG, "broadcast: non-JSON args — ${args.firstOrNull()?.javaClass?.simpleName}")
            return@Listener
        }
        val name = payload.optString("name", "")
        val data = payload.optJSONObject("payload") ?: run {
            // Broadcasts we don't consume (e.g. time_marks) may omit a payload object — expected.
            GnssLog.d(TAG, "broadcast '$name': no payload object (ignored)")
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
        GnssLog.d(TAG, "navigation broadcast: ${data.toString().take(300)}")

        val nav = parseNavigation(data)
        cachedNav = nav

        GnssLog.d(TAG, "navigation parsed: aod=${nav.aod} sol=${nav.solution} baseline=${nav.baseline} " +
                "satsBase=${nav.satsBase} baseLat=${nav.baseLat} baseLon=${nav.baseLon} baseH=${nav.baseH}")

        // Log only when the RTK solution state changes (navigation broadcasts arrive continuously).
        // Base-station lat/lon are intentionally omitted to avoid coordinate spam in the export.
        if (nav.solution != lastLoggedSolution) {
            lastLoggedSolution = nav.solution
            GnssLog.diagnostic("CORRECTIONS", "rtk solution=${nav.solution ?: "none"} " +
                "ageS=${nav.aod ?: "?"} baselineKm=${nav.baseline ?: "?"} baseSats=${nav.satsBase ?: "?"}")
        }

        pushUpdate()
    }

    /** Pure parse of a `navigation` broadcast payload. `internal` for unit tests. */
    internal fun parseNavigation(data: JSONObject): NavSnapshot {
        val coordsObj = data.optJSONObject("base_position")?.optJSONObject("coordinates")
        return NavSnapshot(
            aod      = data.optDoubleOrNull("aod"),
            solution = data.optString("solution", "").takeIf { it.isNotBlank() },
            baseline = data.optDoubleOrNull("baseline"),
            satsBase = data.optJSONObject("satellites")?.optIntOrNull("base"),
            baseLat  = coordsObj?.optDoubleOrNull("lat"),
            baseLon  = coordsObj?.optDoubleOrNull("lon"),
            baseH    = coordsObj?.optDoubleOrNull("h"),
        )
    }

    private fun handleStreamStatus(data: JSONObject) {
        GnssLog.d(TAG, "stream_status broadcast: ${data.toString().take(300)}")

        val parsed = parseStreamStatus(data)
        if (parsed != null) {
            cachedIsReceiving = parsed.isReceiving
            cachedChannel     = parsed.channel

            GnssLog.d(TAG, "stream_status parsed: state=${parsed.state} connected=${parsed.isReceiving} channel=${parsed.channel}")

            // Log only on a change of the correction-input state/channel.
            val streamKey = "${parsed.state}|${parsed.isReceiving}|${parsed.channel}"
            if (streamKey != lastLoggedStreamKey) {
                lastLoggedStreamKey = streamKey
                GnssLog.diagnostic("CORRECTIONS",
                    "stream_status state=${parsed.state} receiving=${parsed.isReceiving} channel=${parsed.channel ?: "none"}")
            }
        }

        pushUpdate()
    }

    /** Parsed correction-input liveness from a `stream_status` broadcast. */
    internal data class StreamStatusParse(val state: String, val isReceiving: Boolean, val channel: String?)

    /** Pure parse of a `stream_status` payload; null when no correction-input entry is present. */
    internal fun parseStreamStatus(data: JSONObject): StreamStatusParse? {
        // correction_input may be an array; pick the first active entry.
        val corrInput = data.optJSONArray("correction_input")
            ?: data.optJSONObject("correction_input")?.let { JSONArray().apply { put(it) } }
            ?: return null
        if (corrInput.length() == 0) return null
        val entry = corrInput.optJSONObject(0) ?: return null

        // Emlid firmware reports correction-input liveness via "state" ("active" when RTCM is
        // flowing), NOT a boolean "connected". Treat state == "active" as receiving; keep the
        // legacy boolean as a fallback for any firmware that does send it.
        val state     = entry.optString("state", "")
        val connected = state.equals("active", ignoreCase = true) ||
                        entry.optBoolean("connected", false)
        val type      = entry.optString("type", "").takeIf { it.isNotBlank() }
                       ?: entry.optString("name", "").takeIf { it.isNotBlank() }
        // Friendlier label: prefer an explicit type/name; otherwise show "RTCM" while active
        // (the input carries RTCM messages) instead of a bare "Connected".
        val channel   = type ?: if (connected) "RTCM" else null
        return StreamStatusParse(state, connected, channel)
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

    internal data class NavSnapshot(
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
