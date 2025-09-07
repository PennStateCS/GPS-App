package com.example.surveyingapp.data.location.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.domain.model.TimestampSource
import com.example.surveyingapp.domain.model.CorrectionSource
import com.example.surveyingapp.data.location.nmea.accumulator.FixAccumulator
import com.example.surveyingapp.data.location.nmea.accumulator.FixSnapshot
import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.coroutineContext

class NmeaSource(
    private val btAddressProvider: suspend () -> String?,
    private val tcpHostProvider: suspend () -> Pair<String?, Int?>,
    private val connectionTypeProvider: suspend () -> ConnectionType
) {
    private val parser = NmeaParser()

    private val statusInternal = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val status: StateFlow<LocationStatus> = statusInternal.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _currentType = MutableStateFlow(ConnectionType.BT)
    val currentType: StateFlow<ConnectionType> = _currentType.asStateFlow()

    // Recent raw NMEA for diagnostics (UI replays up to 100 lines).
    private val _recentRaw = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 32)
    val recentRaw: SharedFlow<String> = _recentRaw.asSharedFlow()

    // ───────────────── Satellites/SNR model for UI ─────────────────
    data class SkySnapshot(
        val byConst: Map<NmeaParser.Constellation, List<Int>>, // SNR 0..50
        val satsUsed: Int? = null,
        val satsInView: Int? = null,
        val perConstCounts: Map<NmeaParser.Constellation, Int> = emptyMap(),
        val lastUpdatedMs: Long = System.currentTimeMillis(),
        val satellites: List<NmeaParser.Satellite> = emptyList(),
        val usedPrns: Set<Int> = emptySet()
    )
    private val _skyFlow = MutableStateFlow(SkySnapshot(emptyMap(), null, null, emptyMap()))
    val skyFlow: StateFlow<SkySnapshot> = _skyFlow.asStateFlow()

    // Small EMA smoother + staleness tracking (per SVID)
    private val snrEma: MutableMap<Int, Double> = mutableMapOf()
    private val snrSeenAt: MutableMap<Int, Long> = mutableMapOf()
    private val snrAlpha = 0.5            // EMA smoothing factor
    private val snrStaleMs = 15_000L      // drop sats older than this

    private fun guessConstellation(svid: Int): NmeaParser.Constellation = when (svid) {
        in 1..32    -> NmeaParser.Constellation.GPS
        in 33..64   -> NmeaParser.Constellation.SBAS     // NMEA SVIDs; PRN = SVID + 87
        in 65..96   -> NmeaParser.Constellation.GLONASS
        in 193..200 -> NmeaParser.Constellation.QZSS
        in 201..237 -> NmeaParser.Constellation.BEIDOU   // common BeiDou mapping
        in 301..336 -> NmeaParser.Constellation.GALILEO
        in 401..437 -> NmeaParser.Constellation.BEIDOU   // some receivers use 401+ for BDS-3
        else        -> NmeaParser.Constellation.UNKNOWN
    }

    private fun updateSkyFrom(snapshot: FixSnapshot) {
        val now = System.currentTimeMillis()

        // Update EMA for sats that report SNR
        snapshot.satellites.forEach { sat ->
            val snr = sat.snrDb ?: return@forEach
            val prev = snrEma[sat.prn]
            val ema = if (prev == null) snr.toDouble() else prev + snrAlpha * (snr - prev)
            snrEma[sat.prn] = ema
            snrSeenAt[sat.prn] = now
        }
        // Prune stale
        val it = snrSeenAt.iterator()
        while (it.hasNext()) {
            val (prn, seen) = it.next()
            if (now - seen > snrStaleMs) {
                it.remove()
                snrEma.remove(prn)
            }
        }

        // Build grouped, clamped SNR lists
        val grouped: Map<NmeaParser.Constellation, List<Int>> =
            snapshot.satellites
                .groupBy { sat ->
                    if (sat.constellation != NmeaParser.Constellation.UNKNOWN)
                        sat.constellation
                    else
                        guessConstellation(sat.prn)
                }
                .mapValues { (_, sats) ->
                    sats.mapNotNull { sat ->
                        val v = (snrEma[sat.prn] ?: sat.snrDb?.toDouble())?.toInt()
                        v?.coerceIn(0, 50)
                    }
                }
                .filterValues { it.isNotEmpty() }

        // Per-constellation counts (include satellites even if SNR missing)
        var perConstCounts: Map<NmeaParser.Constellation, Int> =
            snapshot.satellites
                .groupBy { sat ->
                    if (sat.constellation != NmeaParser.Constellation.UNKNOWN)
                        sat.constellation
                    else
                        guessConstellation(sat.prn)
                }
                .mapValues { it.value.size }
                .filterValues { it > 0 }

        // Fallback: if we have no GSV satellites yet but GGA reports satsUsed, surface a generic GPS count
        if (perConstCounts.isEmpty()) {
            snapshot.satsUsed?.let { used ->
                if (used > 0) perConstCounts = mapOf(NmeaParser.Constellation.GPS to used)
            }
        }

        _skyFlow.value = SkySnapshot(
            byConst = grouped,
            satsUsed = snapshot.satsUsed,
            satsInView = snapshot.satsInView,
            perConstCounts = perConstCounts,
            lastUpdatedMs = now,
            satellites = snapshot.satellites,
            usedPrns = snapshot.usedSvids?.toSet() ?: emptySet()
        )
    }

    enum class ConnectionType { BT, TCP }

    private data class StreamHandle(
        val reader: BufferedReader,
        val close: () -> Unit,
        val type: ConnectionType
    )

    /**
     * Raw NMEA line stream.
     * Cancellation-safe: on coroutine/job cancellation, we actively close the current socket/input,
     * which unblocks readLine() immediately (both TCP and Bluetooth).
     */
    fun rawLines(): Flow<String> = channelFlow {
        val closerRef = AtomicReference<(() -> Unit)?>(null)

        // Ensure any in-flight stream is torn down immediately on cancellation of this producer
        val job = coroutineContext[Job]
        job?.invokeOnCompletion {
            runCatching { closerRef.get()?.invoke() }
        }

        var attempt = 0
        while (isActive) {
            attempt++
            _attemptCount.value = attempt
            statusInternal.value = LocationStatus.Connecting(attempt)

            var handle: StreamHandle? = null
            try {
                val kind = connectionTypeProvider()
                _currentType.value = kind
                Log.d(TAG, "attempt #$attempt: opening ${kind.name}")

                handle = withContext(Dispatchers.IO) {
                    when (kind) {
                        ConnectionType.BT  -> openBt()
                        ConnectionType.TCP -> openTcp()
                    }
                } ?: run {
                    Log.w(TAG, "attempt #$attempt: failed to open stream (null handle)")
                    throw IllegalStateException("Unable to open ${kind.name} stream")
                }

                // Register closer so cancellation shuts the socket/reader immediately
                closerRef.set(handle.close)

                statusInternal.value = LocationStatus.Streaming
                Log.d(TAG, "attempt #$attempt: streaming via ${handle.type}")

                withContext(Dispatchers.IO) {
                    handle.reader.use { r ->
                        attempt = 0
                        var lineCount = 0
                        while (isActive) {
                            val line = r.readLine() ?: break
                            lineCount++
                            if (line.isNotEmpty() && line[0] == '$' && line.contains('*') && line.length <= 200) {
                                trySend(line)
                                _recentRaw.tryEmit(line)
                            }
                        }
                        Log.d(TAG, "stream ended after $lineCount lines")
                    }
                }
            } catch (ce: CancellationException) {
                // Normal during source switches; let finally close and exit.
                throw ce
            } catch (se: SecurityException) {
                statusInternal.value = LocationStatus.Error("bt permission")
                _lastError.value = se.message
                Log.w(TAG, "SecurityException: ${se.message}")
            } catch (e: Exception) {
                statusInternal.value = LocationStatus.Error(e.message ?: "error")
                _lastError.value = e.message
                Log.w(TAG, "open error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Clear and invoke closer to ensure readLine() is unblocked if still pending
                val c = closerRef.getAndSet(null)
                runCatching { c?.invoke() }
                runCatching { handle?.close?.invoke() }
            }

            // Exponential backoff with small jitter; cap at 30s.
            val pow = (attempt - 1).coerceAtMost(5)
            val base = min(30_000L, 1000L * (1L shl pow))
            val jitter = Random.Default.nextLong(0, 300)
            val delayMs = base + jitter
            Log.d(TAG, "retry in ${delayMs}ms (attempt=$attempt)")
            delay(delayMs)
        }
    }

    /**
     * Parsed Fix stream.
     * Altitude is strictly ellipsoidal: if receiver only reports MSL and geoid separation,
     * we derive ellipsoidal = MSL + geoidSeparation.
     */
    fun fixes(): Flow<Fix> = channelFlow {
        val accum = FixAccumulator()

        rawLines().collect { line ->
            val sentence = parser.parse(line) ?: return@collect

            // Feed aggregator; build snapshot on GGA/RMC/GST
            val snapshot = accum.feed(sentence) ?: return@collect

            // Update sky immediately (even if no position yet)
            updateSkyFrom(snapshot)

            val lat = snapshot.lat ?: return@collect
            val lon = snapshot.lon ?: return@collect

            val tsMillis = snapshot.timestampMillis ?: System.currentTimeMillis()
            val instant = Instant.ofEpochMilli(tsMillis)

            val providerEnum = when (_currentType.value) {
                ConnectionType.BT  -> Provider.RS2_BT
                ConnectionType.TCP -> Provider.RS2_TCP
            }
            val rtkStatus = when (snapshot.fixType) {
                NmeaParser.FixType.RTK_FIXED -> RtkStatus.FIX
                NmeaParser.FixType.RTK_FLOAT -> RtkStatus.FLOAT
                NmeaParser.FixType.DGPS      -> RtkStatus.DGPS
                NmeaParser.FixType.SINGLE    -> RtkStatus.SINGLE
                else                         -> RtkStatus.INVALID
            }
            val timestampSourceEnum = when (snapshot.timestampSource) {
                "RMC" -> TimestampSource.NMEA_RMC
                "GGA" -> TimestampSource.NMEA_GGA
                "ZDA" -> TimestampSource.NMEA_ZDA
                null  -> TimestampSource.SYSTEM
                else  -> TimestampSource.SYSTEM
            }
            val diffAgeDuration: Duration? = snapshot.diffAgeSec?.let { it.seconds }

            // Compute ellipsoidal altitude without mixing datums:
            // Prefer altEllipsoidal direct; else if altMSL & geoidSeparation present, derive.
            val altEllip: Double? = snapshot.altEllipsoidal
                ?: snapshot.altMsl?.let { msl ->
                    snapshot.geoidSeparation?.let { msl + it }
                }

            // Infer presence of corrections; type is unknown without extra metadata
            val hasCorrections = when (rtkStatus) {
                RtkStatus.FIX, RtkStatus.FLOAT, RtkStatus.DGPS -> true
                else -> false
            } || (diffAgeDuration != null)
            val inferredCorrectionSource: CorrectionSource? =
                if (hasCorrections) CorrectionSource.UNKNOWN else null

            trySend(
                Fix(
                    lat = lat,
                    lon = lon,
                    altEllipsoidalM = altEllip,
                    hAccM = snapshot.hAccM,
                    vAccM = snapshot.vAccM,
                    accuracyM = snapshot.hAccM,     // deprecated but kept for back-compat (won’t serialize)
                    speedMps = snapshot.speedMps,
                    bearingDeg = snapshot.courseDeg,
                    satsUsed = snapshot.satsUsed,
                    satsVisible = snapshot.satsInView,
                    hdop = snapshot.hdop,
                    pdop = snapshot.pdop,
                    rtkStatus = rtkStatus,
                    timestamp = instant,
                    timestampSource = timestampSourceEnum,
                    diffAge = diffAgeDuration,
                    baseStationId = snapshot.stationId,
                    baselineLengthM = null,
                    correctionSource = inferredCorrectionSource,
                    geoidSeparationM = snapshot.geoidSeparation,
                    crsEpsg = 4326,
                    stdLatM = snapshot.stdLatM,
                    stdLonM = snapshot.stdLonM,
                    stdAltM = snapshot.stdAltM,
                    provider = providerEnum,
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openBt(): StreamHandle? = withContext(Dispatchers.IO) {
        val addr = btAddressProvider() ?: run {
            Log.w(TAG, "openBt: no BT address configured")
            return@withContext null
        }
        if (addr.isBlank()) {
            Log.w(TAG, "openBt: BT address is blank")
            return@withContext null
        }
        Log.d(TAG, "openBt: attempting connection to addr=$addr")

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.w(TAG, "openBt: BluetoothAdapter not available")
            return@withContext null
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "openBt: Bluetooth is disabled")
            return@withContext null
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "openBt: invalid BT address format: $addr")
            return@withContext null
        }

        Log.d(TAG, "openBt: device found, name=${device.name}, bondState=${device.bondState}")

        val uuid = UUID.fromString(SPP_UUID)

        fun tryCreateSocket(): BluetoothSocket? {
            Log.d(TAG, "openBt: trying insecure RFCOMM socket")
            runCatching {
                return device.createInsecureRfcommSocketToServiceRecord(uuid)
            }.onFailure { Log.d(TAG, "openBt: insecure failed: ${it.message}") }

            Log.d(TAG, "openBt: trying secure RFCOMM socket")
            runCatching {
                return device.createRfcommSocketToServiceRecord(uuid)
            }.onFailure { Log.d(TAG, "openBt: secure failed: ${it.message}") }

            Log.d(TAG, "openBt: trying reflection socket")
            return runCatching {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }.onFailure { Log.d(TAG, "openBt: reflection failed: ${it.message}") }
                .getOrNull()
        }

        val socket = tryCreateSocket() ?: run {
            Log.w(TAG, "openBt: failed to create any socket type")
            return@withContext null
        }

        try {
            Log.d(TAG, "openBt: canceling discovery and connecting...")
            runCatching { adapter.cancelDiscovery() }
            socket.connect() // This can block; cancellation closes socket via closerRef hook.
            Log.d(TAG, "openBt: socket connected successfully")
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII))
            StreamHandle(reader, {
                try { reader.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }, ConnectionType.BT)
        } catch (e: Exception) {
            Log.w(TAG, "openBt: connection failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { socket.close() }
            if (e is SecurityException) throw e
            null
        }
    }

    private suspend fun openTcp(): StreamHandle? = withContext(Dispatchers.IO) {
        val (host, port) = tcpHostProvider()
        val hostNorm = host?.trim()?.lowercase(Locale.US)

        if (hostNorm.isNullOrBlank()) {
            Log.w(TAG, "openTcp: host is null or blank")
            return@withContext null
        }
        if (port == null || port <= 0 || port > 65535) {
            Log.w(TAG, "openTcp: invalid port: $port")
            return@withContext null
        }

        val socket = Socket()
        try {
            Log.d(TAG, "openTcp: configuring socket options")
            socket.tcpNoDelay = true
            socket.keepAlive = true

            Log.d(TAG, "openTcp: connecting to $hostNorm:$port with 5s timeout")
            socket.connect(InetSocketAddress(hostNorm, port), 5_000)

            Log.d(TAG, "openTcp: setting SO timeout to 15s")
            socket.soTimeout = 15_000

            Log.d(TAG, "openTcp: TCP connection established to $hostNorm:$port")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))

            Log.d(TAG, "openTcp: reader created, returning StreamHandle")
            StreamHandle(reader, {
                Log.d(TAG, "openTcp: closing connection to $hostNorm:$port")
                try { reader.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }, ConnectionType.TCP)
        } catch (e: Exception) {
            Log.w(TAG, "openTcp: connection to $hostNorm:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { socket.close() }
            null
        }
    }

    companion object {
        private const val TAG = "NmeaSource"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
