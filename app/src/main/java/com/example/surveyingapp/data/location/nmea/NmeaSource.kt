package com.example.surveyingapp.data.location.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.surveyingapp.data.location.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * NmeaSource
 * ----------
 * Streams NMEA lines from either:
 *   - Bluetooth SPP (RS2+ over classic Bluetooth), or
 *   - TCP socket (RS2+ TCP server like 5000/9001)
 *
 * It exposes:
 *   - raw NMEA lines (Flow<String>)
 *   - parsed Fix objects (Flow<Fix>)
 *   - connection status + last error + attempt count (Flows)
 */
class NmeaSource(
    private val btAddressProvider: suspend () -> String?,
    private val tcpHostProvider: suspend () -> Pair<String?, Int?>,
    private val connectionTypeProvider: suspend () -> ConnectionType // prefer enum over raw string
) {
    // Core NMEA parser (turns raw lines into typed sentence objects)
    private val parser = NmeaParser()

    // Status flows for UI
    private val statusInternal = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val status: StateFlow<LocationStatus> = statusInternal.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Remember which transport we’re using so we can tag Fix.source
    private val _currentType = MutableStateFlow(ConnectionType.BT)
    val currentType: StateFlow<ConnectionType> = _currentType.asStateFlow()

    // Small rolling buffer of raw NMEA lines (for diagnostics)
    private val _recentRaw = MutableSharedFlow<String>(replay = 5, extraBufferCapacity = 32)
    val recentRaw: SharedFlow<String> = _recentRaw.asSharedFlow()

    /** How we identify the transport in a type-safe way. */
    enum class ConnectionType { BT, TCP }

    /** Internal: a handle we can close to break the blocking read loop. */
    private data class StreamHandle(
        val reader: BufferedReader,
        val close: () -> Unit,
        val type: ConnectionType
    )

    /**
     * Emits raw NMEA lines with auto-reconnect.
     * Cancellation note: closing the StreamHandle will interrupt readLine() on most streams.
     */
    fun rawLines(): Flow<String> = channelFlow {
        var attempt = 0
        while (isActive) {
            attempt++ // count each (re)connection attempt
            _attemptCount.value = attempt
            statusInternal.value = LocationStatus.Connecting(attempt)

            var handle: StreamHandle? = null
            try {
                val kind = connectionTypeProvider()
                _currentType.value = kind
                handle = withContext(Dispatchers.IO) {
                    when (kind) {
                        ConnectionType.BT -> openBt()
                        ConnectionType.TCP -> openTcp()
                    }
                } ?: throw IllegalStateException("Unable to open ${kind.name} stream")

                statusInternal.value = LocationStatus.Streaming

                // Blocking read loop (IO dispatcher)
                withContext(Dispatchers.IO) {
                    handle.reader.use { r ->
                        // successful connection -> reset backoff attempts
                        attempt = 0
                        while (isActive) {
                            val line = r.readLine() ?: break // null => stream closed
                            // Simple sanity filter: only forward plausible NMEA
                            if (line.isNotEmpty() && line[0] == '$' && line.contains('*') && line.length <= 200) {
                                trySend(line)      // emit to downstream collectors
                                _recentRaw.tryEmit(line) // also store in recent buffer
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                statusInternal.value = LocationStatus.Error("bt permission")
                _lastError.value = se.message
            } catch (e: Exception) {
                statusInternal.value = LocationStatus.Error(e.message ?: "error")
                _lastError.value = e.message
            } finally {
                // Always close the stream/socket so readLine() unblocks
                try { handle?.close?.invoke() } catch (_: Exception) {}
            }

            // Exponential backoff with small jitter: 1s, 2s, 4s, 8s, 16s, 32s (cap 30s)
            val pow = (attempt - 1).coerceAtMost(5)
            val base = min(30_000L, 1000L * (1L shl pow))
            val jitter = Random.Default.nextLong(0, 300) // up to 300ms
            delay(base + jitter)
        }
    }

    /**
     * Emits high-level Fix objects (good for UI).
     * We keep the same mapping logic you had (GGA + RMC + optional DOPs).
     *
     * Note: If you move to the new NmeaParser.FixAccumulator, you can replace this with that.
     */
    fun fixes(): Flow<Fix> = channelFlow {
        val accum = NmeaParser.FixAccumulator()
        rawLines().collect { line ->
            val sentence = parser.parse(line) ?: return@collect // drop if invalid / unsupported
            val snapshot = accum.feed(sentence) ?: return@collect // only proceed when snapshot updated
            val lat = snapshot.lat ?: return@collect // need position
            val lon = snapshot.lon ?: return@collect
            val tsMillis = snapshot.timestampMillis ?: System.currentTimeMillis()
            val instant = Instant.ofEpochMilli(tsMillis)
            val providerEnum = when (_currentType.value) {
                ConnectionType.BT -> Provider.RS2_BT
                ConnectionType.TCP -> Provider.RS2_TCP
            }
            val rtkStatus = when (snapshot.fixType) {
                NmeaParser.FixType.RTK_FIXED -> RtkStatus.FIX
                NmeaParser.FixType.RTK_FLOAT -> RtkStatus.FLOAT
                NmeaParser.FixType.DGPS -> RtkStatus.DGPS
                NmeaParser.FixType.SINGLE -> RtkStatus.SINGLE
                else -> RtkStatus.INVALID
            }
            val timestampSourceEnum = when (snapshot.timestampSource) {
                "RMC" -> TimestampSource.NMEA_RMC
                "GGA" -> TimestampSource.NMEA_GGA
                // Map ZDA to RMC bucket (no dedicated enum) else fallback
                "ZDA" -> TimestampSource.NMEA_RMC
                null -> TimestampSource.SYSTEM
                else -> TimestampSource.SYSTEM
            }
            val diffAgeDuration: Duration? = snapshot.diffAgeSec?.let { it.seconds }
            val fix = Fix(
                lat = lat,
                lon = lon,
                altEllipsoidalM = snapshot.altEllipsoidal ?: snapshot.altMsl,
                hAccM = snapshot.hAccM,
                vAccM = snapshot.vAccM,
                accuracyM = snapshot.hAccM, // mirror horiz accuracy for legacy consumers
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
                baseStationId = snapshot.stationId, // surfaced from GGA station ID
                baselineLengthM = null,
                correctionSource = null,
                geoidSeparationM = snapshot.geoidSeparation,
                provider = providerEnum,
                crsEpsg = 4326
            )
            trySend(fix) // emit assembled Fix
        }
    }

    // ------------------------------------------------------------
    // Connection openers (return a StreamHandle so we can close it)
    // ------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller must have BLUETOOTH_CONNECT on 12+ and classic BT perms
    private suspend fun openBt(): StreamHandle? = withContext(Dispatchers.IO) {
        val addr = btAddressProvider() ?: return@withContext null
        if (addr.isBlank()) return@withContext null // no address configured

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(addr)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }

        val uuid = UUID.fromString(SPP_UUID)

        fun tryCreateSocket(): BluetoothSocket? {
            // Try insecure first (common for GNSS receivers lacking pairing UI)
            runCatching { return device.createInsecureRfcommSocketToServiceRecord(uuid) }
                .onFailure { Log.w(TAG, "Insecure RFCOMM create failed: ${it.message}") }
            // Fallback to secure
            runCatching { return device.createRfcommSocketToServiceRecord(uuid) }
                .onFailure { Log.w(TAG, "Secure RFCOMM create failed: ${it.message}") }
            // Final fallback (reflection, channel 1)
            return runCatching {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
            }.onFailure { Log.w(TAG, "Reflective RFCOMM create failed: ${it.message}") }.getOrNull()
        }

        val socket = tryCreateSocket() ?: return@withContext null // give up if we cannot allocate

        try {
            // Discovery slows connect or fails; best-effort cancel (may require BLUETOOTH_CONNECT)
            runCatching { adapter.cancelDiscovery() }

            // Blocking connect (no socket timeout available on classic BT)
            socket.connect() // blocking; may throw
            Log.i(TAG, "BT connected to ${device.name} (${device.address})")

            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            // Return a handle whose close() will close the socket and interrupt readLine()
            StreamHandle(
                reader = reader,
                close = {
                    // Close in reverse order; swallow exceptions
                    try { reader.close() } catch (_: Exception) {}
                    try { socket.close() } catch (_: Exception) {}
                },
                type = ConnectionType.BT
            )
        } catch (e: Exception) {
            Log.w(TAG, "BT connect failed: ${e.message}")
            runCatching { socket.close() }
            if (e is SecurityException) throw e
            null
        }
    }

    private suspend fun openTcp(): StreamHandle? = withContext(Dispatchers.IO) {
        val (host, port) = tcpHostProvider()
        if (host.isNullOrBlank() || port == null) return@withContext null // config incomplete

        val socket = Socket()
        try {
            // Make TCP responsive and resilient
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), 5_000) // connect timeout
            socket.soTimeout = 15_000                            // read timeout (detect silent stalls)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            StreamHandle(
                reader = reader,
                close = {
                    // Close input + socket; ignore errors
                    try { reader.close() } catch (_: Exception) {}
                    try { socket.close() } catch (_: Exception) {}
                },
                type = ConnectionType.TCP
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    companion object {
        private const val TAG = "NmeaSource"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
