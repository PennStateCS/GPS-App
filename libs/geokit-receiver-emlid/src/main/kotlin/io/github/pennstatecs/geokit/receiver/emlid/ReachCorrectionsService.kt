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
    // so only the first error is logged, then one "still failing" line per [ERROR_LOG_INTERVAL_MS]. The
    // count is reset only once the link really works (a broadcast arrived) or on an intentional
    // disconnect — not on a bare socket "connect" or a stall restart, which flap while the receiver is
    // half-reachable and used to make every error a "first" error.
    private var connectErrors = 0
    // Link state last written to the log ("connecting", "connected", "disconnected"), so connect /
    // disconnect lines are written on a change only; and whether a broadcast arrived since the connect.
    private var loggedLink: String? = null
    private var dataSinceConnect = false
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
        if (changeLink("connecting")) GnssLog.diagnostic("CORRECTIONS", "connect attempt host=$host")
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
                val failed = synchronized(lock) { dataSinceConnect = false; connectErrors }
                if (changeLink("connected")) {
                    GnssLog.diagnostic("CORRECTIONS", "socket connected host=$host" +
                        if (failed > 0) " after $failed failed attempts" else "")
                } else {
                    GnssLog.d(TAG) { "socket.io connected again to $host (not logged: link state unchanged)" }
                }
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args.firstOrNull()?.toString() ?: "unknown") }
            s.on("reconnect_failed") {
                GnssLog.diagnosticWarn("CORRECTIONS", "corrections unavailable — gave up connecting host=$host after retries")
                clearStatus()
            }
            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                // Logged only when the link had really worked (data arrived); a socket that connects and
                // drops without ever delivering data is a flapping connect, covered by the error lines.
                val wasUp = synchronized(lock) { dataSinceConnect }
                if (wasUp && changeLink("disconnected")) {
                    GnssLog.diagnostic("CORRECTIONS", "socket disconnected host=$host reason=$reason")
                } else {
                    GnssLog.d(TAG) { "socket.io disconnected: $reason" }
                }
                clearStatus()        // link down → status unknown until broadcasts resume
            }
            s.on("broadcast") { args -> onBroadcast(args) }

            synchronized(lock) { socket = s; lastActivityAtMs = nowMs() }
            s.connect()
            GnssLog.d(TAG) { "socket.io connecting to http://$host" }
        } catch (e: Exception) {
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
        if (line != null) GnssLog.diagnosticWarn("CORRECTIONS", line)
    }

    /** Records [state] as the logged link state; true when it changed (the caller then writes its line). */
    private fun changeLink(state: String): Boolean = synchronized(lock) {
        (loggedLink != state).also { loggedLink = state }
    }

    /** Detaches every listener and closes the current socket (its Manager closes with it). */
    private fun closeSocket(): Boolean {
        val old = synchronized(lock) { socket.also { socket = null } } ?: return false
        runCatching { old.offAll(); old.disconnect() }
        return true
    }

    /** Intentional disconnect (device stopped): the next connect starts a fresh error count and log state. */
    fun disconnect() {
        if (closeSocket()) GnssLog.diagnostic("CORRECTIONS", "disconnect requested host=$host")
        synchronized(lock) {
            connectErrors = 0
            loggedLink = null
            dataSinceConnect = false
        }
        resetCaches()
    }

    /** Closes the socket and clears the cached status without resetting the error count (stall restart). */
    private fun closeForRestart() {
        closeSocket()
        resetCaches()
    }

    private fun resetCaches() {
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
        // Not an intentional disconnect: keep the error count and logged link state, so a receiver that
        // stays unreachable doesn't log a fresh "first error" / "connect attempt" every restart.
        closeForRestart()
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
        synchronized(lock) {
            // Data arrived: the link really works, so a later failure is a new outage with a fresh count.
            dataSinceConnect = true
            connectErrors = 0
        }
        val name = payload.optString("name", "")
        val data = payload.optJSONObject("payload") ?: run {
            // Broadcasts we don't consume (e.g. time_marks) may omit a payload object — expected.
            GnssLog.every("corrections.nopayload.$name", "corrections", 60_000L) { "broadcast '$name': no payload object (ignored)" }
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
        // Per-broadcast detail (raw JSON, rover/base position) goes to the GNSS debug trace — the only
        // place coordinates may appear ("GnssDebug [corrections] …", debug builds, at most every 15 s).
        GnssLog.every("corrections.nav.raw", "corrections") { "navigation broadcast: ${data.toString().take(300)}" }

        val nav = parseNavigation(data)
        cachedNav = nav

        GnssLog.every("corrections.nav.parsed", "corrections") {
            "navigation parsed: aod=${nav.aod} sol=${nav.solution} baseline=${nav.baseline} " +
                "satsBase=${nav.satsBase} baseLat=${nav.baseLat} baseLon=${nav.baseLon} baseH=${nav.baseH}"
        }

        // Log only when the RTK solution state changes (navigation broadcasts arrive continuously).
        // Base-station lat/lon are intentionally omitted to avoid coordinate spam in the export.
        if (nav.solution != lastLoggedSolution) {
            lastLoggedSolution = nav.solution
            GnssLog.diagnostic("CORRECTIONS", "rtk solution=${nav.solution ?: "none"} " +
                "ageS=${nav.aod ?: "?"} baselineKm=${formatBaselineKm(nav.baseline)} baseSats=${nav.satsBase ?: "?"}")
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
        GnssLog.every("corrections.stream.raw", "corrections") { "stream_status broadcast: ${data.toString().take(300)}" }

        val parsed = parseStreamStatus(data)
        if (parsed != null) {
            cachedIsReceiving = parsed.isReceiving
            cachedChannel     = parsed.channel

            GnssLog.every("corrections.stream.parsed", "corrections") {
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
            baselineM        = nav?.baseline,
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
        /** Distance to the base in metres (the Reach sends metres, e.g. 4594.8 for a 4.6 km baseline). */
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

/** A Reach baseline (metres) for the log, in km with two decimals ("4.59"), or "?" when unknown. */
internal fun formatBaselineKm(baselineM: Double?): String =
    baselineM?.let { String.format(java.util.Locale.US, "%.2f", it / 1000.0) } ?: "?"
