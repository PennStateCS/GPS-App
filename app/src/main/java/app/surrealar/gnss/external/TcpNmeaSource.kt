package app.surrealar.gnss.external

import android.util.Log
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.gnss.diagnostics.DiagnosticsService
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import app.surrealar.gnss.source.SourceSettings
import kotlinx.coroutines.asContextElement
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
    private val provider: Provider = Provider.EXTERNAL_TCP,
    private val diagnostics: DiagnosticsService? = null,
    /** Socket read timeout; [DEAD_LINK_TIMEOUTS] in a row with no data ends the connection. Tests shorten it. */
    private val readTimeoutMs: Int = SOCKET_TIMEOUT_MS,
) : NmeaSource {

    companion object {
        private const val TAG = "TcpNmeaSource"
        private const val SOCKET_TIMEOUT_MS = 5_000
        /** How often the fuser's GST hold cap (300 ms) is checked while reading. */
        private const val HOLD_TICK_MS = 100L
        /**
         * Consecutive read timeouts with no line received that mean the link is dead (half-open socket:
         * Wi-Fi dropped, receiver powered off without closing). 2 × 5 s ≈ 10 s on a ≥1 Hz stream, instead
         * of the adapter's 30 s no-data timeout. Ending the connection lets ExternalAdapter's existing
         * reconnect/backoff run.
         */
        private const val DEAD_LINK_TIMEOUTS = 2
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
        // Dropped if the connection that parsed it has been stopped (see [generation]).
        onFix    = { fix -> if (readerGeneration.get() == generation) _fixes.tryEmit(fix) },
        onGsv    = { gsv -> if (readerGeneration.get() == generation) _gsv.tryEmit(gsv) }
    )

    /**
     * Bumped by every [stop]. Each reader records the generation it started under (thread-local:
     * the read loop never suspends, so it stays on one thread) and stops feeding the fuser — and its
     * fixes are dropped — once [stop] has advanced it. Otherwise a sentence in flight during a stop
     * (e.g. switching receivers) could put one fix from the OLD receiver into the replay cache.
     */
    @Volatile private var generation = 0L
    private val readerGeneration = ThreadLocal<Long>()

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
                    DiagnosticsLogger.w("RECEIVER", "TCP NMEA: no host/port configured")
                    return@launch
                }

                started = true
                firstDataLogged = false
                Log.d(TAG, "Connecting to $host:$port")
                DiagnosticsLogger.i("RECEIVER", "Connecting to $host:$port")
                connectAndRead(host, port)

            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // stop() closes the socket, which surfaces here as "Socket closed" — not an error.
                if (!isActive) return@launch
                Log.e(TAG, "Connection error: ${e.message}")
                DiagnosticsLogger.e("RECEIVER", "TCP connection error: ${e.message}")
                started = false
                // Let ExternalAdapter's reconnect loop handle the retry
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping TCP NMEA source")
        generation++            // first: an in-flight sentence from the old reader is now discarded
        started = false
        firstDataLogged = false
        connectionJob?.cancel()
        connectionJob = null
        // Closing the socket unblocks readLine() now, so the old reader can't keep feeding the
        // fuser (or hold a receiver client slot) for up to SOCKET_TIMEOUT_MS after a stop/reconnect.
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
        // reset() takes the fuser's lock, so it waits for any accept() already in progress; the replay
        // cache is cleared AFTER that, so no fix from the old connection can survive in it.
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
        val myGeneration = generation
        readerGeneration.set(myGeneration)
        try {
            socket = Socket().also { activeSocket = it }.apply {
                soTimeout = readTimeoutMs
                keepAlive = true   // OS-level probe for a peer that vanished without closing
                connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }
            Log.d(TAG, "Connected to $host:$port")
            DiagnosticsLogger.i("RECEIVER", "TCP socket connected to $host:$port")
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1024)

            // Enforce the fuser's GST hold cap in time (readLine() blocks, so the cap can't rely on the
            // next sentence). The ticker carries this reader's generation as a coroutine thread-local,
            // so its fixes pass the same onFix guard and are dropped after a stop like any other.
            val holdTicker = scope.launch(Dispatchers.IO + readerGeneration.asContextElement(myGeneration)) {
                while (isActive && myGeneration == generation) {
                    kotlinx.coroutines.delay(HOLD_TICK_MS)
                    if (myGeneration == generation) fuser.flushIfStale()
                }
            }
            var silentTimeouts = 0
            try {
            while (coroutineContext.isActive) {
                try {
                    val line = reader.readLine()
                    silentTimeouts = 0
                    if (line == null) {
                        Log.w(TAG, "Connection closed by remote")
                        DiagnosticsLogger.w("RECEIVER", "TCP connection closed by remote ($host:$port)")
                        break
                    }
                    if (myGeneration != generation) break   // stopped while reading this line
                    val clean = line.trim()
                    if (clean.isNotEmpty() && clean[0] == '$') {
                        if (!firstDataLogged) {
                            firstDataLogged = true
                            DiagnosticsLogger.i("RECEIVER", "First NMEA sentence received from $host:$port")
                        }
                        diagnostics?.recordLine(clean)
                        _rawNmea.tryEmit(Unit)
                        fuser.accept(clean)
                        debugSampler.accept(clean) { "$host:$port" }
                    }
                } catch (_: SocketTimeoutException) {
                    if (!coroutineContext.isActive) break
                    if (++silentTimeouts >= DEAD_LINK_TIMEOUTS) {
                        val silentMs = silentTimeouts.toLong() * readTimeoutMs
                        Log.w(TAG, "No data for ${silentMs}ms — treating the link as dead")
                        DiagnosticsLogger.w("RECEIVER", "TCP: no data for ${silentMs}ms from $host:$port — link dead, reconnecting")
                        break
                    }
                }
            }
            } finally {
                holdTicker.cancel()
            }
        } finally {
            // Stream ended (EOF / error): emit a fix still held for its GST so the last position
            // before a disconnect isn't lost. On the reader thread, so the generation guard applies:
            // after an explicit stop() it is dropped, as before.
            if (myGeneration == generation) runCatching { fuser.flush() }
            // Close directly (already on Dispatchers.IO). The previous withContext(...) threw
            // immediately in a cancelled coroutine without running its block, leaking the socket
            // on every stop/reconnect.
            runCatching { reader?.close() }
            runCatching { socket?.close() }
            if (activeSocket === socket) activeSocket = null
            readerGeneration.remove()
            Log.d(TAG, "Disconnected from $host:$port")
            DiagnosticsLogger.i("RECEIVER", "TCP disconnected from $host:$port")
        }
    }
}
