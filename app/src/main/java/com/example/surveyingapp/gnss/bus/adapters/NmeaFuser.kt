package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.nmea.parse.NmeaDateTimeUtils
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.nmea.sentence.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

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

    // Epoch deduplication: the epoch (date + GGA time-of-day) of the most recently emitted fix.
    private var lastEmittedGgaTimeUtc: Long? = null

    /** Which clock supplied the date for the last emitted fix (diagnostics). */
    private var lastTimestampSource: TimestampSource? = null

    private val TWELVE_HOURS_MS = 12L * 60 * 60 * 1000
    private val DAY_MS = 24L * 60 * 60 * 1000

    /*
     * Multi-constellation GSA accumulation.
     * Modern receivers emit one GSA per constellation (GP, GL, GA, etc.) within
     * the same epoch. We collect all SVIDs from those sentences into one set before
     * reporting satsUsed, so the count reflects the full solution rather than just
     * the last constellation's contribution.
     */
    private val gsaUsedSatIds: MutableSet<SatId> = mutableSetOf()
    private var lastGsaUpdateMs: Long = 0L
    private val GSA_EPOCH_TIMEOUT_MS = 2000L

    /*
     * Per-talker GSV accumulation.
     * Each constellation can span multiple GSV sentences (up to 4 satellites per
     * sentence). We buffer them by talker ID until the last message in the sequence
     * arrives, then fire onGsv with the complete list.
     */
    private val pendingGsvByTalker: MutableMap<String, PendingGsvEpoch> = mutableMapOf()

    /**
     * Tracks visible satellite count per constellation, updated each time a GSV epoch
     * completes. Summed across all constellations to get the total satsVisible in the Fix.
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
            is GGA -> { ggaCount++; lastGga = sentence; maybeEmitFix() }
            is RMC -> { rmcCount++; lastRmc = sentence }  // Enrich speed/course; does not trigger a fix
            is GSA -> handleGsa(sentence)
            is GSV -> handleGsv(sentence)
            is ZDA -> { zdaCount++; handleZda(sentence) }
            is GST -> lastGst = sentence  // store accuracy ellipse; used in next fix emission
            is EBP -> ebpCount++          // Emlid base position — diagnostics only, never affects fixes
            is ETC -> { etcCount++; lastEtc = sentence }  // Emlid tilt — diagnostics only
            else   -> Unit // unknown sentence type — fuser is tolerant by design
        }
    }

    // ── RS4 custom-sentence diagnostics (do not affect fix generation) ──────────
    private var ebpCount = 0
    private var etcCount = 0
    private var lastEtc: ETC? = null

    /** Snapshot of Emlid custom-sentence activity, for diagnostics. */
    data class NmeaCustomStats(
        val ebpSeen: Boolean,
        val etcSeen: Boolean,
        val ebpCount: Int,
        val etcCount: Int,
        val latestEtc: ETC?,
    )

    /** Read-only view of EBP/ETC activity seen by this fuser (diagnostics). */
    val nmeaCustomStats: NmeaCustomStats
        get() = NmeaCustomStats(ebpCount > 0, etcCount > 0, ebpCount, etcCount, lastEtc)

    // ── Sentence/fix rate diagnostics (cumulative counts; consumers compute rates from deltas) ──
    private var ggaCount = 0
    private var rmcCount = 0
    private var zdaCount = 0
    private var emittedFixCount = 0

    /** Cumulative sentence/fix counts + the clock used for the last fix's date (diagnostics). */
    data class FuserTimingStats(
        val ggaCount: Int,
        val rmcCount: Int,
        val zdaCount: Int,
        val emittedFixCount: Int,
        /** "GGA time + ZDA date", "GGA time + RMC date", "GGA time + device date", or device fallback. */
        val lastTimestampSource: TimestampSource?,
    )

    /** Snapshot of GGA/RMC/ZDA + emitted-fix counts and the date source of the last fix. */
    val timingStats: FuserTimingStats
        get() = FuserTimingStats(ggaCount, rmcCount, zdaCount, emittedFixCount, lastTimestampSource)

    private fun handleZda(zda: ZDA) {
        val epochMs = zda.epochMillis
        if (epochMs != null) {
            val ageSec = (System.currentTimeMillis() - epochMs) / 1000.0
            /*
             * Allow a small future window for receiver clock skew. Reject anything
             * significantly in the past — it means the ZDA is stale or the receiver
             * clock has reset.
             */
            if (ageSec in -5.0..10.0) {
                lastZda = zda
            } else {
                android.util.Log.w("NmeaFuser", "Stale ZDA ignored: age=${ageSec}s")
            }
        }
    }

    private fun handleGsa(gsa: GSA) {
        lastGsa = gsa

        val now = System.currentTimeMillis()
        if (now - lastGsaUpdateMs > GSA_EPOCH_TIMEOUT_MS) {
            gsaUsedSatIds.clear()
        }
        lastGsaUpdateMs = now

        // Add SVIDs from this constellation's GSA to the combined set
        gsa.usedSvids.forEach { svid ->
            gsaUsedSatIds.add(SatId(gsa.talker, svid))
        }
    }

    private fun handleGsv(gsv: GSV) {
        val talker = gsv.talker
        val total = gsv.totalMessages ?: 1
        val num   = gsv.messageNumber ?: total

        val pending = pendingGsvByTalker.getOrPut(talker) {
            PendingGsvEpoch(totalMessages = total, satellites = mutableListOf())
        }

        if (num == 1) {
            // First message of a new sequence — reset the accumulator for this talker
            pending.totalMessages = total
            pending.satellites.clear()
        }

        pending.satellites.addAll(gsv.satellites)

        if (num >= total) {
            // Last message in the sequence — build the final entries list
            val entries = pending.satellites.mapNotNull { s ->
                s.svid?.let { svid ->
                    val satId = SatId(talker, svid)
                    /*
                     * A satellite is considered "used" if it appears in the GSA set for
                     * its talker, or under the "GN" (combined) talker that some receivers
                     * emit instead of per-constellation GSA sentences.
                     */
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

            val visibleCount = gsv.totalSatellites ?: pending.satellites.size
            gsvVisibleByTalker[talker] = visibleCount

            val label = if (entries.isEmpty()) "UNKNOWN" else talker
            onGsv(GsvMessage(label, entries))

            pendingGsvByTalker.remove(talker)
        }
    }

    private fun maybeEmitFix() {
        val gga = lastGga ?: return
        val lat = gga.lat ?: return
        val lon = gga.lon ?: return

        // Epoch time: the GGA UTC time-of-day is the authoritative (up to 5 Hz) clock; the date is
        // supplied by the most recent ZDA/RMC (or the current UTC date as a fallback). This lets each
        // GGA epoch emit a fresh fix even though RMC/ZDA only arrive at ~1 Hz. If the GGA has no usable
        // time-of-day, the previous ZDA → RMC → device-clock behavior is preserved.
        val (epochMs, tsSource) = computeEpochMs(gga)
        lastTimestampSource = tsSource

        // Epoch deduplication: skip only an exact repeat of the same epoch (e.g. a duplicated GGA
        // line). Distinct GGA epochs — including 200 ms-apart 5 Hz epochs — each emit a fresh fix.
        if (epochMs == lastEmittedGgaTimeUtc) return
        lastEmittedGgaTimeUtc = epochMs

        val altEllipsoidal = sumIfFinite(gga.altMsl, gga.geoidSeparation)

        val rtk = when (gga.fixQuality) {
            1    -> RtkStatus.SINGLE
            2    -> RtkStatus.DGPS
            4    -> RtkStatus.FIX
            5    -> RtkStatus.FLOAT
            else -> RtkStatus.NONE
        }

        // Prefer the accumulated constellation-aware count; fall back to GGA's field
        val satsUsed = if (gsaUsedSatIds.isNotEmpty()) gsaUsedSatIds.size else (gga.satsUsed ?: 0)

        // Sum visible satellites across all constellations seen this epoch.
        // Guard: if the sum is less than satsUsed the GSV data is from a prior epoch
        // (GSV sentences for some constellations had not yet arrived when GGA triggered
        // this fix). Treat the count as absent rather than storing an impossible value.
        val rawVisible = gsvVisibleByTalker.values.sum().takeIf { it > 0 }
        val satsVisible = rawVisible?.takeIf { it >= satsUsed }

        // DRMS horizontal accuracy from GST error ellipse axes (see drmsAccuracy below)
        val hAccM = drmsAccuracy(lastGst?.stdDevMajor, lastGst?.stdDevMinor)
        val vAccM = lastGst?.stdDevAlt
        val stdDevEastM = lastGst?.stdDevLon
        val stdDevNorthM = lastGst?.stdDevLat
        val stdDevUpM = lastGst?.stdDevAlt

        emittedFixCount++
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

    /**
     * Computes the fix epoch and its date source. The time-of-day comes from the GGA (so 5 Hz GGA
     * yields 5 Hz fixes); the date from the latest ZDA → RMC → current UTC. Falls back to the prior
     * ZDA → RMC → device-clock epoch when the GGA has no parseable time-of-day.
     */
    private fun computeEpochMs(gga: GGA): Pair<Long, TimestampSource> {
        val ggaTime = NmeaDateTimeUtils.parseNmeaTime(gga.timeRaw)
        if (ggaTime != null) {
            val zdaDate = lastZda?.let { z ->
                if (z.year != null && z.month != null && z.day != null)
                    runCatching { LocalDate.of(z.year, z.month, z.day) }.getOrNull() else null
            }
            val (date, dateSource) = when {
                zdaDate != null       -> zdaDate to TimestampSource.NMEA_ZDA
                lastRmc?.date != null -> lastRmc!!.date!! to TimestampSource.GNSS_PROVIDER
                else                  -> LocalDate.now(ZoneOffset.UTC) to TimestampSource.DEVICE
            }
            var epoch = ZonedDateTime.of(date, ggaTime, ZoneOffset.UTC).toInstant().toEpochMilli()
            // Midnight rollover: if the 1 Hz date source's own time was just before midnight while
            // GGA has already wrapped past it, the naive combine lands ~24 h in the past. Detect via
            // the reference ZDA/RMC epoch and advance one day.
            val refEpoch = lastZda?.epochMillis ?: lastRmc?.epochMillis
            if (refEpoch != null && epoch < refEpoch - TWELVE_HOURS_MS) {
                epoch += DAY_MS
            }
            return epoch to dateSource
        }
        // No usable GGA time-of-day → preserve the previous ZDA → RMC → device behavior.
        return when {
            lastZda?.epochMillis != null -> lastZda!!.epochMillis!! to TimestampSource.NMEA_ZDA
            lastRmc?.epochMillis != null -> lastRmc!!.epochMillis!! to TimestampSource.GNSS_PROVIDER
            else                         -> System.currentTimeMillis() to TimestampSource.DEVICE
        }
    }

    /** Clears all accumulated sentence state. Call when the underlying GNSS source is stopped. */
    fun reset() {
        lastGga = null; lastRmc = null; lastGsa = null; lastZda = null; lastGst = null
        lastEmittedGgaTimeUtc = null; lastTimestampSource = null
        ggaCount = 0; rmcCount = 0; zdaCount = 0; emittedFixCount = 0
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

