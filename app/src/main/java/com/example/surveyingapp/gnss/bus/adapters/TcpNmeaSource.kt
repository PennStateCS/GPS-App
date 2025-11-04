package com.example.surveyingapp.gnss.bus.adapters

import android.util.Log
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.nmea.sentence.*
import com.example.surveyingapp.gnss.parser.NmeaParser
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
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * NmeaSource implementation that reads NMEA data from RS2+ receiver via TCP.
 * Observes TCP settings from SettingsRepository and connects/parses automatically.
 */
class TcpNmeaSource(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository
) : NmeaSource {

    companion object {
        private const val TAG = "TcpNmeaSource"
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private val parser = NmeaParser()

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun parsedFixes(): SharedFlow<Fix> = _fixes
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv

    private var connectionJob: Job? = null
    private var started = false

    // Cached latest sentence data to build Fix
    private var lastGga: GGA? = null
    private var lastRmc: RMC? = null
    private var lastGsa: GSA? = null
    private var lastZda: ZDA? = null

    // GSV aggregation
    private var pendingGsvTotal: Int? = null
    private var pendingGsvCollected: MutableList<NmeaParser.Satellite> = mutableListOf()

    override fun start() {
        if (started) return
        started = true
        Log.d(TAG, "Starting TCP NMEA source")

        connectionJob = scope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                try {
                    // Get current TCP settings
                    val host = settingsRepository.externalTcpHost.first()
                    val port = settingsRepository.externalTcpPort.first()

                    if (host.isNullOrBlank() || port == null) {
                        Log.w(TAG, "No TCP host/port configured, waiting...")
                        kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
                        continue
                    }

                    Log.d(TAG, "Connecting to $host:$port")
                    connectAndRead(host, port)

                } catch (e: Exception) {
                    if (coroutineContext.isActive) {
                        Log.e(TAG, "Connection error, will retry: ${e.message}")
                        kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
                    }
                }
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping TCP NMEA source")
        started = false
        connectionJob?.cancel()
        connectionJob = null

        // Clear cached data
        lastGga = null
        lastRmc = null
        lastGsa = null
        lastZda = null
        pendingGsvTotal = null
        pendingGsvCollected.clear()
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

            reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8),
                1024
            )

            while (coroutineContext.isActive) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "Connection closed by remote")
                        break
                    }

                    val cleanLine = line.trim()
                    if (cleanLine.isNotEmpty() && cleanLine.startsWith("$")) {
                        parseLine(cleanLine)
                    }

                } catch (e: SocketTimeoutException) {
                    // Continue reading after timeout
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

    private fun parseLine(line: String) {
        when (val result = parser.parse(line)) {
            is NmeaParser.ParseResult.Success -> handleSentence(result.sentence)
            is NmeaParser.ParseResult.Error -> {
                // Ignore parse errors silently (could be unsupported sentence type)
                Log.v(TAG, "Parse error: ${result.message}")
            }
        }
    }

    private fun handleSentence(sentence: NmeaSentence) {
        when (sentence) {
            is GGA -> { lastGga = sentence; maybeEmitFix() }
            is RMC -> { lastRmc = sentence; if (lastGga != null) maybeEmitFix() }
            is GSA -> lastGsa = sentence
            is GSV -> handleGsv(sentence)
            is ZDA -> lastZda = sentence
        }
    }

    private fun handleGsv(gsv: GSV) {
        val total = gsv.totalMessages ?: 1
        val num = gsv.messageNumber ?: total

        if (num == 1) {
            pendingGsvTotal = total
            pendingGsvCollected = mutableListOf()
        }

        val satellites = gsv.satellites.mapNotNull { gsvSat ->
            gsvSat.svid?.let { svid ->
                NmeaParser.Satellite(
                    prn = svid,
                    constellation = com.example.surveyingapp.gnss.model.Constellation.GPS,
                    elevationDeg = gsvSat.elevationDeg?.toDouble(),
                    azimuthDeg = gsvSat.azimuthDeg?.toDouble(),
                    cn0DbHz = gsvSat.snrDb?.toDouble()
                )
            }
        }
        pendingGsvCollected.addAll(satellites)

        val shouldEmit = (num >= total) || pendingGsvTotal == null
        if (shouldEmit) {
            val used = lastGsa?.usedSvids ?: emptyList()
            val entries = pendingGsvCollected.map { sat ->
                GsvEntry(
                    svid = sat.prn,
                    elevationDeg = sat.elevationDeg?.toInt(),
                    azimuthDeg = sat.azimuthDeg?.toInt(),
                    snrDbHz = sat.cn0DbHz,
                    usedInFix = used.contains(sat.prn)
                )
            }
            val constellationLabel = if (entries.isNotEmpty()) {
                val constNames = pendingGsvCollected.map { it.constellation.name }.distinct()
                if (constNames.size == 1) constNames.first() else "MIXED"
            } else "UNKNOWN"

            scope.launch { _gsv.emit(GsvMessage(constellationLabel, entries)) }
            pendingGsvTotal = null
            pendingGsvCollected.clear()
        }
    }

    private fun maybeEmitFix() {
        val gga = lastGga ?: return
        val lat = gga.lat ?: return
        val lon = gga.lon ?: return

        // Determine timestamp and source
        val (epochMs, tsSource) = when {
            lastZda?.epochMillis != null -> {
                Pair(lastZda!!.epochMillis!!, TimestampSource.NMEA_ZDA)
            }
            lastRmc?.epochMillis != null -> {
                Pair(lastRmc!!.epochMillis!!, TimestampSource.GNSS_PROVIDER)
            }
            else -> {
                Pair(System.currentTimeMillis(), TimestampSource.DEVICE)
            }
        }

        // Calculate ellipsoidal altitude
        val altEllipsoidal = if (gga.altMsl != null && gga.geoidSeparation != null) {
            gga.altMsl + gga.geoidSeparation
        } else {
            null
        }

        // Map GGA fix quality to RTK status
        val rtk = when (gga.fixQuality) {
            1 -> RtkStatus.SINGLE
            2 -> RtkStatus.DGPS
            4 -> RtkStatus.FIX
            5 -> RtkStatus.FLOAT
            else -> RtkStatus.NONE
        }

        val satsUsed = lastGsa?.usedSvids?.size ?: gga.satsUsed ?: 0

        // Build fix with RS2_EXTERNAL provider
        val fix = Fix(
            provider = Provider.RS2_EXTERNAL,
            timeUtc = Instant.ofEpochMilli(epochMs),
            timestampSource = tsSource,
            latDeg = lat,
            lonDeg = lon,
            altEllipsoidalM = altEllipsoidal,
            altMslM = gga.altMsl,
            geoidSeparationM = gga.geoidSeparation,
            hDop = gga.hdop,
            vDop = lastGsa?.vdop,
            pDop = lastGsa?.pdop,
            hAccM = null,
            vAccM = null,
            speedMps = lastRmc?.speedKnots?.let { it * 0.514444 },
            courseDeg = lastRmc?.courseDeg,
            satsUsed = satsUsed,
            satsVisible = null,
            rtkStatus = rtk,
            diffAgeS = gga.diffAge,
            correctionStationId = gga.stationId
        )

        Log.d(TAG, "Emitting fix: lat=${fix.latDeg}, lon=${fix.lonDeg}, rtk=${fix.rtkStatus}, sats=$satsUsed")
        scope.launch { _fixes.emit(fix) }
    }
}

