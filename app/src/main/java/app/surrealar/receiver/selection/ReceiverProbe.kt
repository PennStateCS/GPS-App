package app.surrealar.receiver.selection

import app.surrealar.receiver.ReceiverHttpProbe
import app.surrealar.util.DevLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The connection test behind test-before-switch. [tcp] opens its own short-lived socket to the NMEA port (the live
 * stream, TcpNmeaSource, is not touched); [http] asks the Emlid HTTP API whether a receiver is there and is called
 * for Emlid profiles only (never for Generic NMEA receivers).
 */
interface ReceiverProbe {
    suspend fun tcp(host: String, port: Int): TcpProbeResult
    suspend fun http(host: String): Boolean
}

/** The real probes (moved unchanged from ReceiverSettingsController.connectAndReadTcpNmea / probeHttpReachable). */
object SocketReceiverProbe : ReceiverProbe {
    private const val TAG = "ReceiverProbe"

    /** Connects (4 s) and reads lines for up to 5 s: the first line starting with `$` is NMEA. */
    override suspend fun tcp(host: String, port: Int): TcpProbeResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        try {
            socket = Socket().apply { tcpNoDelay = true; keepAlive = true }
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
            } catch (e: Exception) {
                // Expected when the receiver is off / unreachable; the caller logs the probe outcome.
                DevLog.d(TAG) { "TCP probe connect failed port=$port: ${e.javaClass.simpleName}" }
                return@withContext TcpProbeResult.CONNECT_FAILED
            }

            runCatching { socket.soTimeout = 3000 }
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + 5000L
            var sawNonNmea = false
            while (System.currentTimeMillis() < deadline) {
                val line = withTimeoutOrNull(1000L) { runCatching { reader.readLine() }.getOrNull() }
                    ?: continue
                if (line.startsWith("$")) return@withContext TcpProbeResult.RECEIVING_NMEA
                if (line.isNotEmpty()) sawNonNmea = true
            }
            if (sawNonNmea) TcpProbeResult.RECEIVING_RTCM_OR_BIN else TcpProbeResult.CONNECTED_NO_DATA
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DevLog.d(TAG) { "TCP probe error port=$port: ${e.javaClass.simpleName}" }
            TcpProbeResult.CONNECT_FAILED
        } finally {
            runCatching { reader?.close() }
            runCatching { socket?.close() }
        }
    }

    /** Emlid only: a 200-range answer on `/info` or `/status` means a Reach is at [host]. */
    override suspend fun http(host: String): Boolean = ReceiverHttpProbe.statusApiReachable(host)
}
