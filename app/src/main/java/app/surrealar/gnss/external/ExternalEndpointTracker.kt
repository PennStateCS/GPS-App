package app.surrealar.gnss.external

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Records which endpoint the external NMEA source asked for on each connection attempt (stabilization item 4), so the
 * app can tell which receiver the live external stream actually comes from. It only observes the app-side endpoint
 * lambda handed to geokit's TcpNmeaSource (GnssModule); the connection logic itself is unchanged.
 */
class ExternalEndpointTracker {

    /** One connection attempt: [seq] increases on every attempt; [host]/[port] null when nothing was configured. */
    data class Attempt(val seq: Long, val host: String?, val port: Int?)

    private val _attempts = MutableStateFlow(Attempt(0L, null, null))
    /** The latest connection attempt (seq 0 = none yet this process). */
    val attempts: StateFlow<Attempt> = _attempts.asStateFlow()

    /** Called with the endpoint the source is about to connect to; returns it unchanged. Never throws. */
    fun record(endpoint: Pair<String, Int>?): Pair<String, Int>? {
        _attempts.update { Attempt(it.seq + 1, endpoint?.first?.trim(), endpoint?.second) }
        return endpoint
    }
}
