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
import java.time.Instant

/**
 * LEGACY: Wires Android's internal GPS NMEA listener.
 * @deprecated This class is misnamed and unused. Use InternalNmeaSource instead.
 * This uses LocationManager which is for INTERNAL GPS, not external devices.
 */
@Deprecated("Use InternalNmeaSource instead")
class AndroidNmeaSourceLegacy(
    context: Context,
    private val scope: CoroutineScope
) : NmeaSource {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val parser = NmeaParser()

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
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

    // GSV aggregation (one "epoch" can span multiple messages)
    private var pendingGsvTotal: Int? = null
    private var pendingGsvCollected: MutableList<NmeaParser.Satellite> = mutableListOf()

    @Suppress("DEPRECATION")
    override fun start() {
        if (started) return
        started = true

        val listener = OnNmeaMessageListener { message, _ ->
            when (val result = parser.parse(message)) {
                is NmeaParser.ParseResult.Success -> handleSentence(result.sentence)
                else -> Unit
            }
        }
        nmeaListener = listener
        try {
            lm.addNmeaListener(listener)
        } catch (_: SecurityException) {
            // Permissions missing; silently ignore for now
        }
    }

    override fun stop() {
        nmeaListener?.let { runCatching { lm.removeNmeaListener(it) } }
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

        // Convert GSVSatellite to NmeaParser.Satellite format
        val satellites = gsv.satellites.mapNotNull { gsvSat ->
            gsvSat.svid?.let { svid ->
                NmeaParser.Satellite(
                    prn = svid,
                    constellation = com.example.surveyingapp.gnss.model.Constellation.GPS, // Default to GPS
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

        // Determine timestamp and source with proper priority:
        // 1. ZDA epochMillis (most precise)
        // 2. RMC epochMillis (has date + time)
        // 3. GGA time (time only, use current date)
        // 4. Device time (fallback)
        val (epochMs, tsSource) = when {
            lastZda?.epochMillis != null -> {
                val zda = lastZda!! // Safe cast after null check
                Pair(zda.epochMillis!!, TimestampSource.NMEA_ZDA)
            }
            lastRmc?.epochMillis != null -> {
                val rmc = lastRmc!! // Safe cast after null check
                Pair(rmc.epochMillis!!, TimestampSource.GNSS_PROVIDER)
            }
            gga.timeRaw != null -> {
                // GGA has time but no date, use current system time but mark as GNSS_PROVIDER
                Pair(System.currentTimeMillis(), TimestampSource.GNSS_PROVIDER)
            }
            else -> {
                Pair(System.currentTimeMillis(), TimestampSource.DEVICE)
            }
        }

        // ACCUMULATOR CALCULATION: ellipsoidal = MSL + geoid separation
        // This is the only place where ellipsoidal altitude should be calculated
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

        // Get satellite count from GSA or GGA
        val satsUsed = lastGsa?.usedSvids?.size ?: gga.satsUsed ?: 0

        // Build fix with accumulator-calculated ellipsoidal altitude
        val fix = Fix(
            provider = Provider.RS2_EXTERNAL,
            timeUtc = Instant.ofEpochMilli(epochMs),
            timestampSource = tsSource,
            latDeg = lat,
            lonDeg = lon,
            altEllipsoidalM = altEllipsoidal,  // Calculated here in accumulator
            altMslM = gga.altMsl,        // Raw from parser
            geoidSeparationM = gga.geoidSeparation, // Raw from parser
            hDop = gga.hdop,
            vDop = null,  // Not available in current sentence structure
            pDop = null,  // Not available in current sentence structure
            hAccM = null, // Would come from GST if available
            vAccM = null, // Would come from GST if available
            rtkStatus = rtk,
            satsUsed = satsUsed,
            satsVisible = pendingGsvCollected.size.takeIf { it > 0 },
            diffAgeS = gga.diffAge,
            speedMps = lastRmc?.speedKnots?.let { knots -> knots * 0.514444 }, // Convert knots to m/s
            courseDeg = lastRmc?.courseDeg
        )

        scope.launch { _fixes.emit(fix) }
    }
}
