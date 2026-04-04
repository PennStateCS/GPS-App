package com.example.surveyingapp.gnss.bus.adapters

import android.content.Context
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.nmea.sentence.*
import com.example.surveyingapp.gnss.parser.NmeaParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import java.time.Instant

/**
 * Wires Android's internal GPS NMEA listener into the InternalAdapter.
 * Similar to ExternalNmeaSource but uses INTERNAL_GPS as provider.
 */
class InternalNmeaSource(
    context: Context,
    private val scope: CoroutineScope
) : NmeaSource {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val parser = NmeaParser()

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1, // Replay last fix for new subscribers
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Implement the NmeaSource interface methods
    override fun parsedFixes(): SharedFlow<Fix> = _fixes
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv

    private var started = false
    private var nmeaListener: OnNmeaMessageListener? = null

    // --- Cached latest sentence data to build Fix ---
    private var lastGga: GGA? = null
    private var lastRmc: RMC? = null
    private var lastGsa: GSA? = null
    private var lastZda: ZDA? = null

    // GSV aggregation
    private var pendingGsvTotal: Int? = null
    private var pendingGsvCollected: MutableList<NmeaParser.Satellite> = mutableListOf()

    @Suppress("DEPRECATION")
    override fun start() {
        if (started) return
        started = true
        android.util.Log.d("InternalNmeaSource", "Starting internal GPS NMEA listener")

        val listener = OnNmeaMessageListener { message, _ ->
            when (val result = parser.parse(message)) {
                is NmeaParser.ParseResult.Success -> handleSentence(result.sentence)
                else -> Unit
            }
        }
        nmeaListener = listener

        // Register the NMEA listener using a Handler bound to the main Looper so the
        // LocationManager will create any required internal Handlers against the main
        // thread even if this method is invoked from a background thread.
        try {
            lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))
            android.util.Log.d("InternalNmeaSource", "NMEA listener added successfully")
        } catch (e: SecurityException) {
            android.util.Log.e("InternalNmeaSource", "Missing location permission", e)
        }
    }

    override fun stop() {
        nmeaListener?.let {
            // Use the main looper handler to ensure removal occurs on the main thread
            try {
                lm.removeNmeaListener(it)
            } catch (_: Exception) {
                // ignore
            }
            android.util.Log.d("InternalNmeaSource", "NMEA listener removed")
        }
        nmeaListener = null
        started = false
    }

    // --------------------------------------------------------------------------
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
            else -> RtkStatus.NONE
        }

        val satsUsed = lastGsa?.usedSvids?.size ?: gga.satsUsed ?: 0

        // Build fix with INTERNAL provider
        val fix = Fix(
            provider = Provider.INTERNAL,  // Use internal provider
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

        android.util.Log.d("InternalNmeaSource", "Emitting fix: lat=${fix.latDeg}, lon=${fix.lonDeg}, sats=$satsUsed")
        scope.launch { _fixes.emit(fix) }
    }
}
