package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.nmea.sentence.*
import java.time.Instant

/**
 * Stateful NMEA sentence fuser.
 *
 * Accepts raw NMEA lines, parses them via [NmeaRegistry], accumulates GGA/RMC/GSA/GSV/ZDA
 * sentences, and fires [onFix] / [onGsv] whenever a new position epoch is ready.
 *
 * **Not thread-safe**: all calls to [accept] must originate from the same thread, or callers
 * must provide their own synchronisation.
 *
 * Extracted from the duplicated logic that previously lived in both [InternalNmeaSource] and
 * [TcpNmeaSource]. Adding support for a new external provider requires only a new
 * [SourceAdapter] implementation that creates its own [NmeaFuser] with the appropriate
 * [Provider] value — no changes to the fuser itself are needed.
 *
 * @param provider   Which GNSS provider produced this data (stamped onto every [Fix]).
 * @param registry   Shared [NmeaRegistry] that performs checksum-verified sentence parsing.
 * @param onFix      Invoked synchronously when a complete position epoch is assembled.
 * @param onGsv      Invoked synchronously when a full GSV multi-message epoch completes.
 */
class NmeaFuser(
    private val provider: Provider,
    private val registry: NmeaRegistry,
    private val onFix: (Fix) -> Unit,
    private val onGsv: (GsvMessage) -> Unit
) {
    private var lastGga: GGA? = null
    private var lastRmc: RMC? = null
    private var lastGsa: GSA? = null
    private var lastZda: ZDA? = null
    private var lastGst: GST? = null   // GPS Error Statistics (accuracy ellipse)

    // GSV multi-message aggregation
    private var pendingGsvTotal: Int? = null
    private val pendingGsvSats: MutableList<GSVSatellite> = mutableListOf()

    /** Feed one raw NMEA line into the fuser. Silently ignores unparseable lines. */
    fun accept(line: String) {
        val sentence = try { registry.parse(line) } catch (_: Exception) { return } ?: return
        handle(sentence)
    }

    private fun handle(sentence: NmeaSentence) {
        when (sentence) {
            is GGA -> { lastGga = sentence; maybeEmitFix() }
            is RMC -> { lastRmc = sentence; if (lastGga != null) maybeEmitFix() }
            is GSA -> lastGsa = sentence
            is GSV -> handleGsv(sentence)
            is ZDA -> lastZda = sentence
            is GST -> lastGst = sentence  // store accuracy ellipse; used in next fix emission
            else   -> Unit // unknown sentence type — fuser is tolerant by design
        }
    }

    private fun handleGsv(gsv: GSV) {
        val total = gsv.totalMessages ?: 1
        val num   = gsv.messageNumber ?: total

        if (num == 1) {
            // Start of a new GSV epoch; discard any partial previous epoch
            pendingGsvTotal = total
            pendingGsvSats.clear()
        }
        pendingGsvSats.addAll(gsv.satellites)

        if (num >= total || pendingGsvTotal == null) {
            val used = lastGsa?.usedSvids ?: emptyList()
            val entries = pendingGsvSats.mapNotNull { s ->
                s.svid?.let { svid ->
                    GsvEntry(
                        svid         = svid,
                        elevationDeg = s.elevationDeg,
                        azimuthDeg   = s.azimuthDeg,
                        snrDbHz      = s.snrDb?.toDouble(),
                        usedInFix    = used.contains(svid)
                    )
                }
            }
            // Use the talker ID (e.g. "GP", "GL", "GA", "GB", "GN") as the constellation label so
            // callers can distinguish GPS, GLONASS, Galileo, BeiDou, and multi-constellation epochs.
            val label = if (entries.isEmpty()) "UNKNOWN" else gsv.talker
            onGsv(GsvMessage(label, entries))
            pendingGsvTotal = null
            pendingGsvSats.clear()
        }
    }

    private fun maybeEmitFix() {
        val gga = lastGga ?: return
        val lat = gga.lat ?: return
        val lon = gga.lon ?: return

        // Timestamp priority: ZDA (full date+time) > RMC (date+time) > device clock
        val (epochMs, tsSource) = when {
            lastZda?.epochMillis != null -> lastZda!!.epochMillis!! to TimestampSource.NMEA_ZDA
            lastRmc?.epochMillis != null -> lastRmc!!.epochMillis!! to TimestampSource.GNSS_PROVIDER
            else                         -> System.currentTimeMillis() to TimestampSource.DEVICE
        }

        val altEllipsoidal = sumIfFinite(gga.altMsl, gga.geoidSeparation)

        val rtk = when (gga.fixQuality) {
            1    -> RtkStatus.SINGLE
            2    -> RtkStatus.DGPS
            4    -> RtkStatus.FIX
            5    -> RtkStatus.FLOAT
            else -> RtkStatus.NONE
        }

        // Prefer GSA satellite count (includes SVIDs actually used), fall back to GGA's field
        val satsUsed = lastGsa?.usedSvids?.size ?: gga.satsUsed ?: 0

        // Derive 1-sigma circular horizontal accuracy (DRMS) from GST error ellipse axes
        val hAccM = drmsAccuracy(lastGst?.stdDevMajor, lastGst?.stdDevMinor)
        val vAccM = lastGst?.stdDevAlt

        onFix(
            Fix(
                provider            = provider,
                timeUtc             = Instant.ofEpochMilli(epochMs),
                timestampSource     = tsSource,
                latDeg              = lat,
                lonDeg              = lon,
                altEllipsoidalM     = altEllipsoidal,
                altMslM             = gga.altMsl,
                geoidSeparationM    = gga.geoidSeparation,
                hDop                = gga.hdop,
                vDop                = lastGsa?.vdop,
                pDop                = lastGsa?.pdop,
                hAccM               = hAccM,
                vAccM               = vAccM,
                rtkStatus           = rtk,
                satsUsed            = satsUsed,
                satsVisible         = pendingGsvSats.size.takeIf { it > 0 },
                diffAgeS            = gga.diffAge,
                speedMps            = lastRmc?.speedKnots?.let { it * 0.514444 },
                courseDeg           = lastRmc?.courseDeg,
                correctionStationId = gga.stationId
            )
        )
    }

    /** Clears all accumulated sentence state. Call when the underlying GNSS source is stopped. */
    fun reset() {
        lastGga = null; lastRmc = null; lastGsa = null; lastZda = null; lastGst = null
        pendingGsvTotal = null; pendingGsvSats.clear()
    }

    /**
     * 1-sigma circular horizontal accuracy (DRMS) from GST error ellipse semi-axes.
     * DRMS = sqrt((σ_major² + σ_minor²) / 2) correctly handles elongated ellipses.
     */
    private fun drmsAccuracy(major: Double?, minor: Double?): Double? {
        if (major == null || minor == null) return null
        return Math.sqrt((major * major + minor * minor) / 2.0)
    }

    private fun sumIfFinite(a: Double?, b: Double?): Double? =
        if (a != null && a.isFinite() && b != null && b.isFinite()) a + b else null
}

