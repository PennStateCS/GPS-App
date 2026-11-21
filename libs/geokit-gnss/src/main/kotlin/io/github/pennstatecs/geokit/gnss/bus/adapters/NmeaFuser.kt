package io.github.pennstatecs.geokit.gnss.bus.adapters

import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaDateTimeUtils
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.sentence.*
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
 * **Threading.** Feed lines from one reader thread, in stream order: epoch assembly depends on the
 * order of the sentences, which no lock can restore. All state is guarded by an internal lock, so
 * [flushIfStale] (a ~100 ms timer), [flush], [reset] and the stats getters may run on other threads.
 * [onFix] and [onGsv] are called synchronously **while that lock is held**: keep them short, never
 * block in them, and don't call this fuser from another thread inside them.
 *
 * **GST hold.** Emlid sends GST (accuracy) and GSA (DOP, used satellites) after the GGA of the same
 * epoch. When the stream carries GST and one is expected for a GGA's time, the fix is held and released
 * by the first of: a GST with the same time, the first sentence of the next epoch, or 300 ms
 * (GST_HOLD_MS), checked on every sentence and by [flushIfStale]. Streams without GST are never held.
 * Call [flush] at the end of finite input (replay, tests) so the last epoch is emitted.
 *
 * **Accuracy.** [Fix.hAccM] is a 68 % horizontal radius (GST σ → HorizontalAccuracy.r68; MediaTek
 * $..ACCURACY as reported); [Fix.vAccM] is 1σ. Values the receiver did not send stay null, never 0.
 *
 * One fuser serves every NMEA source (device GNSS, TCP, Bluetooth, replay). Adding a new source means
 * creating its own [NmeaFuser] with the appropriate [Provider] value — no changes to the fuser itself.
 *
 * @param provider   Which GNSS provider produced this data (stamped onto every [Fix]).
 * @param registry   Shared [NmeaRegistry] that performs checksum-verified sentence parsing.
 * @param onFix      Invoked synchronously, under the fuser's lock, when a position epoch is assembled.
 * @param onGsv      Invoked synchronously, under the fuser's lock, when a full GSV epoch completes.
 * @param clock      Wall clock in milliseconds (GST hold, staleness); injectable for tests.
 */
public class NmeaFuser(
    private val provider: Provider,
    private val registry: NmeaRegistry,
    private val onFix: (Fix) -> Unit,
    private val onGsv: (GsvMessage) -> Unit,
    /** Wall clock (ms). Injectable so the GST hold cap can be tested with a fake clock. */
    private val clock: () -> Long = System::currentTimeMillis,
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

    /**
     * Feed one raw NMEA line (with or without the trailing CR/LF). Unparseable, checksum-failed and
     * unknown sentences are ignored. May call [onFix] / [onGsv] before returning.
     */
    public fun accept(line: String) {
        val sentence = try { registry.parse(line) } catch (_: Exception) { return } ?: return
        synchronized(lock) { handle(sentence) }
    }

    /*
     * Epoch alignment. Emlid sends RMC, GGA, GSA, GSV, VTG, GST, ZDA, so GST (accuracy) and GSA
     * (DOP / used satellites) for epoch T arrive AFTER GGA(T). Emitting on GGA would attach T−1's
     * values. When the stream carries GST and one is expected for this GGA's time, the fix is held
     * and released by the first of: a GST with the same time, the first sentence of the next epoch,
     * or GST_HOLD_MS. Enrichment is evaluated at release. Streams without GST are never held.
     */
    private var pendingGga: GGA? = null
    private var pendingAtMs = 0L
    private var lastGstAtMs = 0L
    private var lastGstTodMs: Long? = null
    private var gstPeriodMs = 1_000L
    /** RTK status of the fix the latest GST was attached to (exact-time match). */
    private var gstEpochRtk: RtkStatus? = null

    private fun handle(sentence: NmeaSentence) {
        val now = clock()
        pendingGga?.let { p ->
            if (now - pendingAtMs > GST_HOLD_MS || startsNextEpoch(sentence, p)) releasePending()
        }
        when (sentence) {
            is GGA -> {
                ggaCount++; lastGga = sentence; ggaSinceLastGsa = true
                val gstInHand = lastGst?.let { timesMatch(it.timeRaw, sentence.timeRaw) } == true
                if (!gstInHand && expectGstFor(sentence, now)) {
                    pendingGga = sentence; pendingAtMs = now
                } else {
                    maybeEmitFix(sentence)
                }
            }
            is RMC -> { rmcCount++; lastRmc = sentence }  // Enrich speed/course; does not trigger a fix
            is GSA -> handleGsa(sentence)
            is GSV -> handleGsv(sentence)
            is ZDA -> { zdaCount++; handleZda(sentence) }
            is GST -> {
                gstCount++; lastGst = sentence; lastGstAtMs = now
                NmeaDateTimeUtils.parseNmeaTime(sentence.timeRaw)?.let { t ->
                    val tod = t.toNanoOfDay() / 1_000_000L
                    lastGstTodMs?.let { prev ->
                        val d = Math.floorMod(tod - prev, DAY_MS)
                        if (d in 100L..10_000L) gstPeriodMs = d
                    }
                    lastGstTodMs = tod
                }
                pendingGga?.let { if (timesMatch(sentence.timeRaw, it.timeRaw)) releasePending() }
            }
            // MediaTek proprietary accuracy: no timestamp, so freshness is judged by arrival time.
            is ACCURACY -> { lastAccuracy = sentence; lastAccuracyAtMs = clock() }
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
    public data class NmeaCustomStats(
        val ebpSeen: Boolean,
        val etcSeen: Boolean,
        val ebpCount: Int,
        val etcCount: Int,
        val latestEtc: ETC?,
        /** True if any ETC carried orientation/IMU-style data (undocumented — never used for AR). */
        val imuOrientationSeen: Boolean,
    )

    /** Read-only view of EBP/ETC activity seen by this fuser (diagnostics). */
    public val nmeaCustomStats: NmeaCustomStats
        get() = synchronized(lock) { NmeaCustomStats(ebpCount > 0, etcCount > 0, ebpCount, etcCount, lastEtc, etcOrientationSeen) }

    /** Where the last fix's horizontal accuracy came from. */
    public enum class AccuracySource { RECEIVER_GST, RECEIVER_PROPRIETARY, DOP_ESTIMATE, UNKNOWN }

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
    public data class FuserTimingStats(
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
    public val timingStats: FuserTimingStats
        get() = synchronized(lock) {
            FuserTimingStats(ggaCount, rmcCount, zdaCount, gstCount, emittedFixCount, lastTimestampSource,
                lastRtkStatus, lastSatsUsed, lastSatsVisible, lastAltMslM, lastGeoidSepM, lastHAccM, lastAccuracySource,
                gsaCountByTalker.toMap(), gsvCountByTalker.toMap())
        }

    /**
     * Accepts a ZDA when its time-of-day agrees with the latest GGA/RMC (same epoch within
     * [ZDA_MATCH_MS], midnight-aware) — GNSS time vs GNSS time. Comparing with the DEVICE clock (the
     * old −5..10 s window) rejected every ZDA after a tablet clock step, so the last accepted ZDA's
     * date was reused and fixes after UTC midnight were dated a day behind. With no GGA/RMC yet to
     * compare against, the ZDA is accepted. See also [freshZda] (expiry).
     */
    private fun handleZda(zda: ZDA) {
        if (zda.epochMillis == null) return
        val ref = lastGga?.timeRaw ?: lastRmc?.timeRaw
        val zdaTime = NmeaDateTimeUtils.parseNmeaTime(zda.timeRaw)
        val refTime = NmeaDateTimeUtils.parseNmeaTime(ref)
        val agrees = zdaTime == null || refTime == null || run {
            val diff = Math.abs(zdaTime.toNanoOfDay() - refTime.toNanoOfDay()) / 1_000_000L
            minOf(diff, DAY_MS - diff) <= ZDA_MATCH_MS
        }
        if (agrees) {
            lastZda = zda
            lastZdaAtMs = clock()
        } else {
            GnssLog.w("NmeaFuser", "ZDA ignored: time ${zda.timeRaw} doesn't match GGA/RMC $ref")
        }
    }

    /** Wall-clock arrival of the last accepted ZDA (only for expiry; never compared with GNSS time). */
    private var lastZdaAtMs = 0L

    /** The stored ZDA if refreshed within [ZDA_EXPIRY_MS]; otherwise the date falls back to RMC, then device. */
    private fun freshZda(): ZDA? = lastZda?.takeIf { clock() - lastZdaAtMs <= ZDA_EXPIRY_MS }

    private fun handleGsa(gsa: GSA) {
        lastGsa = gsa
        gsaCountByTalker[gsa.talker] = (gsaCountByTalker[gsa.talker] ?: 0) + 1

        val now = clock()
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
            val now = clock()
            val signals = gsvSignalsByTalker.getOrPut(talker) { sortedMapOf() }
            signals[signal] = CompletedGsv(now, pending.satellites.toList(), gsv.totalSatellites)
            signals.entries.removeIf { now - it.value.atMs > GSV_SIGNAL_STALE_MS }
            pendingGsvByTalker.remove(pendingKey)

            val merged = mergeSignals(
                signals.entries.sortedBy { signalRank(talker, it.key) }.map { it.value }
            )
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
                        snrDbHz      = s.snrDb,
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
     * Sort key for a constellation's NMEA 4.10 signal IDs: the L1-band civil signal first. The IDs
     * differ per system — Galileo numbers E5a as 1 and E1-BC as 7, so "lowest ID" picked E5a.
     *  GP/GL/GQ: 1 = L1 C/A (G1 C/A).  GA: 7 = E1-BC, 6 = E1-A.  GB: 1 = B1I, 3 = B1C.
     * Unlisted IDs follow in ascending order; [NO_SIGNAL] (legacy, single sequence) sorts first.
     */
    private fun signalRank(talker: String, signal: Int): Int {
        if (signal == NO_SIGNAL) return -1
        val preferred = when (talker) {
            "GA" -> listOf(7, 6)
            "GB" -> listOf(1, 3)
            else -> listOf(1)
        }
        val i = preferred.indexOf(signal)
        return if (i >= 0) i else 100 + signal
    }

    /**
     * Merges per-signal satellite lists (iterated in preference order, see [signalRank]): each SVID
     * appears once, taking the preferred signal's values and filling any blank elevation/azimuth/SNR
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

    private fun releasePending() {
        val p = pendingGga ?: return
        pendingGga = null
        maybeEmitFix(p)
    }

    /** True when this stream sends GST and its cadence puts one at this GGA's time. */
    private fun expectGstFor(gga: GGA, now: Long): Boolean {
        if (lastGstAtMs == 0L || now - lastGstAtMs > GST_ACTIVE_MS) return false
        val prev = lastGstTodMs ?: return true
        val t = NmeaDateTimeUtils.parseNmeaTime(gga.timeRaw) ?: return false
        val phase = Math.floorMod(t.toNanoOfDay() / 1_000_000L - prev, gstPeriodMs)
        return phase <= EPOCH_MATCH_MS || phase >= gstPeriodMs - EPOCH_MATCH_MS
    }

    /** True when [s] carries a time-of-day different from the pending GGA's (a later epoch began). */
    private fun startsNextEpoch(s: NmeaSentence, pending: GGA): Boolean {
        val t = when (s) {
            is GGA -> s.timeRaw
            is RMC -> s.timeRaw
            is ZDA -> s.timeRaw
            else   -> return false   // GST handled by exact match; GSA/GSV/VTG carry no time
        }
        val a = NmeaDateTimeUtils.parseNmeaTime(t) ?: return s is GGA
        val b = NmeaDateTimeUtils.parseNmeaTime(pending.timeRaw) ?: return true
        return a != b
    }

    /** Same epoch: time-of-day within [EPOCH_MATCH_MS]. A missing time matches (tolerant, as before). */
    private fun timesMatch(timeRaw: String?, ggaTimeRaw: String?): Boolean {
        val a = NmeaDateTimeUtils.parseNmeaTime(timeRaw) ?: return true
        val b = NmeaDateTimeUtils.parseNmeaTime(ggaTimeRaw) ?: return true
        val diff = Math.abs(a.toNanoOfDay() - b.toNanoOfDay()) / 1_000_000L
        return minOf(diff, DAY_MS - diff) <= EPOCH_MATCH_MS
    }

    /** Milliseconds from [earlierRaw] to [laterRaw] (midnight-aware), or null if either is unparseable. */
    private fun msAfter(earlierRaw: String?, laterRaw: String?): Long? {
        val a = NmeaDateTimeUtils.parseNmeaTime(earlierRaw) ?: return null
        val b = NmeaDateTimeUtils.parseNmeaTime(laterRaw) ?: return null
        return Math.floorMod((b.toNanoOfDay() - a.toNanoOfDay()) / 1_000_000L, DAY_MS)
    }

    /**
     * Releases a fix held for its GST, if any. The live stream releases it with the next epoch's
     * first sentence; call this at end of input (replay, tests) so the last epoch is not lost.
     */
    public fun flush(): Unit = synchronized(lock) { releasePending() }

    /**
     * Enforces the GST hold cap in time, not only when the next sentence arrives: releases a held fix
     * once it has waited longer than [GST_HOLD_MS]. Sources call this every ~100 ms. Runs under the
     * same lock as [accept], so [onFix] is never called concurrently and fixes stay in order.
     */
    public fun flushIfStale(nowMs: Long = clock()): Unit = synchronized(lock) {
        if (pendingGga != null && nowMs - pendingAtMs > GST_HOLD_MS) releasePending()
    }

    private fun maybeEmitFix(gga: GGA) {
        val lat = gga.lat ?: return
        val lon = gga.lon ?: return
        // Quality 0 = "fix not valid". Some chipsets keep repeating the last coordinates with
        // quality 0 after losing lock; publishing those would show (and let users capture) a frozen
        // position as if it were live. A blank quality field is still accepted (tolerant parsing).
        if (gga.fixQuality == 0) {
            GnssLog.every("fuser.q0.$provider", "fuser") {
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
        // Null when neither source reported a count — "unknown" must not be recorded as 0 satellites.
        val gsaCount = gsaUsedSatIds.size.takeIf { it > 0 }
        val satsUsed: Int? = when {
            gsaCount == null      -> gga.satsUsed
            gga.satsUsed == null  -> gsaCount
            else                  -> maxOf(gsaCount, gga.satsUsed)
        }

        // Sum visible satellites across all constellations seen this epoch.
        // Guard: if the sum is less than satsUsed the GSV data is from a prior epoch
        // (GSV sentences for some constellations had not yet arrived when GGA triggered
        // this fix). Treat the count as absent rather than storing an impossible value.
        val rawVisible = gsvVisibleByTalker.values.sum().takeIf { it > 0 }
        val satsVisible = rawVisible?.takeIf { it >= (satsUsed ?: 0) }

        // Horizontal accuracy from GST: prefer the error-ellipse semi-axes; otherwise fall back to the
        // lat/lon standard deviations — Emlid Reach (RS2+/RS4) leaves the semi-major/minor fields
        // empty and populates lat/lon instead. Null when GST carried neither — never fabricated as 0.0.
        // Enrichment sentences only apply if they belong to (roughly) this epoch. Otherwise a receiver
        // that stops sending GST/RMC/GSA would leave the last accuracy/speed/DOP frozen on every fix.
        // GST from exactly this epoch (waited for above). Between GSTs (5 Hz GGA, 1 Hz GST) the latest
        // GST is carried for at most one GST period, and only while the RTK status is unchanged since
        // the epoch it belonged to — so a FIX → FLOAT change never keeps the FIX accuracy.
        val exactGst = lastGst?.takeIf { timesMatch(it.timeRaw, gga.timeRaw) }
        val gst = exactGst ?: lastGst?.takeIf { g ->
            gstEpochRtk == rtk && msAfter(g.timeRaw, gga.timeRaw)?.let { it in 1..(gstPeriodMs + EPOCH_MATCH_MS) } == true
        }
        if (exactGst != null) gstEpochRtk = rtk
        val rmc = lastRmc?.takeIf { sameEpoch(it.timeRaw, gga.timeRaw) }
        val gsa = lastGsa?.takeIf { clock() - lastGsaAtMs <= GSA_STALE_MS }

        // Receiver-reported accuracy: standard GST first; otherwise the receiver's proprietary
        // $..ACCURACY (MediaTek) if it arrived recently — it follows GGA in each burst, so it belongs
        // to this or the previous epoch. Downstream, a still-missing value is filled from Android's
        // Location (internal source) or estimated from DOP × UERE.
        val acc = lastAccuracy?.takeIf { clock() - lastAccuracyAtMs <= ENRICHMENT_WINDOW_MS }
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
        val zda = freshZda()
        if (ggaTime != null) {
            val zdaDate = zda?.let { z ->
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
            val refEpoch = zda?.epochMillis ?: lastRmc?.epochMillis
            if (refEpoch != null && epoch < refEpoch - TWELVE_HOURS_MS) {
                epoch += DAY_MS
            }
            return epoch to dateSource
        }
        // No usable GGA time-of-day → preserve the previous ZDA → RMC → device behavior.
        return when {
            zda?.epochMillis != null     -> zda.epochMillis!! to TimestampSource.NMEA_ZDA
            lastRmc?.epochMillis != null -> lastRmc!!.epochMillis!! to TimestampSource.GNSS_PROVIDER
            else                         -> clock() to TimestampSource.DEVICE
        }
    }

    /** Clears all accumulated sentence state. Call when the underlying GNSS source is stopped. */
    public fun reset(): Unit = synchronized(lock) {
        gsvSignalsByTalker.clear(); ggaSinceLastGsa = false; lastGsaAtMs = 0L
        lastAccuracy = null; lastAccuracyAtMs = 0L
        lastGga = null; lastRmc = null; lastGsa = null; lastZda = null; lastGst = null; lastZdaAtMs = 0L
        pendingGga = null; pendingAtMs = 0L; lastGstAtMs = 0L; lastGstTodMs = null; gstPeriodMs = 1_000L; gstEpochRtk = null
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
     * 68 % horizontal radius from GST, in metres (the app-wide meaning of hAccM — same as Android's
     * Location.accuracy; see [io.github.pennstatecs.geokit.accuracy.HorizontalAccuracy]), or null when GST provides neither
     * the error-ellipse semi-axes nor the lat/lon standard deviations (accuracy genuinely unknown —
     * callers must treat null as "unknown", never 0.0).
     *
     * Preference order:
     *  1. Semi-major/semi-minor axes (fields 3–4).
     *  2. Lat/lon std-devs (fields 6–7). Emlid Reach fills these and leaves 1 empty.
     * Either pair → 1.515 · sqrt((σ1² + σ2²) / 2). Vertical (σ_alt) stays 1σ.
     */
    private fun horizontalAccuracyFromGst(gst: GST?): Double? {
        if (gst == null) return null
        val major = gst.stdDevMajor
        val minor = gst.stdDevMinor
        if (major != null && minor != null) return io.github.pennstatecs.geokit.accuracy.HorizontalAccuracy.r68(major, minor)
        val lat = gst.stdDevLat
        val lon = gst.stdDevLon
        if (lat != null && lon != null) return io.github.pennstatecs.geokit.accuracy.HorizontalAccuracy.r68(lat, lon)
        return null
    }

    /**
     * Debug builds: a periodic summary of the fix this fuser produced (and from which inputs), plus
     * throttled notes whenever an input was dropped or overridden — so a wrong value on screen can be
     * traced back to the sentence that caused it. Debug trace only (GnssLog.every; logcat in the app).
     */
    private fun debugTrace(
        gga: GGA, epochMs: Long, tsSource: TimestampSource, rtk: RtkStatus,
        satsUsed: Int?, rawVisible: Int?, satsVisible: Int?,
        gst: GST?, rmc: RMC?, gsa: GSA?, hAccM: Double?, altEllipsoidal: Double?,
    ) {
        val log = GnssLog
        if (!log.enabled) return
        log.every("fuser.fix.$provider", "fuser") {
            "$provider fix t=${gga.timeRaw} epoch=${Instant.ofEpochMilli(epochMs)} ($tsSource) q=${gga.fixQuality}→$rtk " +
                "lat=${gga.lat} lon=${gga.lon} msl=${gga.altMsl} N=${gga.geoidSeparation} ellH=$altEllipsoidal " +
                "sats used=$satsUsed (gsaSet=${gsaUsedSatIds.size} gga=${gga.satsUsed}) visible=$satsVisible " +
                "(gsv by talker=$gsvVisibleByTalker) hdop=${gga.hdop} pdop=${gsa?.pdop} vdop=${gsa?.vdop} " +
                "hAcc=${hAccM ?: "none"} vAcc=${gst?.stdDevAlt ?: lastAccuracy?.vAccM} " +
                "accSource=${when {
                    gst != null && horizontalAccuracyFromGst(gst) != null -> "GST"
                    hAccM != null -> "\$..ACCURACY (age ${clock() - lastAccuracyAtMs} ms)"
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
        /** Longest a GGA is held waiting for its same-time GST before emitting without it. */
        const val GST_HOLD_MS = 300L
        /** A ZDA is the same epoch as the latest GGA/RMC when their times differ by at most this. */
        const val ZDA_MATCH_MS = 2_000L
        /** A stored ZDA not refreshed for this long no longer supplies the date. */
        const val ZDA_EXPIRY_MS = 10_000L
        /** The stream counts as GST-capable while a GST arrived within this window. */
        const val GST_ACTIVE_MS = 5_000L
        /** Two NMEA times within this are the same epoch (covers centisecond rounding). */
        const val EPOCH_MATCH_MS = 50L
    }

    private fun sumIfFinite(a: Double?, b: Double?): Double? =
        if (a != null && a.isFinite() && b != null && b.isFinite()) a + b else null
}

