package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections

/**
 * RECEIVER and CORRECTIONS lines that the connect loop doesn't already write: link up/down with how
 * long it was up, reconnect counts, time from connect to first data, and the corrections stream
 * (connected / lost, station ID). Never logs the base position or credentials. Thread-safe.
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

    /** [name] is ExternalAdapter.ConnectionState.name. */
    fun onConnectionState(name: String, nowMs: Long) = synchronized(lock) {
        val before = state
        if (before == name) return@synchronized
        state = name
        lastLinkState = name
        when (name) {
            "CONNECTED" -> {
                connectedAtMs = nowMs; awaitingFirstData = true
                sink('I', DiagTags.RECEIVER, "External receiver link up" + if (attemptsSinceUp > 1) " attempts=$attemptsSinceUp" else "")
                attemptsSinceUp = 0
            }
            "CONNECTING", "RECONNECTING" -> {
                attemptsSinceUp++
                if (name == "RECONNECTING") reconnects++
                linkDown(before, nowMs, "reconnecting attempt=$attemptsSinceUp")
            }
            "ERROR" -> linkDown(before, nowMs, "error")
            "DISCONNECTED", "CANCELLED" -> { linkDown(before, nowMs, name.lowercase()); attemptsSinceUp = 0 }
        }
    }

    private fun linkDown(before: String?, nowMs: Long, why: String) {
        if (before != "CONNECTED") return
        val upS = connectedAtMs?.let { (nowMs - it) / 1000 } ?: 0
        connectedAtMs = null; awaitingFirstData = false
        sink(if (why.startsWith("disconnected") || why.startsWith("cancelled")) 'I' else 'W', DiagTags.RECEIVER,
            "External receiver link down: $why upS=$upS")
    }

    /** Called per NMEA line while the external receiver is the active source (cheap after the first). */
    fun onData(nowMs: Long) {
        if (!awaitingFirstData) return
        synchronized(lock) {
            if (!awaitingFirstData) return
            awaitingFirstData = false
            sink('I', DiagTags.RECEIVER, "External receiver first data received delayMs=${connectedAtMs?.let { nowMs - it } ?: "unknown"}")
        }
    }

    fun onCorrections(c: ReceiverCorrections?) = synchronized(lock) {
        val receiving = c?.isReceiving
        if (receiving != corrReceiving) {
            val was = corrReceiving
            corrReceiving = receiving
            when {
                receiving == true -> sink('I', DiagTags.CORRECTIONS,
                    "Corrections stream connected" + (c.channel?.let { " channel=$it" } ?: "") + (c.stationId?.let { " station=$it" } ?: ""))
                receiving == false && was == true -> sink('W', DiagTags.CORRECTIONS, "Corrections stream lost")
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
}