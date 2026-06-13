package com.example.surveyingapp.gnss.bus.adapters

import android.util.Log
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.settings.SourceSettings
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
 * Connection parameters (host/port) are read from [SettingsRepository].
 *
 * **Future**: [SourceSettings] is wired for multi-profile support but profiles are not yet
 * populated from UI. When profile UI is implemented, this class can switch to using
 * [SourceSettings.getConnectionInfo] instead of [SettingsRepository] for connection parameters.
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
    private val settingsRepository: SettingsRepository,
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

    override fun parsedFixes(): SharedFlow<Fix> = _fixes.asSharedFlow()
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv.asSharedFlow()

    private val fuser = NmeaFuser(
        provider = provider,
        registry = registry,
        onFix    = { fix -> scope.launch { _fixes.emit(fix) } },
        onGsv    = { gsv -> scope.launch { _gsv.emit(gsv) } }
    )

    private var connectionJob: Job? = null
    @Volatile private var started = false

    /**
     * Reads host/port from [SettingsRepository] and initiates a single TCP connection attempt.
     * The connection runs until the stream ends, a read error occurs, or [stop] is called.
     * [ExternalAdapter] is responsible for calling [stop] then [start] again to reconnect.
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
                    return@launch
                }

                // Only set started flag after we have valid connection parameters
                started = true
                Log.d(TAG, "Connecting to $host:$port")
                connectAndRead(host, port)

            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                started = false  // Reset on failure so next start() attempt can proceed
                // Let ExternalAdapter's reconnect loop handle the retry
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping TCP NMEA source")
        started = false
        connectionJob?.cancel()
        connectionJob = null
        fuser.reset()
    }

    private suspend fun connectAndRead(host: String, port: Int) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        try {
            socket = Socket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }
            Log.d(TAG, "Connected to $host:$port")
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1024)

            while (coroutineContext.isActive) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "Connection closed by remote")
                        break
                    }
                    val clean = line.trim()
                    if (clean.isNotEmpty() && clean[0] == '$') {
                        // Log all GSV sentences to diagnose satellite count discrepancy
                        if (clean.contains("GSV")) {
                            android.util.Log.i(TAG, "📡 RAW GSV: $clean")
                        }
                        diagnostics?.recordLine(clean)
                        fuser.accept(clean)
                    }
                } catch (_: SocketTimeoutException) {
                    if (!coroutineContext.isActive) break
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { reader?.close() }
                runCatching { socket?.close() }
            }
            Log.d(TAG, "Disconnected from $host:$port")
        }
    }
}
