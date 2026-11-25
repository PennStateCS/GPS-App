package app.surrealar.receiver.selection

import app.surrealar.receiver.ReceiverHttpProbe
import app.surrealar.util.DevLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * Connects (4 s) and reads lines for up to 5 s: the first line starting with `$` is NMEA. Cancellable: cancelling
     * the caller closes the socket at once, which ends a blocking connect or read (a receiver that accepts and never
     * answers is not held for the full window).
     */
    override suspend fun tcp(host: String, port: Int): TcpProbeResult = coroutineScope {
        val socket = Socket()
        // Closes the socket when this probe is cancelled (blocking socket calls ignore coroutine cancellation).
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { socket.close() } }
        }
        try {
            withContext(Dispatchers.IO) { sniff(socket, host, port) }
        } finally {
            closer.cancel()
            runCatching { socket.close() }
        }
    }

    private suspend fun sniff(socket: Socket, host: String, port: Int): TcpProbeResult = coroutineScope {
        var reader: BufferedReader? = null
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
            } catch (e: Exception) {
                ensureActive()
                // Expected when the receiver is off / unreachable; the caller logs the probe outcome.
                DevLog.d(TAG) { "TCP probe connect failed port=$port: ${e.javaClass.simpleName}" }
                return@coroutineScope TcpProbeResult.CONNECT_FAILED
            }

            runCatching { socket.soTimeout = 3000 }
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + 5000L
            var sawNonNmea = false
            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                val line = withTimeoutOrNull(1000L) { runCatching { reader.readLine() }.getOrNull() }
                    ?: continue
                if (line.startsWith("$")) return@coroutineScope TcpProbeResult.RECEIVING_NMEA
                if (line.isNotEmpty()) sawNonNmea = true
            }
            if (sawNonNmea) TcpProbeResult.RECEIVING_RTCM_OR_BIN else TcpProbeResult.CONNECTED_NO_DATA
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive()
            DevLog.d(TAG) { "TCP probe error port=$port: ${e.javaClass.simpleName}" }
            TcpProbeResult.CONNECT_FAILED
        } finally {
            runCatching { reader?.close() }
        }
    }

    /** Emlid only: a 200-range answer on `/info` or `/status` means a Reach is at [host]. */
    override suspend fun http(host: String): Boolean = ReceiverHttpProbe.statusApiReachable(host)
}
