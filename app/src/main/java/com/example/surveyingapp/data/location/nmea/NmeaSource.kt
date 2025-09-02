package com.example.surveyingapp.data.location.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.domain.model.TimestampSource
import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import com.example.surveyingapp.data.location.nmea.accumulator.FixAccumulator
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
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    private val _recentRaw = MutableSharedFlow<String>(replay = 5, extraBufferCapacity = 32)
    val recentRaw: SharedFlow<String> = _recentRaw.asSharedFlow()

    enum class ConnectionType { BT, TCP }

    private data class StreamHandle(val reader: BufferedReader, val close: () -> Unit, val type: ConnectionType)

    fun rawLines(): Flow<String> = channelFlow {
        var attempt = 0
        while (isActive) {
            attempt++
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
                withContext(Dispatchers.IO) {
                    handle.reader.use { r ->
                        attempt = 0
                        while (isActive) {
                            val line = r.readLine() ?: break
                            if (line.isNotEmpty() && line[0] == '$' && line.contains('*') && line.length <= 200) {
                                trySend(line)
                                _recentRaw.tryEmit(line)
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
            } finally { try { handle?.close?.invoke() } catch (_: Exception) {} }
            val pow = (attempt - 1).coerceAtMost(5)
            val base = min(30_000L, 1000L * (1L shl pow))
            val jitter = Random.Default.nextLong(0, 300)
            delay(base + jitter)
        }
    }

    fun fixes(): Flow<Fix> = channelFlow {
        val accum = FixAccumulator()
        rawLines().collect { line ->
            val sentence = parser.parse(line) ?: return@collect
            val snapshot = accum.feed(sentence) ?: return@collect
            val lat = snapshot.lat ?: return@collect
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
                "ZDA" -> TimestampSource.NMEA_RMC
                null -> TimestampSource.SYSTEM
                else -> TimestampSource.SYSTEM
            }
            val diffAgeDuration: Duration? = snapshot.diffAgeSec?.let { it.seconds }
            trySend(
                Fix(
                    lat = lat,
                    lon = lon,
                    altEllipsoidalM = snapshot.altEllipsoidal ?: snapshot.altMsl,
                    hAccM = snapshot.hAccM,
                    vAccM = snapshot.vAccM,
                    accuracyM = snapshot.hAccM,
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
                    correctionSource = null,
                    geoidSeparationM = snapshot.geoidSeparation,
                    provider = providerEnum,
                    crsEpsg = 4326
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openBt(): StreamHandle? = withContext(Dispatchers.IO) {
        val addr = btAddressProvider() ?: return@withContext null
        if (addr.isBlank()) return@withContext null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
        val device: BluetoothDevice = try { adapter.getRemoteDevice(addr) } catch (_: IllegalArgumentException) { return@withContext null }
        val uuid = UUID.fromString(SPP_UUID)
        fun tryCreateSocket(): BluetoothSocket? {
            runCatching { return device.createInsecureRfcommSocketToServiceRecord(uuid) }
            runCatching { return device.createRfcommSocketToServiceRecord(uuid) }
            return runCatching { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket }.getOrNull()
        }
        val socket = tryCreateSocket() ?: return@withContext null
        try {
            runCatching { adapter.cancelDiscovery() }
            socket.connect()
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            StreamHandle(reader, {
                try { reader.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }, ConnectionType.BT)
        } catch (e: Exception) {
            runCatching { socket.close() }; if (e is SecurityException) throw e; null
        }
    }

    private suspend fun openTcp(): StreamHandle? = withContext(Dispatchers.IO) {
        val (host, port) = tcpHostProvider()
        if (host.isNullOrBlank() || port == null) return@withContext null
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            StreamHandle(reader, {
                try { reader.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }, ConnectionType.TCP)
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
