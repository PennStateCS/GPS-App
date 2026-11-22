package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.log.GnssLog
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
 *
 * **Outages.** The socket reconnects indefinitely (2 s backoff growing to 15 s), so the status comes
 * back after a receiver reboot, battery swap or a spell out of Wi-Fi range. While the link is down
 * the status is unknown: [correctionsInfo] is cleared on disconnect, and [expireIfStale] clears it
 * when no broadcast has arrived for [STALE_AFTER_MS] (the receiver normally broadcasts every second).
 *
 * Socket callbacks arrive on socket.io's own thread; [expireIfStale] is called from the device's
 * coroutine. All state is guarded by one lock.
 */
internal class ReachCorrectionsService(
    private val host: String,
    /** Monotonic clock (ms) for broadcast age. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Creates the socket; replaced by a fake in tests. */
    private val socketFactory: (URI, IO.Options) -> CorrectionsSocket = SocketIoCorrectionsSocket::create,
) {

    private val _correctionsInfo = MutableStateFlow<ReachCorrectionsInfo?>(null)
    val correctionsInfo: StateFlow<ReachCorrectionsInfo?> = _correctionsInfo.asStateFlow()

    private val lock = Any()
    private var socket: CorrectionsSocket? = null

    // Cached fields merged from different broadcast names so we never lose
    // navigation data when a stream_status message arrives (and vice-versa).
    private var cachedNav: NavSnapshot? = null
    private var cachedChannel: String? = null
    private var cachedIsReceiving: Boolean = false
    private var lastBroadcastAtMs: Long? = null

    // Last values written to the diagnostics log (GnssLog.diagnostic) — used to log only state CHANGES (corrections
    // broadcasts arrive continuously; logging each one would flood the diagnostic export).
    private var lastLoggedSolution: String? = null
    private var lastLoggedStreamKey: String? = null

    // Connect-error throttling: socket.io retries every 2-15 s for as long as the receiver is unreachable,
    // so only the first error is logged, then one "still failing" line per [ERROR_LOG_INTERVAL_MS].
    private var connectErrors = 0
    // Stall recovery: any socket traffic (broadcasts every few seconds, connect, connect_error) counts as
    // activity. A long-poll can hang with no error at all; after [STALL_RESTART_MS] of silence the socket
    // is replaced (seen on an RS2+: broadcasts stopped and never resumed until the app restarted).
    @Volatile private var lastActivityAtMs = 0L
    private var lastRestartAtMs = Long.MIN_VALUE / 2
    private var firstErrorAtMs = 0L
    private var lastErrorLogAtMs = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        if (synchronized(lock) { socket }?.connected() == true) {
            GnssLog.d(TAG, "Already connected to $host — ignoring duplicate connect()")
            return
        }
        // Never leave an old socket reconnecting in the background (it would retry forever).
        closeSocket()
        GnssLog.diagnostic("CORRECTIONS", "connect attempt host=$host")
        try {
            val options = IO.Options().apply {
                // Own Manager per socket: socket.io 1.x caches Managers by URL otherwise, and a closed
                // socket's Manager would stay in that cache for the life of the process.
                forceNew = true
                transports = arrayOf("polling")   // RS2+ supports polling; skip websocket upgrade
                reconnection = true
                // Never give up: a reboot or a minute out of range must not end corrections status
                // for the rest of the session.
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 2_000
                reconnectionDelayMax = 15_000
            }
            val uri = URI.create("http://$host")
            val s = socketFactory(uri, options)

            s.on(Socket.EVENT_CONNECT) {
                lastActivityAtMs = nowMs()
                val failed = synchronized(lock) { connectErrors.also { connectErrors = 0 } }
                GnssLog.d(TAG, "socket.io connected to $host")
                GnssLog.diagnostic("CORRECTIONS", "socket connected host=$host" +
                    if (failed > 0) " after $failed failed attempts" else "")
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args.firstOrNull()?.toString() ?: "unknown") }
            s.on("reconnect_failed") {
                GnssLog.w(TAG, "socket.io gave up connecting to $host after repeated failures — corrections info unavailable")
                GnssLog.diagnosticWarn("CORRECTIONS", "corrections unavailable — gave up connecting host=$host after retries")
                clearStatus()
            }
            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                GnssLog.d(TAG, "socket.io disconnected: $reason")
                GnssLog.diagnostic("CORRECTIONS", "socket disconnected host=$host reason=$reason")
                clearStatus()        // link down → status unknown until broadcasts resume
            }
            s.on("broadcast") { args -> onBroadcast(args) }

            synchronized(lock) { socket = s; lastActivityAtMs = nowMs() }
            s.connect()
            GnssLog.d(TAG, "socket.io connecting to http://$host")
        } catch (e: Exception) {
            GnssLog.e(TAG, "Failed to create socket.io connection to $host: ${e.message}", e)
            GnssLog.diagnosticError("CORRECTIONS",
                "failed to create socket host=$host: ${e.javaClass.simpleName} ${e.message}", e)
        }
    }

    /** First error logged at once; while it keeps failing, one summary line per minute. */
    private fun onConnectError(msg: String) {
        val now = nowMs()
        lastActivityAtMs = now
        val line: String? = synchronized(lock) {
            connectErrors++
            when {
                connectErrors == 1 -> {
                    firstErrorAtMs = now; lastErrorLogAtMs = now
                    "socket connect error host=$host (will retry): $msg"
                }
                now - lastErrorLogAtMs >= ERROR_LOG_INTERVAL_MS -> {
                    lastErrorLogAtMs = now
                    "socket still failing host=$host: $connectErrors attempts in ${(now - firstErrorAtMs) / 1000}s, last error: $msg"
                }
                else -> null
            }
        }
        if (line != null) {
            GnssLog.w(TAG, line)
            GnssLog.diagnosticWarn("CORRECTIONS", line)
        }
    }

    /** Detaches every listener and closes the current socket (its Manager closes with it). */
    private fun closeSocket(): Boolean {
        val old = synchronized(lock) { socket.also { socket = null } } ?: return false
        runCatching { old.offAll(); old.disconnect() }
        return true
    }

    fun disconnect() {
        synchronized(lock) { connectErrors = 0 }
        if (closeSocket()) {
            GnssLog.d(TAG, "socket.io disconnected from $host")
            GnssLog.diagnostic("CORRECTIONS", "disconnect requested host=$host")
        }
        synchronized(lock) {
            cachedNav = null
            cachedChannel = null
            cachedIsReceiving = false
            lastBroadcastAtMs = null
            lastLoggedSolution = null
            lastLoggedStreamKey = null
        }
    }

    /**
     * Clears the status when no broadcast has arrived for [STALE_AFTER_MS] (a half-open socket or a
     * receiver that stopped broadcasting). The next broadcast restores it. Call about once a second.
     */
    fun expireIfStale() {
        synchronized(lock) { expireStatusLocked() }
        restartIfStalled()
    }

    private fun expireStatusLocked() {
        if (_correctionsInfo.value == null) return
        val last = lastBroadcastAtMs ?: return clearStatusLocked()
        if (nowMs() - last > STALE_AFTER_MS) {
            GnssLog.diagnostic("CORRECTIONS", "no broadcast for ${STALE_AFTER_MS / 1000}s host=$host — status cleared")
            clearStatusLocked()
        }
    }

    /** Replaces a socket that has been completely silent for [STALL_RESTART_MS] (at most once per interval). */
    private fun restartIfStalled() {
        val now = nowMs()
        val stalled = synchronized(lock) {
            (socket != null && now - lastActivityAtMs > STALL_RESTART_MS && now - lastRestartAtMs > STALL_RESTART_MS)
                .also { if (it) lastRestartAtMs = now }
        }
        if (!stalled) return
        GnssLog.diagnosticWarn("CORRECTIONS", "socket silent for ${(now - lastActivityAtMs) / 1000}s host=$host — reconnecting")
        disconnect()
        connect()
    }

    private fun clearStatus() = synchronized(lock) { clearStatusLocked() }

    private fun clearStatusLocked() {
        cachedNav = null
        cachedChannel = null
        cachedIsReceiving = false
        lastLoggedSolution = null
        lastLoggedStreamKey = null
        _correctionsInfo.value = null
    }

    // ── Socket.IO listener ────────────────────────────────────────────────────

    private fun onBroadcast(args: Array<out Any?>) {
        val payload = args.firstOrNull() as? JSONObject ?: run {
            // Some socket.io broadcasts arrive as plain strings (handshake/other events) — expected.
            GnssLog.d(TAG) { "broadcast: non-JSON args — ${args.firstOrNull()?.javaClass?.simpleName}" }
            return
        }
        lastActivityAtMs = nowMs()
        val name = payload.optString("name", "")
        val data = payload.optJSONObject("payload") ?: run {
            // Broadcasts we don't consume (e.g. time_marks) may omit a payload object — expected.
            GnssLog.d(TAG) { "broadcast '$name': no payload object (ignored)" }
            return
        }

        synchronized(lock) {
            when (name) {
                "navigation"    -> { lastBroadcastAtMs = nowMs(); handleNavigation(data) }
                "stream_status" -> { lastBroadcastAtMs = nowMs(); handleStreamStatus(data) }
                else -> { /* ignore unrelated broadcasts */ }
            }
        }
    }

    // ── Payload handlers ──────────────────────────────────────────────────────

    private fun handleNavigation(data: JSONObject) {
        // Per-broadcast detail (raw JSON, base position) only in debug builds, at most every 15 s.
        GnssLog.dEvery(TAG, "corrections.nav.raw") { "navigation broadcast: ${data.toString().take(300)}" }

        val nav = parseNavigation(data)
        cachedNav = nav

        GnssLog.dEvery(TAG, "corrections.nav.parsed") {
            "navigation parsed: aod=${nav.aod} sol=${nav.solution} baseline=${nav.baseline} " +
                "satsBase=${nav.satsBase} baseLat=${nav.baseLat} baseLon=${nav.baseLon} baseH=${nav.baseH}"
        }

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
        GnssLog.dEvery(TAG, "corrections.stream.raw") { "stream_status broadcast: ${data.toString().take(300)}" }

        val parsed = parseStreamStatus(data)
        if (parsed != null) {
            cachedIsReceiving = parsed.isReceiving
            cachedChannel     = parsed.channel

            GnssLog.dEvery(TAG, "corrections.stream.parsed") {
                "stream_status parsed: state=${parsed.state} connected=${parsed.isReceiving} channel=${parsed.channel}"
            }

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

    companion object {
        /** No broadcast for this long → status unknown (the receiver broadcasts about once a second). */
        const val STALE_AFTER_MS: Long = 10_000L
        /** While connecting keeps failing, at most one log line per this interval. */
        const val ERROR_LOG_INTERVAL_MS: Long = 60_000L
        /** No socket traffic at all for this long → replace the socket. */
        const val STALL_RESTART_MS: Long = 30_000L
    }
}

/** The bit of a socket.io client the corrections service uses (a seam for tests). */
internal interface CorrectionsSocket {
    fun on(event: String, listener: (Array<out Any?>) -> Unit)
    fun off(event: String)
    /** Removes every listener (default: the one event this service listens to besides lifecycle ones). */
    fun offAll() { off("broadcast") }
    fun connect()
    fun disconnect()
    fun connected(): Boolean
}

/** [CorrectionsSocket] over the real socket.io client. */
internal class SocketIoCorrectionsSocket private constructor(private val socket: Socket) : CorrectionsSocket {
    override fun on(event: String, listener: (Array<out Any?>) -> Unit) {
        socket.on(event, Emitter.Listener { args -> listener(args) })
    }
    override fun off(event: String) { socket.off(event) }
    override fun offAll() { socket.off() }
    override fun connect() { socket.connect() }
    override fun disconnect() { socket.disconnect() }
    override fun connected(): Boolean = socket.connected()

    companion object {
        fun create(uri: URI, options: IO.Options): CorrectionsSocket = SocketIoCorrectionsSocket(IO.socket(uri, options))
    }
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
