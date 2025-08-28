package com.example.surveyingapp.data.location.nmea

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.surveyingapp.data.location.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.min

/** Streams NMEA from either Bluetooth SPP or TCP and converts to Fix objects. */
class NmeaSource(
    private val btAddressProvider: suspend () -> String?,
    private val tcpHostProvider: suspend () -> Pair<String?, Int?>,
    private val connectionTypeProvider: suspend () -> String // "bt" or "tcp"
) {
    private val parser = NmeaParser()
    private val statusInternal = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val status: Flow<LocationStatus> = statusInternal

    fun fixes(): Flow<Fix> = channelFlow {
        var attempt = 0
        var lastGga: NmeaParser.Gga? = null
        var lastRmc: NmeaParser.Rmc? = null
        while (isActive) {
            attempt++
            statusInternal.value = LocationStatus.Connecting(attempt)
            try {
                val type = connectionTypeProvider()
                val reader = withContext(Dispatchers.IO) {
                    when (type) {
                        "bt" -> openBt()
                        else -> openTcp()
                    }
                } ?: throw IllegalStateException("Unable to open stream")
                statusInternal.value = LocationStatus.Streaming
                reader.use { r ->
                    while (isActive) {
                        val line = r.readLine() ?: break
                        val parsed = parser.parse(line) ?: continue
                        when (parsed) {
                            is NmeaParser.Parsed.GgaMsg -> lastGga = parsed.gga
                            is NmeaParser.Parsed.RmcMsg -> lastRmc = parsed.rmc
                        }
                        val g = lastGga
                        val m = lastRmc
                        if (g?.lat != null && g.lon != null) {
                            trySend(
                                Fix(
                                    lat = g.lat,
                                    lon = g.lon,
                                    altEllipsoidalM = g.alt,
                                    speedMps = m?.speedKnots?.let { it * 0.514444 },
                                    bearingDeg = m?.course,
                                    satsUsed = g.sats,
                                    hdop = g.hdop,
                                    rtkStatus = mapQuality(g.fixQuality),
                                    timestamp = System.currentTimeMillis(),
                                    provider = if (type == "bt") "rs2-bt" else "rs2-tcp"
                                )
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) { throw ce } catch (e: Exception) {
                Log.w(TAG, "NMEA connection failure: ${e.message}")
                statusInternal.value = LocationStatus.Error(e.message ?: "error")
            }
            val backoffMs = min(30_000L, 1000L * (1 shl (attempt.coerceAtMost(5))))
            delay(backoffMs)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun openBt(): BufferedReader? = withContext(Dispatchers.IO) {
        val addr = btAddressProvider() ?: return@withContext null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
        val device: BluetoothDevice = adapter.getRemoteDevice(addr)
        val uuid = UUID.fromString(SPP_UUID)
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
        try {
            adapter.cancelDiscovery()
            socket.connect()
            BufferedReader(InputStreamReader(socket.inputStream))
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            null
        }
    }

    private suspend fun openTcp(): BufferedReader? = withContext(Dispatchers.IO) {
        val (host, port) = tcpHostProvider()
        if (host.isNullOrBlank() || port == null) return@withContext null
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            BufferedReader(InputStreamReader(socket.getInputStream()))
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            null
        }
    }

    private fun mapQuality(q: Int?): RtkStatus? = when (q) {
        4 -> RtkStatus.FIX
        5 -> RtkStatus.FLOAT
        2 -> RtkStatus.DGPS
        1 -> RtkStatus.SINGLE
        else -> RtkStatus.INVALID
    }

    companion object { private const val TAG = "NmeaSource"; private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB" }
}
