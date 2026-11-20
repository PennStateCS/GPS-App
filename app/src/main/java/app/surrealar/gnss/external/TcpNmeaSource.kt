package app.surrealar.gnss.external

import android.util.Log
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import app.surrealar.gnss.bus.adapters.GsvMessage
import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.bus.adapters.NmeaSource
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.gnss.diagnostics.DiagnosticsService
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.nmea.parse.NmeaRegistry
import app.surrealar.gnss.source.SourceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * [NmeaSource] that reads NMEA sentences from an external GNSS receiver over TCP.
 *
 * Connection parameters (host/port) are read from `SettingsRepository`.
 *
 * **Future**: [SourceSettings] is wired for multi-profile support but profiles are not yet
 * populated from UI. When profile UI is implemented, this class can switch to using
 * [SourceSettings.getConnectionInfo] instead of `SettingsRepository` for connection parameters.
 *
 * This source makes a **single connection attempt** and streams until the connection drops or
 * [stop] is called. Reconnection logic (with exponential back-off) is the responsibility of
 * [ExternalAdapter], which owns the lifecycle of this source.
 *
 * Sentence parsing and fix fusion are delegated to [NmeaFuser]; the shared [NmeaRegistry]
 * instance from DI is reused to avoid redundant parser allocations.
 *
 * This implementation is intentionally provider-agnostic: any TCP NMEA source (RS2+, Reach,
 * u-blox, etc.) will work — the [Provider] tag stamped onto emitted [Fix] objects is the only
 * RS2-specific coupling, and it can be changed at construction time.
 */
class TcpNmeaSource(
    private val scope: CoroutineScope,
    private val settingsRepository: ExternalReceiverSettingsRepository,
    @Suppress("UNUSED_PARAMETER") sourceSettings: SourceSettings,  // Reserved for future profile support
    registry: NmeaRegistry,
    private val provider: Provider = Provider.RS2_EXTERNAL,
    private val diagnostics: DiagnosticsService? = null
) : NmeaSource {

    companion object {
        private const val TAG = "TcpNmeaSource"
        private const val SOCKET_TIMEOUT_MS = 5_000
    }

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _rawNmea = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun parsedFixes(): SharedFlow<Fix> = _fixes.asSharedFlow()
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv.asSharedFlow()
    override fun rawNmeaEvents(): SharedFlow<Unit> = _rawNmea.asSharedFlow()
    override fun nmeaTimingStats() = fuser.timingStats
    override fun nmeaCustomStats() = fuser.nmeaCustomStats

    private val fuser = NmeaFuser(
        provider = provider,
        registry = registry,
        // tryEmit (never fails: DROP_OLDEST buffers) keeps fixes in receive order. A coroutine per
        // fix on the multi-threaded app scope could deliver an older fix after a newer one.
        onFix    = { fix -> _fixes.tryEmit(fix) },
        onGsv    = { gsv -> _gsv.tryEmit(gsv) }
    )

    /** Debug builds: periodic raw-sentence samples from the receiver (logcat only). Reader thread only. */
    private val debugSampler = app.surrealar.gnss.debug.NmeaDebugSampler("nmea-ext")

    private var connectionJob: Job? = null
    /** Open socket, so [stop] can close it and unblock a pending readLine() immediately. */
    @Volatile private var activeSocket: Socket? = null

    /**
     * True while a connection attempt or open stream is in progress. False once the remote closed the
     * socket, the connect failed, or [stop] ran — lets [ExternalAdapter] reconnect right away instead
     * of waiting for its no-data timeout.
     */
    override fun isConnectionActive(): Boolean = connectionJob?.isActive == true
    @Volatile private var started = false
    @Volatile private var firstDataLogged = false

    /**
     * Reads host/port from `SettingsRepository` and initiates a single TCP connection attempt.
     * The connection streams lines until the remote closes it, a read error occurs, or [stop]
     * is called. Reconnection with exponential back-off is the responsibility of [ExternalAdapter].
     */
    override fun start() {
        if (started) return
        Log.d(TAG, "Starting TCP NMEA source (single-connect)")
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                val host = settingsRepository.externalTcpHost.first()
                val port = settingsRepository.externalTcpPort.first()

                if (host.isNullOrBlank() || port == null) {
                    Log.w(TAG, "No TCP host/port configured")
                    DiagnosticsLogger.w("Receiver", "TCP NMEA: no host/port configured")
                    return@launch
                }

                started = true
                firstDataLogged = false
                Log.d(TAG, "Connecting to $host:$port")
                DiagnosticsLogger.i("Receiver", "Connecting to $host:$port")
                connectAndRead(host, port)

            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // stop() closes the socket, which surfaces here as "Socket closed" — not an error.
                if (!isActive) return@launch
                Log.e(TAG, "Connection error: ${e.message}")
                DiagnosticsLogger.e("Receiver", "TCP connection error: ${e.message}")
                started = false
                // Let ExternalAdapter's reconnect loop handle the retry
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping TCP NMEA source")
        started = false
        firstDataLogged = false
        connectionJob?.cancel()
        connectionJob = null
        // Closing the socket unblocks readLine() now, so the old reader can't keep feeding the
        // fuser (or hold a receiver client slot) for up to SOCKET_TIMEOUT_MS after a stop/reconnect.
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
        fuser.reset()
        /*
         * Drop the replay=1 buffered fix so a later reconnect doesn't immediately
         * re-deliver the last fix from the previous session to a fresh collector.
         */
        _fixes.resetReplayCache()
    }

    private suspend fun connectAndRead(host: String, port: Int) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        try {
            socket = Socket().also { activeSocket = it }.apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }
            Log.d(TAG, "Connected to $host:$port")
            DiagnosticsLogger.i("Receiver", "TCP socket connected to $host:$port")
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1024)

            while (coroutineContext.isActive) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "Connection closed by remote")
                        DiagnosticsLogger.w("Receiver", "TCP connection closed by remote ($host:$port)")
                        break
                    }
                    val clean = line.trim()
                    if (clean.isNotEmpty() && clean[0] == '$') {
                        if (!firstDataLogged) {
                            firstDataLogged = true
                            DiagnosticsLogger.i("Receiver", "First NMEA sentence received from $host:$port")
                        }
                        diagnostics?.recordLine(clean)
                        _rawNmea.tryEmit(Unit)
                        fuser.accept(clean)
                        debugSampler.accept(clean) { "$host:$port" }
                    }
                } catch (_: SocketTimeoutException) {
                    if (!coroutineContext.isActive) break
                }
            }
        } finally {
            // Close directly (already on Dispatchers.IO). The previous withContext(...) threw
            // immediately in a cancelled coroutine without running its block, leaking the socket
            // on every stop/reconnect.
            runCatching { reader?.close() }
            runCatching { socket?.close() }
            if (activeSocket === socket) activeSocket = null
            Log.d(TAG, "Disconnected from $host:$port")
            DiagnosticsLogger.i("Receiver", "TCP disconnected from $host:$port")
        }
    }
}
