package app.surrealar.receiver

import io.github.pennstatecs.geokit.receiver.emlid.ReachHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings' "is a receiver there?" check: whether the receiver's HTTP status API answers (Emlid
 * `/info`, then `/status`). A 200-range response is enough — the receiver is present even without a
 * GNSS fix. Phase 5: asked for Emlid profiles only (ReceiverConnectionController); a Generic NMEA receiver
 * never gets an Emlid HTTP request.
 */
object ReceiverHttpProbe {
    suspend fun statusApiReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        val client = ReachHttpClient(host, connectTimeoutMs = 3000, readTimeoutMs = 3000)
        try { client.get("/info"); true }
        catch (_: Exception) {
            try { client.get("/status"); true }
            catch (_: Exception) { false }
        }
    }
}