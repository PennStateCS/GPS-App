package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.bus.adapters.RawNmeaProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.SkyProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.SourceAdapter
import io.github.pennstatecs.geokit.gnss.bus.adapters.Startable
import io.github.pennstatecs.geokit.gnss.link.LinkLog.Companion.TAG_RECEIVER
import io.github.pennstatecs.geokit.gnss.log.GnssLog
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
 * Adapter for any external GNSS receiver whose fixes arrive via a [NmeaSource] (e.g. [TcpNmeaSource]).
 *
 * Converts the NMEA stream into:
 *  - [fixes]: normalized [Fix] objects, each passed through [stampFix], for the host's switchboard
 *  - [sky]:   smoothed SNR snapshots via [SatelliteInventory]
 *
 * Includes exponential-backoff reconnection logic so a transient TCP drop is recovered
 * automatically without user intervention.
 *
 * @param stampFix applied to every fix before it is published. A host uses it to label the fix with
 *   the receiver that produced it (the NMEA source and fuser don't know which receiver they talk to).
 *   If it throws, the unstamped fix is published and the error logged (throttled).
 * @param linkLog where connection lines go; share one instance with the [NmeaSource].
 * @param monotonicNowMs monotonic clock for data-age and connect timing (never jumps with wall-clock
 *   corrections).
 */
public class ReconnectingNmeaAdapter(
    private val scope: CoroutineScope,
    private val nmea: NmeaSource,
    private val inv: SatelliteInventory,
    private val stampFix: (Fix) -> Fix = { it },
    private val linkLog: LinkLog = GnssLinkLog(),
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : SourceAdapter, SkyProvider, Startable, RawNmeaProvider {

    public companion object {
        private const val TAG = "ExternalAdapter"
        private const val MAX_BACKOFF_SECONDS = 30
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_JITTER_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val HEALTH_INTERVAL_MS = 1_000L
        private const val HEALTH_DATA_TIMEOUT_MS = 30_000L

        /**
         * Prefix of the [LinkLog] throttle keys for the connect / retry lines (here and in
         * [TcpNmeaSource]). Each is logged once per outage; all are reset when data flows again.
         */
        public const val RETRY_LOG_PREFIX: String = "rx.retry."
        private const val RETRY_SUMMARY_KEY = RETRY_LOG_PREFIX + "summary"
        private const val RETRY_SUMMARY_MS = 60_000L

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
    public enum class ConnectionState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED, ERROR, CANCELLED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** Current [ConnectionState]. */
    public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    /** Connection attempts since the last successful connect (0 while connected or stopped). */
    public val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

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

    override fun start(): Unit = synchronized(lifecycleLock) { startLocked() }

    private fun startLocked() {
        if (reconnectJob != null) return
        GnssLog.d(TAG) { "start()" }
        _connectionState.value = ConnectionState.CONNECTING
        _attemptCount.value = 0
        // LAZY + start() after assignment: the loop checks `reconnectJob` to know it is the live one,
        // so it must not run before the field is set (immediate dispatchers would otherwise do that).
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { startWithReconnect() }
        reconnectJob = job
        job.start()
    }

    /**
     * Drops the current connection and reconnects (the source asks for its endpoint again). Used when
     * the user connects to a different receiver while this adapter is already active. No-op when the
     * adapter is not running (it reads the endpoint on its next start anyway).
     */
    public fun reconnectNow(reason: String) {
        // Off the caller's thread: a host may call this from its main thread, and stopping closes the
        // TCP socket and waits for the fuser lock while holding lifecycleLock. If the adapter is
        // stopped before this runs, reconnectJob is null and it does nothing.
        scope.launch {
            synchronized(lifecycleLock) {
                if (reconnectJob == null) return@synchronized
                linkLog.i(TAG_RECEIVER, "External receiver reconnect requested reason=\"$reason\"")
                stopLocked()
                startLocked()
            }
        }
    }

    override fun stop(): Unit = synchronized(lifecycleLock) { stopLocked() }

    private fun stopLocked() {
        linkLog.i(TAG_RECEIVER, "External receiver adapter stopped")
        failuresSinceUp = 0
        linkLog.resetThrottles(RETRY_LOG_PREFIX)   // a new session logs its first failure in full
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

    /** Failed attempts since the link was last up (log throttling only). */
    @Volatile private var failuresSinceUp = 0

    private suspend fun startWithReconnect() {
        var attempts = 0
        while (coroutineContext.isActive) {
            try {
                attempts++
                _attemptCount.value = attempts
                _connectionState.value = if (attempts == 1) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
                // Folded into the throttled "still unreachable" summary below.
                if (attempts > 1) GnssLog.d(TAG) { "Reconnecting external receiver attempt #$attempts" }

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
                // Recovery, once: data is flowing, so the next failure is a new outage logged in full.
                linkLog.i(TAG_RECEIVER, "External receiver connected attempt=$attempts" +
                    if (failuresSinceUp > 1) " failedAttempts=$failuresSinceUp" else "")
                failuresSinceUp = 0
                linkLog.resetThrottles(RETRY_LOG_PREFIX)
                GnssLog.event("external") {
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
                val failures = ++failuresSinceUp
                if (failures == 1) {
                    // First failure of an outage: in full, with the exception.
                    linkLog.w(TAG_RECEIVER, "External receiver connect failed attempt=$attempt (will retry)", e)
                    // Start the summary interval now, so the first summary comes a minute later.
                    linkLog.resetThrottles(RETRY_SUMMARY_KEY)
                    linkLog.every(TAG_RECEIVER, RETRY_SUMMARY_KEY, RETRY_SUMMARY_MS, LinkLogLevel.DEBUG) { "External receiver retry summary interval started" }
                } else {
                    linkLog.every(TAG_RECEIVER, RETRY_SUMMARY_KEY, RETRY_SUMMARY_MS, LinkLogLevel.WARN) {
                        "External receiver still unreachable attempts=$failures lastError=${e.javaClass.simpleName} " +
                            "reason=\"${e.message ?: "unknown"}\""
                    }
                }
                _connectionState.value = ConnectionState.ERROR
                synchronized(lifecycleLock) { stopInternalJobs() }
                if (!coroutineContext.isActive) break
                val backoff = calculateBackoffDelay(attempt)
                GnssLog.event("external") {
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
                    val stamped = runCatching { stampFix(fix) }.getOrElse {
                        // Per fix: first error in full, then at most once a minute.
                        linkLog.every(TAG_RECEIVER, "external.stamp", 60_000L, LinkLogLevel.ERROR, throwable = it) {
                            "External receiver fix stamping failed: publishing unstamped fix"
                        }; fix
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
                        .onFailure {
                            linkLog.every(TAG_RECEIVER, "external.gsv", 60_000L, LinkLogLevel.ERROR, throwable = it) { "External receiver satellite update failed: GSV message skipped" }
                        }
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
                // The thrown message reaches the attempt-failed / summary line; detail only here.
                linkLog.d(TAG_RECEIVER) { "External receiver connection ended before any NMEA data (will retry)" }
                throw IllegalStateException("Connection ended before data")
            }
            delay(250)
        }
        linkLog.d(TAG_RECEIVER) { "External receiver connect timed out: no NMEA data timeoutMs=$CONNECT_TIMEOUT_MS (will retry)" }
        throw IllegalStateException("Timeout: no data within ${CONNECT_TIMEOUT_MS} ms")
    }

    private suspend fun monitorConnectionHealth() {
        while (coroutineContext.isActive) {
            delay(HEALTH_INTERVAL_MS)
            // Socket closed by the receiver (reboot, Wi-Fi drop, another client took the slot):
            // reconnect immediately instead of waiting for the no-data timeout.
            if (!nmea.isConnectionActive()) {
                linkLog.w(TAG_RECEIVER, "External receiver connection ended: reconnecting")
                throw IllegalStateException("Connection ended")
            }
            val last = lastDataTimeMs
            if (last == 0L) continue
            val staleFor = monotonicNowMs() - last
            if (staleFor > HEALTH_DATA_TIMEOUT_MS) {
                linkLog.w(TAG_RECEIVER, "External receiver link stalled: no NMEA data, reconnecting silentMs=$HEALTH_DATA_TIMEOUT_MS")
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
