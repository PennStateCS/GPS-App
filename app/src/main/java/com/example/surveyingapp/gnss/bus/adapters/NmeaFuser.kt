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
    /** Constellation-aware satellite ID to prevent SVID collisions across GNSS systems. */
    private data class SatId(val talker: String, val svid: Int)

    private var lastGga: GGA? = null
    private var lastRmc: RMC? = null
    private var lastGsa: GSA? = null
    private var lastZda: ZDA? = null
    private var lastGst: GST? = null   // GPS Error Statistics (accuracy ellipse)

    // Epoch deduplication: track last emitted GGA timestamp to prevent duplicate fix emissions
    private var lastEmittedGgaTimeUtc: Long? = null
    private val EPOCH_DEDUP_WINDOW_MS = 500L  // Treat fixes within 500ms as duplicate epochs

    // GSA multi-constellation accumulation: receivers emit one GSA per constellation (GP, GL, GA, GB, etc.)
    private val gsaUsedSatIds: MutableSet<SatId> = mutableSetOf()
    private var lastGsaUpdateMs: Long = 0L
    private val GSA_EPOCH_TIMEOUT_MS = 2000L  // Clear accumulated SatIds if no new GSA for 2 seconds

    // GSV multi-message aggregation: track per talker to handle interleaved constellations
    private val pendingGsvByTalker: MutableMap<String, PendingGsvEpoch> = mutableMapOf()

    /**
     * Track visible satellite count per constellation (talker).
     * Updated each time a GSV epoch completes. Summed to get total satsVisible for Fix.
     */
    private val gsvVisibleByTalker: MutableMap<String, Int> = mutableMapOf()

    private data class PendingGsvEpoch(
        var totalMessages: Int,
        val satellites: MutableList<GSVSatellite>
    )

    /** Feed one raw NMEA line into the fuser. Silently ignores unparseable lines. */
    fun accept(line: String) {
        val sentence = try { registry.parse(line) } catch (_: Exception) { return } ?: return
        handle(sentence)
    }

    private fun handle(sentence: NmeaSentence) {
        when (sentence) {
            is GGA -> { lastGga = sentence; maybeEmitFix() }
            is RMC -> lastRmc = sentence  // Enrich with speed/course; do not trigger fix emission
            is GSA -> handleGsa(sentence)
            is GSV -> handleGsv(sentence)
            is ZDA -> handleZda(sentence)
            is GST -> lastGst = sentence  // store accuracy ellipse; used in next fix emission
            else   -> Unit // unknown sentence type — fuser is tolerant by design
        }
    }

    private fun handleZda(zda: ZDA) {
        // Only update if timestamp is reasonable (simple staleness check)
        val epochMs = zda.epochMillis
        if (epochMs != null) {
            val ageSec = (System.currentTimeMillis() - epochMs) / 1000.0
            if (ageSec in -5.0..10.0) {  // Allow 5s future tolerance for clock skew, 10s past
                lastZda = zda
            } else {
                android.util.Log.w("NmeaFuser", "Stale ZDA ignored: age=${ageSec}s")
            }
        }
    }

    private fun handleGsa(gsa: GSA) {
        lastGsa = gsa  // Keep last GSA for DOP values

        // Clear stale accumulated SatIds if timeout exceeded
        val now = System.currentTimeMillis()
        if (now - lastGsaUpdateMs > GSA_EPOCH_TIMEOUT_MS) {
            gsaUsedSatIds.clear()
            android.util.Log.d("NmeaFuser", "GSA epoch timeout: cleared accumulated SatIds")
        }
        lastGsaUpdateMs = now

        // Accumulate SatIds from all constellations (GPGSA, GLGSA, GAGSA, GBGSA, etc.)
        if (gsa.usedSvids.isNotEmpty()) {
            val sizeBefore = gsaUsedSatIds.size
            gsa.usedSvids.forEach { svid ->
                gsaUsedSatIds.add(SatId(gsa.talker, svid))
            }
            android.util.Log.d("NmeaFuser", "GSA (${gsa.talker}): added ${gsa.usedSvids.size} satellites → total ${gsaUsedSatIds.size} (was $sizeBefore)")
        }
    }

    private fun handleGsv(gsv: GSV) {
        val talker = gsv.talker
        val total = gsv.totalMessages ?: 1
        val num   = gsv.messageNumber ?: total

        // Get or create pending epoch for this talker
        val pending = pendingGsvByTalker.getOrPut(talker) {
            PendingGsvEpoch(totalMessages = total, satellites = mutableListOf())
        }

        if (num == 1) {
            // Start of a new GSV epoch for this talker; reset its accumulator
            pending.totalMessages = total
            pending.satellites.clear()
            android.util.Log.w("NmeaFuser", "⭐ GSV EPOCH START: Talker=$talker, TotalMsgs=$total, TotalSats=${gsv.totalSatellites}")
        }

        pending.satellites.addAll(gsv.satellites)
        android.util.Log.d("NmeaFuser", "GSV msg ${num}/${total} ($talker): ${gsv.satellites.size} sats, accumulated ${pending.satellites.size} sats, Details: ${gsv.satellites.joinToString { "SVID=${it.svid} SNR=${it.snrDb}dB EL=${it.elevationDeg}° AZ=${it.azimuthDeg}°" }}")

        if (num >= total) {
            // Complete epoch for this talker
            val entries = pending.satellites.mapNotNull { s ->
                s.svid?.let { svid ->
                    val satId = SatId(talker, svid)
                    // Check if satellite is used: first check specific talker, then fallback to GN (combined GNSS)
                    val isUsed = gsaUsedSatIds.contains(satId) ||
                                 gsaUsedSatIds.contains(SatId("GN", svid))
                    GsvEntry(
                        svid         = svid,
                        elevationDeg = s.elevationDeg,
                        azimuthDeg   = s.azimuthDeg,
                        snrDbHz      = s.snrDb?.toDouble(),
                        usedInFix    = isUsed
                    )
                }
            }
            val nullSvidCount = pending.satellites.count { it.svid == null }
            if (nullSvidCount > 0) {
                android.util.Log.w("NmeaFuser", "GSV epoch complete ($talker): $nullSvidCount satellites skipped due to null SVID")
            }

            // Update visible satellite count for this constellation
            val visibleCount = gsv.totalSatellites ?: pending.satellites.size
            gsvVisibleByTalker[talker] = visibleCount

            val label = if (entries.isEmpty()) "UNKNOWN" else talker
            val totalVisible = gsvVisibleByTalker.values.sum()
            android.util.Log.d("NmeaFuser", "GSV epoch complete: $label, ${entries.size} entries, ${entries.count { it.usedInFix }} used | Total visible across all constellations: $totalVisible")
            onGsv(GsvMessage(label, entries))

            // Clear this talker's epoch (ready for next one)
            pendingGsvByTalker.remove(talker)
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

        // Epoch deduplication: skip if we already emitted this timestamp recently
        val lastEmitted = lastEmittedGgaTimeUtc
        if (lastEmitted != null && kotlin.math.abs(epochMs - lastEmitted) < EPOCH_DEDUP_WINDOW_MS) {
            android.util.Log.v("NmeaFuser", "Duplicate epoch skipped: ${epochMs}ms (within ${EPOCH_DEDUP_WINDOW_MS}ms of last)")
            return
        }
        lastEmittedGgaTimeUtc = epochMs

        val altEllipsoidal = sumIfFinite(gga.altMsl, gga.geoidSeparation)

        val rtk = when (gga.fixQuality) {
            1    -> RtkStatus.SINGLE
            2    -> RtkStatus.DGPS
            4    -> RtkStatus.FIX
            5    -> RtkStatus.FLOAT
            else -> RtkStatus.NONE
        }

        // Prefer accumulated constellation-aware satellite count, fall back to GGA's field
        val satsUsed = if (gsaUsedSatIds.isNotEmpty()) gsaUsedSatIds.size else (gga.satsUsed ?: 0)

        // Sum visible satellites across all constellations (from completed GSV epochs)
        val satsVisible = gsvVisibleByTalker.values.sum().takeIf { it > 0 }

        // Derive 1-sigma circular horizontal accuracy (DRMS) from GST error ellipse axes
        val hAccM = drmsAccuracy(lastGst?.stdDevMajor, lastGst?.stdDevMinor)
        val vAccM = lastGst?.stdDevAlt
        val stdDevEastM = lastGst?.stdDevLon
        val stdDevNorthM = lastGst?.stdDevLat
        val stdDevUpM = lastGst?.stdDevAlt

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
                stdDevEastM         = stdDevEastM,
                stdDevNorthM        = stdDevNorthM,
                stdDevUpM           = stdDevUpM,
                rtkStatus           = rtk,
                satsUsed            = satsUsed,
                satsVisible         = satsVisible,
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
        lastEmittedGgaTimeUtc = null
        gsaUsedSatIds.clear(); lastGsaUpdateMs = 0L
        pendingGsvByTalker.clear(); gsvVisibleByTalker.clear()
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

