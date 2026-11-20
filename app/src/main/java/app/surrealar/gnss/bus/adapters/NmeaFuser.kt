package app.surrealar.gnss.bus.adapters

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.nmea.parse.NmeaDateTimeUtils
import app.surrealar.gnss.nmea.parse.NmeaRegistry
import app.surrealar.gnss.nmea.sentence.*
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
 * Extracted from the duplicated logic that previously lived in both `InternalNmeaSource` and
 * `TcpNmeaSource`. Adding support for a new external provider requires only a new
 * `SourceAdapter` implementation that creates its own [NmeaFuser] with the appropriate
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
    private var lastAccuracy: ACCURACY? = null   // MediaTek $..ACCURACY (fallback when no GST)
    private var lastAccuracyAtMs: Long = 0L

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

    /**
     * Latest completed GSV sequence per talker, keyed by signal ID ([NO_SIGNAL] when the receiver
     * sends no signal ID). Multi-frequency receivers send one sequence per signal (L1, L5, …) for
     * the same constellation; these are merged so the sky view shows each satellite once with a
     * stable (lowest-signal, i.e. L1) SNR instead of flip-flopping between bands.
     */
    private val gsvSignalsByTalker: MutableMap<String, MutableMap<Int, CompletedGsv>> = mutableMapOf()

    private data class CompletedGsv(val atMs: Long, val satellites: List<GSVSatellite>, val totalSatellites: Int?)

    /**
     * True once a GGA has been processed since the last GSA. The first GSA after a GGA starts a new
     * epoch, so the used-satellite set is rebuilt each epoch. (A pure wall-clock timeout never fired
     * at ≥0.5 Hz output, so satellites that left the solution stayed "used" forever and satsUsed
     * only ever grew.)
     */
    private var ggaSinceLastGsa = false

    /** Wall-clock time the last GSA arrived, for DOP staleness. */
    private var lastGsaAtMs: Long = 0L

    /** Guards all state: the internal source feeds from the main looper, stop()/reset() and stats readers run elsewhere. */
    private val lock = Any()

    /** Feed one raw NMEA line into the fuser. Silently ignores unparseable lines. */
    fun accept(line: String) {
        val sentence = try { registry.parse(line) } catch (_: Exception) { return } ?: return
        synchronized(lock) { handle(sentence) }
    }

    private fun handle(sentence: NmeaSentence) {
        when (sentence) {
            is GGA -> { ggaCount++; lastGga = sentence; ggaSinceLastGsa = true; maybeEmitFix() }
            is RMC -> { rmcCount++; lastRmc = sentence }  // Enrich speed/course; does not trigger a fix
            is GSA -> handleGsa(sentence)
            is GSV -> handleGsv(sentence)
            is ZDA -> { zdaCount++; handleZda(sentence) }
            is GST -> { gstCount++; lastGst = sentence }  // accuracy ellipse; used in next fix emission
            // MediaTek proprietary accuracy: no timestamp, so freshness is judged by arrival time.
            is ACCURACY -> { lastAccuracy = sentence; lastAccuracyAtMs = System.currentTimeMillis() }
            is EBP -> ebpCount++          // Emlid base position — diagnostics only, never affects fixes
            is ETC -> { etcCount++; lastEtc = sentence   // Emlid orientation/IMU — diagnostics only
                        if (sentence.hasOrientationData) etcOrientationSeen = true }
            else   -> Unit // unknown sentence type — fuser is tolerant by design
        }
    }

    // ── RS4 custom-sentence diagnostics (do not affect fix generation) ──────────
    private var ebpCount = 0
    private var etcCount = 0
    private var etcOrientationSeen = false
    private var lastEtc: ETC? = null

    /** Snapshot of Emlid custom-sentence activity, for diagnostics. */
    data class NmeaCustomStats(
        val ebpSeen: Boolean,
        val etcSeen: Boolean,
        val ebpCount: Int,
        val etcCount: Int,
        val latestEtc: ETC?,
        /** True if any ETC carried orientation/IMU-style data (undocumented — never used for AR). */
        val imuOrientationSeen: Boolean,
    )

    /** Read-only view of EBP/ETC activity seen by this fuser (diagnostics). */
    val nmeaCustomStats: NmeaCustomStats
        get() = synchronized(lock) { NmeaCustomStats(ebpCount > 0, etcCount > 0, ebpCount, etcCount, lastEtc, etcOrientationSeen) }

    /** Where the last fix's horizontal accuracy came from. */
    enum class AccuracySource { RECEIVER_GST, RECEIVER_PROPRIETARY, DOP_ESTIMATE, UNKNOWN }

    // ── Sentence/fix rate diagnostics (cumulative counts; consumers compute rates from deltas) ──
    private var ggaCount = 0
    private var rmcCount = 0
    private var zdaCount = 0
    private var gstCount = 0
    private var emittedFixCount = 0
    // Per-constellation GSA/GSV sentence counts, keyed by talker (GP/GL/GA/GB/GN/…).
    private val gsaCountByTalker = sortedMapOf<String, Int>()
    private val gsvCountByTalker = sortedMapOf<String, Int>()

    // Last emitted-fix summary for diagnostics (null = the field was absent, i.e. "unknown" — never 0.0).
    private var lastRtkStatus: RtkStatus? = null
    private var lastSatsUsed: Int? = null
    private var lastSatsVisible: Int? = null
    private var lastAltMslM: Double? = null
    private var lastGeoidSepM: Double? = null
    private var lastHAccM: Double? = null
    private var lastAccuracySource: AccuracySource = AccuracySource.UNKNOWN

    /** Cumulative sentence/fix counts + last-fix summary + the clock used for the last fix's date. */
    data class FuserTimingStats(
        val ggaCount: Int,
        val rmcCount: Int,
        val zdaCount: Int,
        val gstCount: Int,
        val emittedFixCount: Int,
        /** "GGA time + ZDA date", "GGA time + RMC date", "GGA time + device date", or device fallback. */
        val lastTimestampSource: TimestampSource?,
        val lastRtkStatus: RtkStatus?,
        val lastSatsUsed: Int?,
        val lastSatsVisible: Int?,
        val lastAltMslM: Double?,
        val lastGeoidSepM: Double?,
        /** Last parsed horizontal accuracy (m); null = unknown (never 0.0). */
        val lastHAccM: Double?,
        /** Where [lastHAccM] came from: receiver GST, a downstream DOP estimate, or unknown. */
        val lastAccuracySource: AccuracySource,
        /** GSA sentence counts by constellation talker (GP/GL/GA/GB/GN). */
        val gsaCountByTalker: Map<String, Int>,
        /** GSV sentence counts by constellation talker (GP/GL/GA/GB/GN). */
        val gsvCountByTalker: Map<String, Int>,
    )

    /** Snapshot of sentence/fix counts, last-fix summary, and the date source of the last fix. */
    val timingStats: FuserTimingStats
        get() = synchronized(lock) {
            FuserTimingStats(ggaCount, rmcCount, zdaCount, gstCount, emittedFixCount, lastTimestampSource,
                lastRtkStatus, lastSatsUsed, lastSatsVisible, lastAltMslM, lastGeoidSepM, lastHAccM, lastAccuracySource,
                gsaCountByTalker.toMap(), gsvCountByTalker.toMap())
        }

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
        gsaCountByTalker[gsa.talker] = (gsaCountByTalker[gsa.talker] ?: 0) + 1

        val now = System.currentTimeMillis()
        // New epoch: first GSA after a GGA, or a long gap (receiver paused / GSA-only stream).
        if (ggaSinceLastGsa || now - lastGsaUpdateMs > GSA_EPOCH_TIMEOUT_MS) {
            gsaUsedSatIds.clear()
            ggaSinceLastGsa = false
        }
        lastGsaUpdateMs = now
        lastGsaAtMs = now

        // With a combined "GN" talker, NMEA 4.10 identifies the constellation via the system ID;
        // without it, GPS #5 and Galileo #5 would collapse into one entry and undercount satsUsed.
        val key = gsaConstellationKey(gsa)
        gsa.usedSvids.forEach { svid ->
            gsaUsedSatIds.add(SatId(key, svid))
        }
    }

    /** Constellation key for a GSA: its talker, or the system-ID constellation for a combined "GN" GSA. */
    private fun gsaConstellationKey(gsa: GSA): String {
        val talker = normalizeTalker(gsa.talker)
        if (talker != "GN") return talker
        return when (gsa.systemId) {
            1 -> "GP"; 2 -> "GL"; 3 -> "GA"; 4 -> "GB"; 5 -> "GQ"; 6 -> "GI"
            else -> "GN"
        }
    }

    /** Folds alternate talker spellings onto one key (BeiDou "BD" → "GB", QZSS "QZ" → "GQ"). */
    private fun normalizeTalker(talker: String): String = when (talker.uppercase()) {
        "BD" -> "GB"
        "QZ" -> "GQ"
        else -> talker.uppercase()
    }

    private fun handleGsv(gsv: GSV) {
        val talker = normalizeTalker(gsv.talker)
        gsvCountByTalker[talker] = (gsvCountByTalker[talker] ?: 0) + 1
        val total = gsv.totalMessages ?: 1
        val num   = gsv.messageNumber ?: total
        val signal = gsv.signalId ?: NO_SIGNAL
        // Each signal (L1, L5, …) is its own paginated sequence; accumulate them separately.
        val pendingKey = "$talker#$signal"

        val pending = pendingGsvByTalker.getOrPut(pendingKey) {
            PendingGsvEpoch(totalMessages = total, satellites = mutableListOf())
        }

        if (num == 1) {
            // First message of a new sequence — reset the accumulator for this talker
            pending.totalMessages = total
            pending.satellites.clear()
        }

        pending.satellites.addAll(gsv.satellites)

        if (num >= total) {
            // Last message in this signal's sequence. Store it, then merge every fresh signal for
            // this talker so one constellation = one list of satellites.
            val now = System.currentTimeMillis()
            val signals = gsvSignalsByTalker.getOrPut(talker) { sortedMapOf() }
            signals[signal] = CompletedGsv(now, pending.satellites.toList(), gsv.totalSatellites)
            signals.entries.removeIf { now - it.value.atMs > GSV_SIGNAL_STALE_MS }
            pendingGsvByTalker.remove(pendingKey)

            val merged = mergeSignals(signals.values)
            val entries = merged.mapNotNull { s ->
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
            // One signal (legacy receivers): trust the sentence's own total. Several signals: the
            // per-signal totals overlap, so count the distinct satellites across them instead.
            val visibleCount = signals.values.singleOrNull()?.let { it.totalSatellites ?: it.satellites.size }
                ?: merged.size
            gsvVisibleByTalker[talker] = visibleCount

            val label = if (entries.isEmpty()) "UNKNOWN" else talker
            onGsv(GsvMessage(label, entries))
        }
    }

    /**
     * Merges per-signal satellite lists (iterated in ascending signal ID, so L1 comes first): each SVID
     * appears once, taking the lowest signal's values and filling any blank elevation/azimuth/SNR
     * from the other signals.
     */
    private fun mergeSignals(signals: Collection<CompletedGsv>): List<GSVSatellite> {
        if (signals.size == 1) return signals.first().satellites.filter { it.svid != null }
        val bySvid = LinkedHashMap<Int, GSVSatellite>()
        for (sig in signals) for (s in sig.satellites) {
            val svid = s.svid ?: continue
            val prev = bySvid[svid]
            bySvid[svid] = if (prev == null) s else prev.copy(
                elevationDeg = prev.elevationDeg ?: s.elevationDeg,
                azimuthDeg   = prev.azimuthDeg ?: s.azimuthDeg,
                snrDb        = prev.snrDb ?: s.snrDb
            )
        }
        return bySvid.values.toList()
    }

    private fun maybeEmitFix() {
        val gga = lastGga ?: return
        val lat = gga.lat ?: return
        val lon = gga.lon ?: return
        // Quality 0 = "fix not valid". Some chipsets keep repeating the last coordinates with
        // quality 0 after losing lock; publishing those would show (and let users capture) a frozen
        // position as if it were live. A blank quality field is still accepted (tolerant parsing).
        if (gga.fixQuality == 0) {
            app.surrealar.gnss.debug.GnssDebugLog.every("fuser.q0.$provider", "fuser") {
                "$provider GGA quality 0 with coordinates — not published (time=${gga.timeRaw})"
            }
            return
        }

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
            1, 3 -> RtkStatus.SINGLE          // 3 = PPS fix: a valid standalone solution
            2    -> RtkStatus.DGPS
            4    -> RtkStatus.FIX
            5    -> RtkStatus.FLOAT
            6    -> RtkStatus.DEAD_RECKONING
            else -> RtkStatus.NONE
        }

        // Constellation-aware GSA count vs. GGA's own field: take the larger. GSA lists at most 12
        // SVIDs per sentence (so it undercounts big multi-GNSS solutions on some receivers), while
        // some receivers cap GGA's field at 12 or report GPS-only there.
        val satsUsed = maxOf(gsaUsedSatIds.size, gga.satsUsed ?: 0)

        // Sum visible satellites across all constellations seen this epoch.
        // Guard: if the sum is less than satsUsed the GSV data is from a prior epoch
        // (GSV sentences for some constellations had not yet arrived when GGA triggered
        // this fix). Treat the count as absent rather than storing an impossible value.
        val rawVisible = gsvVisibleByTalker.values.sum().takeIf { it > 0 }
        val satsVisible = rawVisible?.takeIf { it >= satsUsed }

        // Horizontal accuracy from GST: prefer the error-ellipse semi-axes; otherwise fall back to the
        // lat/lon standard deviations — Emlid Reach (RS2+/RS4) leaves the semi-major/minor fields
        // empty and populates lat/lon instead. Null when GST carried neither — never fabricated as 0.0.
        // Enrichment sentences only apply if they belong to (roughly) this epoch. Otherwise a receiver
        // that stops sending GST/RMC/GSA would leave the last accuracy/speed/DOP frozen on every fix.
        val gst = lastGst?.takeIf { sameEpoch(it.timeRaw, gga.timeRaw) }
        val rmc = lastRmc?.takeIf { sameEpoch(it.timeRaw, gga.timeRaw) }
        val gsa = lastGsa?.takeIf { System.currentTimeMillis() - lastGsaAtMs <= GSA_STALE_MS }

        // Receiver-reported accuracy: standard GST first; otherwise the receiver's proprietary
        // $..ACCURACY (MediaTek) if it arrived recently — it follows GGA in each burst, so it belongs
        // to this or the previous epoch. Downstream, a still-missing value is filled from Android's
        // Location (internal source) or estimated from DOP × UERE.
        val acc = lastAccuracy?.takeIf { System.currentTimeMillis() - lastAccuracyAtMs <= ENRICHMENT_WINDOW_MS }
        val gstH = horizontalAccuracyFromGst(gst)
        val hAccM = gstH ?: acc?.hAccM
        val vAccM = gst?.stdDevAlt ?: acc?.vAccM
        val stdDevEastM = gst?.stdDevLon
        val stdDevNorthM = gst?.stdDevLat
        val stdDevUpM = gst?.stdDevAlt

        debugTrace(gga, epochMs, tsSource, rtk, satsUsed, rawVisible, satsVisible, gst, rmc, gsa, hAccM, altEllipsoidal)

        emittedFixCount++
        lastRtkStatus = rtk
        lastSatsUsed = satsUsed
        lastSatsVisible = satsVisible
        lastAltMslM = gga.altMsl
        lastGeoidSepM = gga.geoidSeparation
        lastHAccM = hAccM
        // hAccM is only ever set from GST here; a null GST accuracy is filled downstream by
        // AccuracyEstimator (DOP × UERE) when HDOP is available, else it stays unknown.
        lastAccuracySource = when {
            gstH != null     -> AccuracySource.RECEIVER_GST
            hAccM != null    -> AccuracySource.RECEIVER_PROPRIETARY
            gga.hdop != null -> AccuracySource.DOP_ESTIMATE
            else             -> AccuracySource.UNKNOWN
        }
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
                vDop                = gsa?.vdop,
                pDop                = gsa?.pdop,
                hAccM               = hAccM,
                vAccM               = vAccM,
                stdDevEastM         = stdDevEastM,
                stdDevNorthM        = stdDevNorthM,
                stdDevUpM           = stdDevUpM,
                rtkStatus           = rtk,
                satsUsed            = satsUsed,
                satsVisible         = satsVisible,
                diffAgeS            = gga.diffAge,
                speedMps            = rmc?.speedKnots?.let { it * 0.514444 },
                courseDeg           = rmc?.courseDeg,
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
    fun reset() = synchronized(lock) {
        gsvSignalsByTalker.clear(); ggaSinceLastGsa = false; lastGsaAtMs = 0L
        lastAccuracy = null; lastAccuracyAtMs = 0L
        lastGga = null; lastRmc = null; lastGsa = null; lastZda = null; lastGst = null
        lastEmittedGgaTimeUtc = null; lastTimestampSource = null
        ggaCount = 0; rmcCount = 0; zdaCount = 0; gstCount = 0; emittedFixCount = 0
        gsaCountByTalker.clear(); gsvCountByTalker.clear()
        lastRtkStatus = null; lastSatsUsed = null; lastSatsVisible = null
        lastAltMslM = null; lastGeoidSepM = null; lastHAccM = null; lastAccuracySource = AccuracySource.UNKNOWN
        ebpCount = 0; etcCount = 0; etcOrientationSeen = false; lastEtc = null
        gsaUsedSatIds.clear(); lastGsaUpdateMs = 0L
        pendingGsvByTalker.clear(); gsvVisibleByTalker.clear()
    }

    /**
     * 1-sigma circular horizontal accuracy from GST, in metres, or null when GST provides neither the
     * error-ellipse semi-axes nor the lat/lon standard deviations (i.e. accuracy is genuinely unknown —
     * callers must treat null as "unknown", never 0.0).
     *
     * Preference order:
     *  1. Semi-major/semi-minor axes (fields 3–4): sqrt((σ_major² + σ_minor²) / 2), handling elongated ellipses.
     *  2. Lat/lon std-devs (fields 6–7): sqrt((σ_lat² + σ_lon²) / 2). Emlid Reach fills these and leaves 1 empty.
     */
    private fun horizontalAccuracyFromGst(gst: GST?): Double? {
        if (gst == null) return null
        val major = gst.stdDevMajor
        val minor = gst.stdDevMinor
        if (major != null && minor != null) return Math.sqrt((major * major + minor * minor) / 2.0)
        val lat = gst.stdDevLat
        val lon = gst.stdDevLon
        if (lat != null && lon != null) return Math.sqrt((lat * lat + lon * lon) / 2.0)
        return null
    }

    /**
     * Debug builds: a periodic summary of the fix this fuser produced (and from which inputs), plus
     * throttled notes whenever an input was dropped or overridden — so a wrong value on screen can be
     * traced back to the sentence that caused it. Logcat only (see GnssDebugLog).
     */
    private fun debugTrace(
        gga: GGA, epochMs: Long, tsSource: TimestampSource, rtk: RtkStatus,
        satsUsed: Int, rawVisible: Int?, satsVisible: Int?,
        gst: GST?, rmc: RMC?, gsa: GSA?, hAccM: Double?, altEllipsoidal: Double?,
    ) {
        val log = app.surrealar.gnss.debug.GnssDebugLog
        if (!log.enabled) return
        log.every("fuser.fix.$provider", "fuser") {
            "$provider fix t=${gga.timeRaw} epoch=${Instant.ofEpochMilli(epochMs)} ($tsSource) q=${gga.fixQuality}→$rtk " +
                "lat=${gga.lat} lon=${gga.lon} msl=${gga.altMsl} N=${gga.geoidSeparation} ellH=$altEllipsoidal " +
                "sats used=$satsUsed (gsaSet=${gsaUsedSatIds.size} gga=${gga.satsUsed}) visible=$satsVisible " +
                "(gsv by talker=$gsvVisibleByTalker) hdop=${gga.hdop} pdop=${gsa?.pdop} vdop=${gsa?.vdop} " +
                "hAcc=${hAccM ?: "none"} vAcc=${gst?.stdDevAlt ?: lastAccuracy?.vAccM} " +
                "accSource=${when {
                    gst != null && horizontalAccuracyFromGst(gst) != null -> "GST"
                    hAccM != null -> "\$..ACCURACY (age ${System.currentTimeMillis() - lastAccuracyAtMs} ms)"
                    else -> "none (GST ${if (lastGst == null) "absent" else "stale"}, ACCURACY ${if (lastAccuracy == null) "absent" else "stale"})"
                }} " +
                "speed=${rmc?.speedKnots} diffAge=${gga.diffAge} station=${gga.stationId}"
        }
        if (lastGst != null && gst == null) log.every("fuser.staleGst.$provider", "fuser") {
            "$provider GST t=${lastGst?.timeRaw} not attached to GGA t=${gga.timeRaw} (outside ${ENRICHMENT_WINDOW_MS} ms)"
        }
        if (lastRmc != null && rmc == null) log.every("fuser.staleRmc.$provider", "fuser") {
            "$provider RMC t=${lastRmc?.timeRaw} not attached to GGA t=${gga.timeRaw}"
        }
        if (lastGsa != null && gsa == null) log.every("fuser.staleGsa.$provider", "fuser") {
            "$provider GSA older than ${GSA_STALE_MS} ms — DOP not attached"
        }
        if (rawVisible != null && satsVisible == null) log.every("fuser.visDropped.$provider", "fuser") {
            "$provider visible=$rawVisible < used=$satsUsed — GSV from an older epoch, visible left unknown"
        }
        val ggaSats = gga.satsUsed
        if (ggaSats != null && gsaUsedSatIds.isNotEmpty() && kotlin.math.abs(ggaSats - gsaUsedSatIds.size) > 3) {
            log.every("fuser.satsMismatch.$provider", "fuser") {
                "$provider sats disagree: GGA=$ggaSats GSA set=${gsaUsedSatIds.size} ($gsaUsedSatIds) — showing max"
            }
        }
        if (gga.altMsl != null && gga.geoidSeparation == null) log.every("fuser.noGeoid.$provider", "fuser") {
            "$provider GGA has MSL height but no geoid separation — ellipsoidal height unknown from NMEA"
        }
    }

    /**
     * True when two NMEA time-of-day stamps are within [ENRICHMENT_WINDOW_MS] of each other (midnight-
     * aware). If either time is missing/unparseable the sentence is accepted, as before.
     */
    private fun sameEpoch(timeRaw: String?, ggaTimeRaw: String?): Boolean {
        val a = NmeaDateTimeUtils.parseNmeaTime(timeRaw) ?: return true
        val b = NmeaDateTimeUtils.parseNmeaTime(ggaTimeRaw) ?: return true
        val diff = Math.abs(a.toNanoOfDay() - b.toNanoOfDay()) / 1_000_000L
        return minOf(diff, DAY_MS - diff) <= ENRICHMENT_WINDOW_MS
    }

    private companion object {
        /** GSV signal key used when the receiver sends no NMEA 4.10 signal ID. */
        const val NO_SIGNAL = -1
        /** A signal's GSV list is dropped once no new sequence for it has arrived for this long. */
        const val GSV_SIGNAL_STALE_MS = 10_000L
        /** GSA DOP values older than this are not attached to fixes. */
        const val GSA_STALE_MS = 5_000L
        /** GST/RMC must be within this of the GGA time to enrich it (covers 1 Hz RMC with 5 Hz GGA). */
        const val ENRICHMENT_WINDOW_MS = 2_500L
    }

    private fun sumIfFinite(a: Double?, b: Double?): Double? =
        if (a != null && a.isFinite() && b != null && b.isFinite()) a + b else null
}

