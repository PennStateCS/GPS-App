package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.link.LinkLog.Companion.TAG_NMEA
import io.github.pennstatecs.geokit.gnss.link.LinkLog.Companion.TAG_RECEIVER
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.NmeaDebugSampler
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * [NmeaSource] that reads NMEA sentences from an external GNSS receiver over TCP.
 *
 * The receiver's host and port come from [endpoint], asked once per connection attempt; null means
 * none is configured, and the attempt ends at once (logged once per outage).
 *
 * This source makes a **single connection attempt** and streams until the connection drops or
 * [stop] is called. Reconnection logic (with exponential back-off) is the responsibility of
 * [ReconnectingNmeaAdapter], which owns the lifecycle of this source.
 *
 * Sentence parsing and fix fusion are delegated to [NmeaFuser]; the caller's [NmeaRegistry]
 * instance is reused to avoid redundant parser allocations.
 *
 * This implementation is intentionally provider-agnostic: any TCP NMEA source (RS2+, Reach,
 * u-blox, etc.) will work — the [Provider] tag stamped onto emitted [Fix] objects is the only
 * receiver coupling, and it can be changed at construction time.
 *
 * @param onLine called with every NMEA sentence (trimmed, starting with `$`) before it is parsed,
 *   e.g. for the host's rate/diagnostics counters. An exception from it drops only that sentence.
 * @param linkLog where connection lines go; share one instance with the [ReconnectingNmeaAdapter].
 * @param readTimeoutMs socket read timeout; [DEAD_LINK_TIMEOUTS] in a row with no data ends the
 *   connection. Tests shorten it.
 */
public class TcpNmeaSource(
    private val scope: CoroutineScope,
    private val endpoint: suspend () -> Pair<String, Int>?,
    registry: NmeaRegistry,
    private val provider: Provider = Provider.EXTERNAL_TCP,
    private val onLine: ((String) -> Unit)? = null,
    private val linkLog: LinkLog = GnssLinkLog(),
    private val readTimeoutMs: Int = SOCKET_TIMEOUT_MS,
) : NmeaSource {

    public companion object {
        private const val TAG = "TcpNmeaSource"
        /** Throttle keys for the connect / retry lines; the adapter resets them on a real connect. */
        private const val RETRY = ReconnectingNmeaAdapter.RETRY_LOG_PREFIX
        /** Default socket connect and read timeout, milliseconds. */
        public const val SOCKET_TIMEOUT_MS: Int = 5_000
        /** How often the fuser's GST hold cap (300 ms) is checked while reading. */
        private const val HOLD_TICK_MS = 100L
        /**
         * Consecutive read timeouts with no line received that mean the link is dead (half-open socket:
         * Wi-Fi dropped, receiver powered off without closing). 2 × 5 s ≈ 10 s on a ≥1 Hz stream, instead
         * of the adapter's 30 s no-data timeout. Ending the connection lets the adapter's existing
         * reconnect/backoff run.
         */
        public const val DEAD_LINK_TIMEOUTS: Int = 2
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
    override fun nmeaTimingStats(): NmeaFuser.FuserTimingStats = fuser.timingStats
    override fun nmeaCustomStats(): NmeaFuser.NmeaCustomStats = fuser.nmeaCustomStats

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

    /** Debug builds: periodic raw-sentence samples from the receiver (developer log only). Reader thread only. */
    private val debugSampler = NmeaDebugSampler("nmea-ext")

    private var connectionJob: Job? = null
    /** Open socket, so [stop] can close it and unblock a pending readLine() immediately. */
    @Volatile private var activeSocket: Socket? = null

    /**
     * True while a connection attempt or open stream is in progress. False once the remote closed the
     * socket, the connect failed, or [stop] ran — lets [ReconnectingNmeaAdapter] reconnect right away
     * instead of waiting for its no-data timeout.
     */
    override fun isConnectionActive(): Boolean = connectionJob?.isActive == true
    @Volatile private var started = false
    @Volatile private var firstDataLogged = false

    /**
     * Asks [endpoint] for host/port and initiates a single TCP connection attempt.
     * The connection streams lines until the remote closes it, a read error occurs, or [stop]
     * is called. Reconnection with exponential back-off is the responsibility of [ReconnectingNmeaAdapter].
     */
    override fun start() {
        if (started) return
        GnssLog.d(TAG) { "Starting TCP NMEA source (single-connect)" }
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                val target = endpoint()

                if (target == null || target.first.isBlank()) {
                    // Repeats every reconnect attempt: once per outage (keys reset on a real connect).
                    linkLog.logOnChange(TAG_RECEIVER, RETRY + "nohost", true, LinkLogLevel.WARN) { "External receiver connect skipped: no host or port configured" }
                    return@launch
                }
                val (host, port) = target

                started = true
                firstDataLogged = false
                // Once per target per outage; the adapter's throttled summary covers the retries.
                linkLog.logOnChange(TAG_RECEIVER, RETRY + "connecting", "$host:$port") { "External receiver connecting host=$host port=$port" }
                connectAndRead(host, port)

            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // stop() closes the socket, which surfaces here as "Socket closed" — not an error.
                if (!isActive) return@launch
                // Refused / unreachable is expected while the receiver is off or out of range, and is
                // retried: the first failure of an outage in full, then the adapter's summary.
                val logged = linkLog.logOnChange(TAG_RECEIVER, RETRY + "tcp.error", "failing", LinkLogLevel.WARN, throwable = e) {
                    "External receiver TCP connect failed (will retry)"
                }
                if (!logged) GnssLog.d(TAG) { "TCP connect failed again: ${e.javaClass.simpleName}: ${e.message}" }
                started = false
                // Let the adapter's reconnect loop handle the retry
            }
        }
    }

    override fun stop() {
        GnssLog.d(TAG) { "Stopping TCP NMEA source" }
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
        var socketConnected = false   // only for the "disconnected" log line
        try {
            socket = Socket().also { activeSocket = it }.apply {
                soTimeout = readTimeoutMs
                keepAlive = true   // OS-level probe for a peer that vanished without closing
                connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }
            socketConnected = true
            linkLog.logOnChange(TAG_RECEIVER, RETRY + "socket", "$host:$port") { "External receiver TCP socket connected host=$host port=$port" }
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
                        linkLog.logOnChange(TAG_RECEIVER, RETRY + "remoteclosed", true, LinkLogLevel.WARN) {
                            "External receiver TCP connection closed by remote host=$host port=$port"
                        }
                        break
                    }
                    if (myGeneration != generation) break   // stopped while reading this line
                    val clean = line.trim()
                    if (clean.isNotEmpty() && clean[0] == '$') {
                        if (!firstDataLogged) {
                            firstDataLogged = true
                            linkLog.i(TAG_RECEIVER, "External receiver first NMEA sentence received host=$host port=$port")
                        }
                        // An exception from an odd sentence anywhere downstream (fusion, fix
                        // listeners) used to end the connection and force a reconnect. Drop just
                        // that sentence and log it (throttled, never the sentence text).
                        try {
                            onLine?.invoke(clean)
                            _rawNmea.tryEmit(Unit)
                            fuser.accept(clean)
                            debugSampler.accept(clean) { "$host:$port" }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            linkLog.every(TAG_NMEA, "tcp.sentence.error", 60_000L, LinkLogLevel.ERROR, throwable = e) {
                                "External receiver NMEA processing failed: sentence dropped, repeats suppressed for 60 s"
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    if (!coroutineContext.isActive) break
                    if (++silentTimeouts >= DEAD_LINK_TIMEOUTS) {
                        val silentMs = silentTimeouts.toLong() * readTimeoutMs
                        linkLog.logOnChange(TAG_RECEIVER, RETRY + "deadlink", true, LinkLogLevel.WARN) {
                            "External receiver TCP link dead: no data, reconnecting host=$host port=$port silentMs=$silentMs"
                        }
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
            // Only after a socket that actually connected; once per outage.
            if (socketConnected) {
                linkLog.logOnChange(TAG_RECEIVER, RETRY + "disconnected", "$host:$port") { "External receiver TCP disconnected host=$host port=$port" }
            }
        }
    }
}
