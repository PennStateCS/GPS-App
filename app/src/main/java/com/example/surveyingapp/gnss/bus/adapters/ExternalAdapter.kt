package com.example.surveyingapp.gnss.bus.adapters

import android.util.Log
import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.bus.SkyProvider
import com.example.surveyingapp.gnss.bus.Startable
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random
import kotlin.coroutines.coroutineContext

/**
 * External GNSS adapter (e.g., RS2+) that converts an NMEA source into:
 *  - fixes: normalized GNSS fixes
 *  - sky:   smoothed SNR + tallies via SatelliteInventory
 *
 * Explicit start/stop to tie lifecycle to connection state.
 */
class ExternalAdapter(
    private val scope: CoroutineScope,
    private val context: android.content.Context,
    private val settingsRepository: com.example.surveyingapp.domain.repository.SettingsRepository,
    private val nmea: NmeaSource,                 // bridge to your existing NMEA accumulator
    private val inv: SatelliteInventory           // shared inventory for SNR smoothing
) : SourceAdapter, SkyProvider, Startable {

    companion object {
        private const val TAG = "ExternalAdapter"
        private const val MAX_BACKOFF_SECONDS = 30
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_JITTER_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val HEALTH_INTERVAL_MS = 5_000L
        private const val HEALTH_DATA_TIMEOUT_MS = 30_000L
    }

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot())
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    enum class ConnectionState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED, ERROR, CANCELLED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

    private var reconnectJob: Job? = null
    private var fixesJob: Job? = null
    private var gsvJob: Job? = null

    // health/data flags
    @Volatile private var firstDataArrived = false
    @Volatile private var lastDataTimeMs: Long = 0L

    // ---- Startable ----
    override fun start() {
        if (reconnectJob != null) return
        Log.d(TAG, "start()")
        _connectionState.value = ConnectionState.CONNECTING
        _attemptCount.value = 0
        reconnectJob = scope.launch { startWithReconnect() }
    }

    override fun stop() {
        Log.d(TAG, "stop()")
        reconnectJob?.cancel(); reconnectJob = null
        stopInternalJobs()
        runCatching { nmea.stop() }
        _connectionState.value = ConnectionState.DISCONNECTED
        _attemptCount.value = 0
        _sky.value = emptySky()
        firstDataArrived = false
        lastDataTimeMs = 0L
    }

    // ---- internals ----

    private suspend fun startWithReconnect() {
        var attempts = 0
        while (coroutineContext.isActive) {
            try {
                attempts++
                _attemptCount.value = attempts
                _connectionState.value = if (attempts == 1) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

                startInternalConnection()

                waitForFirstDataOrTimeout()
                _connectionState.value = ConnectionState.CONNECTED
                attempts = 0 // reset after a successful connect

                monitorConnectionHealth()
            } catch (ce: CancellationException) {
                _connectionState.value = ConnectionState.CANCELLED
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "connection attempt #$attempts failed: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR
                stopInternalJobs()
                if (!coroutineContext.isActive) break
                delay(calculateBackoffDelay(attempts))
            }
        }
    }

    private fun startInternalConnection() {
        // Reset flags
        firstDataArrived = false
        lastDataTimeMs = 0L

        nmea.start()

        fixesJob = scope.launch {
            nmea.parsedFixes()
                .conflate()
                .onEach {
                    firstDataArrived = true
                    lastDataTimeMs = System.currentTimeMillis()
                }
                .collect { fix -> _fixes.emit(fix) }
        }

        gsvJob = scope.launch {
            nmea.gsvStream()
                .conflate()
                .onEach {
                    firstDataArrived = true
                    lastDataTimeMs = System.currentTimeMillis()
                }
                .collect { gsv -> _sky.value = inv.consume(gsv) }
        }
    }

    private suspend fun waitForFirstDataOrTimeout() {
        val start = System.currentTimeMillis()
        while (coroutineContext.isActive && System.currentTimeMillis() - start < CONNECT_TIMEOUT_MS) {
            if (firstDataArrived) return
            delay(250)
        }
        throw IllegalStateException("Timeout: no data within ${CONNECT_TIMEOUT_MS} ms")
    }

    private suspend fun monitorConnectionHealth() {
        while (coroutineContext.isActive) {
            delay(HEALTH_INTERVAL_MS)
            val last = lastDataTimeMs
            if (last == 0L) continue
            val staleFor = System.currentTimeMillis() - last
            if (staleFor > HEALTH_DATA_TIMEOUT_MS) {
                throw IllegalStateException("No data for ${HEALTH_DATA_TIMEOUT_MS} ms")
            }
        }
    }

    private fun stopInternalJobs() {
        fixesJob?.cancel(); fixesJob = null
        gsvJob?.cancel();   gsvJob = null
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exp = INITIAL_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5))
        val capped = min(exp, MAX_BACKOFF_SECONDS * 1000L)
        val jitter = Random.Default.nextLong(0, MAX_JITTER_MS)
        return capped + jitter
    }

    private fun emptySky(): SkySnapshot =
        SkySnapshot()
}

/**
 * Abstraction for your (new) NMEA pipeline. It should NOT be the legacy bridge.
 */
interface NmeaSource : Startable {
    fun parsedFixes(): SharedFlow<Fix>
    fun gsvStream(): SharedFlow<GsvMessage>
}

/** Minimal GSV message for the satellite inventory. */
data class GsvMessage(
    val constellation: String,
    val entries: List<GsvEntry>
)

data class GsvEntry(
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Double?,   // nullable is fine
    val usedInFix: Boolean
)
