package app.surrealar.receiver

import app.surrealar.gnss.external.ReachHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings' "is a receiver there?" check: whether the receiver's HTTP status API answers (Emlid
 * `/info`, then `/status`). A 200-range response is enough — the receiver is present even without a
 * GNSS fix. Runs for every profile, as before; receivers without that API simply answer false.
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