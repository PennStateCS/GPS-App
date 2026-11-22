package app.surrealar.gnss.external

import android.util.Log
import app.surrealar.gnss.bus.SourceAdapter
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.bus.adapters.RawNmeaProvider
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.gnss.bus.SkyProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.Startable
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
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
 * Adapter for any external GNSS receiver whose fixes arrive via a [NmeaSource].
 *
 * Converts the NMEA stream into:
 *  - [fixes]: normalized [Fix] objects forwarded to `FixSwitchboard`
 *  - [sky]:   smoothed SNR snapshots via [SatelliteInventory]
 *
 * Includes exponential-backoff reconnection logic so a transient TCP drop is recovered
 * automatically without user intervention.
 *
 * To add a new external provider (e.g., u-blox over Bluetooth), create a new [NmeaSource]
 * implementation and wrap it in a new [ExternalAdapter] instance registered in `GnssModule`.
 */
class ExternalAdapter(
    private val scope: CoroutineScope,
    private val nmea: NmeaSource,
    private val inv: SatelliteInventory,
    /**
     * The active receiver profile. Each fix is stamped with its label and accuracy class here (the
     * NMEA source and fuser don't know which receiver they talk to), so accuracy fallbacks, exports
     * and captures see the receiver that produced the fix even if the profile changes later.
     */
    private val currentProfile: () -> app.surrealar.settings.model.ExternalReceiverProfile,
    /** Monotonic clock for data-age and connect timing (never jumps with wall-clock corrections). */
    private val monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : SourceAdapter, SkyProvider, Startable, RawNmeaProvider, app.surrealar.gnss.source.ExternalReconnectable {

    companion object {
        private const val TAG = "ExternalAdapter"
        private const val MAX_BACKOFF_SECONDS = 30
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_JITTER_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val HEALTH_INTERVAL_MS = 1_000L
        private const val HEALTH_DATA_TIMEOUT_MS = 30_000L

        /**
         * Exponential backoff with jitter. [attempt] is floored at 1: after a drop from CONNECTED
         * the loop's counter is 0, and `1L shl -1` shifted by 63 (a huge negative delay).
         */
        internal fun calculateBackoffDelay(attempt: Int, random: Random = Random.Default): Long {
            val n = attempt.coerceAtLeast(1)
            val exp = INITIAL_DELAY_MS * (1L shl (n - 1).coerceAtMost(5))
            val capped = min(exp, MAX_BACKOFF_SECONDS * 1000L)
            val jitter = random.nextLong(0, MAX_JITTER_MS)
            return capped + jitter
        }
    }

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot())
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private val _rawNmea = MutableSharedFlow<Unit>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val rawNmea: SharedFlow<Unit> = _rawNmea.asSharedFlow()

    /**
     * Lifecycle of the external receiver socket. `RECONNECTING` is an automatic retry after a drop;
     * `ERROR` is a failed attempt; `CANCELLED` is a user/explicit stop. Exposed via [connectionState].
     */
    enum class ConnectionState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED, ERROR, CANCELLED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

    private var reconnectJob: Job? = null
    private var fixesJob: Job? = null
    private var gsvJob: Job? = null
    private var rawNmeaJob: Job? = null

    // ---- health/data tracking ----
    @Volatile private var firstDataArrived = false
    @Volatile private var lastDataTimeMs: Long = 0L

    // ---- Startable ----
    /*
     * start/stop/reconnectNow run on the caller's thread while the reconnect loop runs on the app
     * scope. They share one lock with the loop's connection (re)start, so a stop can never land
     * half-way through startInternalConnection() (which left a second set of collectors running —
     * every fix delivered twice — or the source restarted after stop).
     */
    private val lifecycleLock = Any()

    override fun start() = synchronized(lifecycleLock) { startLocked() }

    private fun startLocked() {
        if (reconnectJob != null) return
        Log.d(TAG, "start()")
        _connectionState.value = ConnectionState.CONNECTING
        _attemptCount.value = 0
        // LAZY + start() after assignment: the loop checks `reconnectJob` to know it is the live one,
        // so it must not run before the field is set (immediate dispatchers would otherwise do that).
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { startWithReconnect() }
        reconnectJob = job
        job.start()
    }

    /**
     * Drops the current connection and reconnects using the CURRENT saved host/port. Used when the
     * user connects to a different receiver while External is already active — the provider does not
     * change, so the switchboard would not rebind and the old socket would stay up. No-op when the
     * adapter is not running (it reads the saved settings on its next start anyway).
     */
    override fun reconnectNow(reason: String) {
        // Off the caller's thread: Settings calls this from the main thread, and stopping closes the TCP
        // socket and waits for the fuser lock while holding lifecycleLock. If the adapter is stopped
        // before this runs, reconnectJob is null and it does nothing.
        scope.launch {
            synchronized(lifecycleLock) {
                if (reconnectJob == null) return@synchronized
                DiagnosticsLogger.i("RECEIVER", "Reconnecting external receiver now ($reason)")
                stopLocked()
                startLocked()
            }
        }
    }

    override fun stop() = synchronized(lifecycleLock) { stopLocked() }

    private fun stopLocked() {
        Log.d(TAG, "stop()")
        DiagnosticsLogger.i("RECEIVER", "External adapter stopped")
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
                if (attempts > 1) DiagnosticsLogger.w("RECEIVER", "Reconnecting external receiver attempt #$attempts")

                // Under the lifecycle lock, and only if this loop is still the live one (a stop()
                // that won the lock has already cancelled us).
                val thisJob = coroutineContext[Job]
                synchronized(lifecycleLock) {
                    if (thisJob == null || thisJob !== reconnectJob || !thisJob.isActive) {
                        throw CancellationException("external adapter stopped")
                    }
                    startInternalConnection()
                }

                waitForFirstDataOrTimeout()
                _connectionState.value = ConnectionState.CONNECTED
                DiagnosticsLogger.i("RECEIVER", "External receiver connected (attempt #$attempts)")
                app.surrealar.gnss.debug.GnssDebugLog.event("external") {
                    "CONNECTED after $attempts attempt(s); fuser stats ${nmea.nmeaTimingStats()}"
                }
                attempts = 0 // reset after a successful connect

                monitorConnectionHealth()
            } catch (ce: CancellationException) {
                // Only the live loop may report CANCELLED: a stop()/reconnectNow() that cancelled
                // us has already set DISCONNECTED/CONNECTING, which must not be overwritten.
                val thisJob = coroutineContext[Job]
                synchronized(lifecycleLock) {
                    if (thisJob != null && thisJob === reconnectJob) {
                        _connectionState.value = ConnectionState.CANCELLED
                    }
                }
                throw ce
            } catch (e: Exception) {
                // After a drop from CONNECTED the counter was reset to 0; this is still attempt #1.
                val attempt = attempts.coerceAtLeast(1)
                Log.w(TAG, "connection attempt #$attempt failed: ${e.message}", e)
                DiagnosticsLogger.w("RECEIVER", "Connection attempt #$attempt failed: ${e.message}")
                _connectionState.value = ConnectionState.ERROR
                synchronized(lifecycleLock) { stopInternalJobs() }
                if (!coroutineContext.isActive) break
                val backoff = calculateBackoffDelay(attempt)
                app.surrealar.gnss.debug.GnssDebugLog.event("external") {
                    "attempt #$attempt failed (${e.javaClass.simpleName}: ${e.message}); " +
                        "connectionActive=${nmea.isConnectionActive()} firstData=$firstDataArrived " +
                        "lastDataAgo=${if (lastDataTimeMs == 0L) "-" else "${monotonicNowMs() - lastDataTimeMs}ms"} " +
                        "retry in ${backoff}ms"
                }
                delay(backoff)
            }
        }
    }

    private fun startInternalConnection() {
        /*
         * Always stop the source first so any open socket or listener from a previous
         * attempt is cleaned up before we start a fresh one.
         */
        runCatching { nmea.stop() }

        // Reset flags
        firstDataArrived = false
        lastDataTimeMs = 0L

        // Clear satellite data from the previous provider so stale entries don't appear
        inv.reset()

        nmea.start()

        fixesJob = scope.launch {
            nmea.parsedFixes()
                .conflate()
                .onEach {
                    firstDataArrived = true
                    lastDataTimeMs = monotonicNowMs()
                }
                .collect { fix ->
                    // Per-item work is guarded so one bad item can't end the collector (or crash the
                    // app scope); the emit stays outside so cancellation is never swallowed.
                    val stamped = runCatching {
                        val profile = currentProfile()
                        fix.copy(accuracyClass = profile.accuracyClass, receiverLabel = profile.label)
                    }.getOrElse {
                        DiagnosticsLogger.e("RECEIVER", "fix stamping failed; publishing unstamped fix", it); fix
                    }
                    _fixes.emit(stamped)
                }
        }

        gsvJob = scope.launch {
            nmea.gsvStream()
                .onEach {
                    firstDataArrived = true
                    lastDataTimeMs = monotonicNowMs()
                }
                .collect { gsv ->
                    runCatching { inv.consume(gsv) }
                        .onSuccess { _sky.value = it }
                        .onFailure { DiagnosticsLogger.e("RECEIVER", "GSV update failed; message skipped", it) }
                }
        }

        rawNmeaJob = scope.launch {
            nmea.rawNmeaEvents().collect {
                firstDataArrived = true
                lastDataTimeMs = monotonicNowMs()
                _rawNmea.emit(Unit)
            }
        }
    }

    private suspend fun waitForFirstDataOrTimeout() {
        val start = monotonicNowMs()
        while (coroutineContext.isActive && monotonicNowMs() - start < CONNECT_TIMEOUT_MS) {
            if (firstDataArrived) return
            // Connect refused / host unreachable / no host configured: retry on the backoff
            // schedule now rather than sitting out the full connect timeout.
            if (!nmea.isConnectionActive()) {
                DiagnosticsLogger.w("RECEIVER", "External connection ended before any NMEA data — will retry")
                throw IllegalStateException("Connection ended before data")
            }
            delay(250)
        }
        DiagnosticsLogger.w("RECEIVER", "No NMEA data within ${CONNECT_TIMEOUT_MS}ms — will retry")
        throw IllegalStateException("Timeout: no data within ${CONNECT_TIMEOUT_MS} ms")
    }

    private suspend fun monitorConnectionHealth() {
        while (coroutineContext.isActive) {
            delay(HEALTH_INTERVAL_MS)
            // Socket closed by the receiver (reboot, Wi-Fi drop, another client took the slot):
            // reconnect immediately instead of waiting for the no-data timeout.
            if (!nmea.isConnectionActive()) {
                DiagnosticsLogger.w("RECEIVER", "External connection ended — triggering reconnect")
                throw IllegalStateException("Connection ended")
            }
            val last = lastDataTimeMs
            if (last == 0L) continue
            val staleFor = monotonicNowMs() - last
            if (staleFor > HEALTH_DATA_TIMEOUT_MS) {
                DiagnosticsLogger.w("RECEIVER", "No NMEA data for ${HEALTH_DATA_TIMEOUT_MS}ms — triggering reconnect")
                throw IllegalStateException("No data for ${HEALTH_DATA_TIMEOUT_MS} ms")
            }
        }
    }

    private fun stopInternalJobs() {
        fixesJob?.cancel();    fixesJob = null
        gsvJob?.cancel();      gsvJob = null
        rawNmeaJob?.cancel();  rawNmeaJob = null
    }

    private fun emptySky(): SkySnapshot =
        SkySnapshot()
}
