package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import java.util.ArrayDeque

/**
 * RECEIVER and CORRECTIONS lines that the connect loop doesn't already write: link up/down with how
 * long it was up, reconnect counts, time from connect to first data, and the corrections stream
 * (connected / lost, station ID). Never logs the base position or credentials. Thread-safe.
 *
 * State changes are [DiagEvents] lines (the older sentence is kept in parentheses):
 *  - `RECEIVER_LINK state=CONNECTING|CONNECTED|NO_DATA|DISCONNECTED|UNREACHABLE conn=cN …`
 *  - `RECEIVER_RECONNECTS count=n windowS=600 conn=cN` once per burst of drops
 *  - `CORRECTIONS_STATE state=OK|NONE via=receiver …`
 *
 * `conn=` names one connect attempt: a new id at every CONNECTING/RECONNECTING, reused on the lines
 * of that attempt (failure, link up, link down). A failed attempt is logged once per outage and kind
 * (UNREACHABLE: no TCP socket; NO_DATA: socket up, no NMEA), not on every retry.
 */
class ReceiverLinkTracker(private val sink: (level: Char, tag: String, message: String) -> Unit) {

    private val lock = Any()
    private var state: String? = null
    private var connectedAtMs: Long? = null
    private var awaitingFirstData = false
    private var attemptsSinceUp = 0
    var reconnects = 0
        private set
    var lastLinkState: String = "unknown"
        private set
    private var corrReceiving: Boolean? = null
    private var stationId: String? = null
    var correctionsSummary: String = "unknown"
        private set

    /** The current connect attempt's id (`c3`), or null before the first attempt. */
    @Volatile var connId: String? = null
        private set
    private var attemptStartMs = 0L
    /** Set when the TCP socket of the current attempt connected (see [onSocketConnected]); consumed at the attempt's end. */
    private var socketUpAtMs: Long? = null
    /** Failure kind already logged for the current outage (UNREACHABLE / NO_DATA), so retries don't repeat it. */
    private var outageLogged: String? = null
    @Volatile private var lastDataAtMs = 0L
    private val drops = ArrayDeque<Long>()
    private var burstLogged = false

    /** [name] is ReconnectingNmeaAdapter.ConnectionState.name (geokit-gnss, via ExternalAdapter). */
    fun onConnectionState(name: String, nowMs: Long) = synchronized(lock) {
        val before = state
        if (before == name) return@synchronized
        state = name
        lastLinkState = name
        when (name) {
            "CONNECTED" -> {
                connectedAtMs = nowMs; awaitingFirstData = true; socketUpAtMs = null
                sink('I', DiagTags.RECEIVER, DiagLine.of(DiagEvents.RECEIVER_LINK, "state" to "CONNECTED",
                    DiagEvents.K_CONN to connId, "attempts" to attemptsSinceUp.takeIf { it > 1 },
                    text = "External receiver link up"))
                attemptsSinceUp = 0
                outageLogged = null
            }
            "CONNECTING", "RECONNECTING" -> {
                attemptsSinceUp++
                if (name == "RECONNECTING") reconnects++
                linkDown(before, nowMs, "reconnecting", attempt = attemptsSinceUp)
                connId = DiagIds.next(DiagIds.CONN)
                attemptStartMs = nowMs
                // The first attempt of an outage only; later retries are folded into the failure line.
                if (attemptsSinceUp == 1) {
                    sink('I', DiagTags.RECEIVER, DiagLine.of(DiagEvents.RECEIVER_LINK, "state" to "CONNECTING",
                        DiagEvents.K_CONN to connId, "reason" to if (before == "ERROR" || before == "CONNECTED") "reconnect" else null))
                }
            }
            "ERROR" -> {
                if (before == "CONNECTED") linkDown(before, nowMs, "error") else attemptFailed(nowMs)
            }
            "DISCONNECTED", "CANCELLED" -> {
                linkDown(before, nowMs, name.lowercase())
                attemptsSinceUp = 0; outageLogged = null; socketUpAtMs = null
            }
        }
    }

    /** A connect attempt ended before data flowed: logged once per outage and kind. */
    private fun attemptFailed(nowMs: Long) {
        val socketAt = socketUpAtMs
        socketUpAtMs = null
        val kind = if (socketAt != null) "NO_DATA" else "UNREACHABLE"
        if (kind == outageLogged) return
        outageLogged = kind
        val afterS = ((nowMs - (socketAt ?: attemptStartMs)) / 1000).coerceAtLeast(0)
        sink('W', DiagTags.RECEIVER, DiagLine.of(DiagEvents.RECEIVER_LINK, "state" to kind, DiagEvents.K_CONN to connId,
            "afterS" to afterS, "attempts" to attemptsSinceUp,
            text = if (kind == "NO_DATA") "External receiver TCP connected but sent no NMEA" else "External receiver unreachable"))
    }

    private fun linkDown(before: String?, nowMs: Long, why: String, attempt: Int? = null) {
        if (before != "CONNECTED") return
        val upS = connectedAtMs?.let { (nowMs - it) / 1000 } ?: 0
        connectedAtMs = null; awaitingFirstData = false
        val userStop = why == "disconnected" || why == "cancelled"
        // Data had stopped for a while before the drop: the link stalled (socket up, no NMEA).
        val silentMs = lastDataAtMs.takeIf { it > 0L }?.let { nowMs - it }
        val noData = !userStop && silentMs != null && silentMs >= STALL_MS
        sink(if (userStop) 'I' else 'W', DiagTags.RECEIVER, DiagLine.of(DiagEvents.RECEIVER_LINK,
            "state" to if (noData) "NO_DATA" else "DISCONNECTED", DiagEvents.K_CONN to connId,
            "afterS" to if (noData) silentMs!! / 1000 else null, "upS" to upS,
            "reason" to if (why == "reconnecting") "reconnect" else why, "attempt" to attempt,
            text = "External receiver link down"))
        if (!userStop) recordDrop(nowMs)
    }

    /** RECEIVER_RECONNECTS once per burst: [BURST_DROPS] drops within [BURST_WINDOW_MS]. */
    private fun recordDrop(nowMs: Long) {
        while (drops.isNotEmpty() && nowMs - drops.first > BURST_WINDOW_MS) drops.removeFirst()
        if (drops.isEmpty()) burstLogged = false   // quiet for a whole window: a new burst may be logged
        drops.addLast(nowMs)
        if (drops.size >= BURST_DROPS && !burstLogged) {
            burstLogged = true
            sink('W', DiagTags.RECEIVER, DiagLine.of(DiagEvents.RECEIVER_RECONNECTS, "count" to drops.size,
                "windowS" to BURST_WINDOW_MS / 1000, DiagEvents.K_CONN to connId, text = "External receiver dropping repeatedly"))
        }
    }

    /** The current attempt's TCP socket connected (from the link log hook); decides UNREACHABLE vs NO_DATA. */
    fun onSocketConnected(nowMs: Long) = synchronized(lock) { if (socketUpAtMs == null) socketUpAtMs = nowMs }

    /** Called per NMEA line while the external receiver is the active source (one field write after the first). */
    fun onData(nowMs: Long) {
        lastDataAtMs = nowMs
        if (!awaitingFirstData) return
        synchronized(lock) {
            if (!awaitingFirstData) return
            awaitingFirstData = false
            sink('I', DiagTags.RECEIVER, "External receiver first data received delayMs=${connectedAtMs?.let { nowMs - it } ?: "unknown"}" +
                (connId?.let { " conn=$it" } ?: ""))
        }
    }

    fun onCorrections(c: ReceiverCorrections?) = synchronized(lock) {
        val receiving = c?.isReceiving
        if (receiving != corrReceiving) {
            val was = corrReceiving
            corrReceiving = receiving
            when {
                receiving == true -> sink('I', DiagTags.CORRECTIONS, DiagLine.of(DiagEvents.CORRECTIONS_STATE, "state" to "OK",
                    "via" to "receiver", "channel" to c.channel, "station" to c.stationId, DiagEvents.K_CONN to connId,
                    text = "Corrections stream connected"))
                receiving == false && was == true -> sink('W', DiagTags.CORRECTIONS, DiagLine.of(DiagEvents.CORRECTIONS_STATE,
                    "state" to "NONE", "via" to "receiver", DiagEvents.K_CONN to connId, text = "Corrections stream lost"))
                receiving == null && was == true -> sink('I', DiagTags.CORRECTIONS, "Corrections status no longer reported")
            }
        }
        val sid = c?.stationId
        if (sid != null && sid != stationId) {
            if (stationId != null) sink('W', DiagTags.CORRECTIONS, "Corrections base station $stationId → $sid")
            stationId = sid
        }
        correctionsSummary = when (receiving) {
            true -> "receiving" + (c.stationId?.let { " station=$it" } ?: "") + (c.ageS?.let { " age=${"%.1f".format(java.util.Locale.US, it)}s" } ?: "")
            false -> "not receiving"
            null -> "not reported by this receiver"
        }
    }

    companion object {
        /** No NMEA for this long before a drop = the link stalled (NO_DATA), not a plain disconnect. */
        const val STALL_MS = 10_000L
        const val BURST_DROPS = 3
        const val BURST_WINDOW_MS = 600_000L
    }
}
