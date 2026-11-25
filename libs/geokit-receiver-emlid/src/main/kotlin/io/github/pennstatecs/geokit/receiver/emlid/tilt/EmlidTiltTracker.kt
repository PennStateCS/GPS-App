package io.github.pennstatecs.geokit.receiver.emlid.tilt

import io.github.pennstatecs.geokit.gnss.nmea.sentence.GGA
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import io.github.pennstatecs.geokit.receiver.emlid.nmea.ETC
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcFields
import kotlin.math.abs
import kotlin.math.min

/** How [EmlidTiltTracker.associate] reached its answer. Only [MATCHED] carries a known state. */
public enum class TiltMatch {
    /** An ETC with the same UTC time-of-fix, received close to the fix. */
    MATCHED,
    /** No ETC since the tracker was created / reset (RS2+, ETC not enabled in the stream, or not yet). */
    NO_ETC_SEEN,
    /** ETC had been seen, but none arrived within the stream timeout before the fix. */
    ETC_STREAM_STALE,
    /** The fix has no usable UTC time-of-day, so nothing can be matched. */
    NO_FIX_TIME,
    /** ETC is flowing but none carries this fix's time (late, dropped, or the fix's ETC not here yet). */
    NOT_MATCHED,
    /** Several ETCs match the time but disagree (e.g. a reconnect replayed an old epoch). */
    AMBIGUOUS,
    /** The matching ETC does not fit the documented layout. */
    MALFORMED,
}

/**
 * Tilt state for one position.
 *
 * @property state [EmlidTiltState.UNKNOWN] unless [match] is [TiltMatch.MATCHED].
 * @property reading the matched ETC (also set for [TiltMatch.MALFORMED], for diagnostics).
 * @property match how the association was decided.
 */
public data class EmlidTiltAssociation(
    val state: EmlidTiltState,
    val reading: EmlidTiltReading?,
    val match: TiltMatch,
) {
    public companion object {
        internal fun unknown(match: TiltMatch, reading: EmlidTiltReading? = null): EmlidTiltAssociation =
            EmlidTiltAssociation(EmlidTiltState.UNKNOWN, reading, match)
    }
}

/**
 * Pairs Emlid ETC epochs with fixes, conservatively.
 *
 * Emlid's ETC document (ETC_rev.3_RS4.pdf) says "ETC frequency is always the same as the GGA frequency.
 * You can rely on the timestamps to determine which message corresponds to which." So an ETC belongs to
 * a fix only when its UTC time-of-fix equals the GGA time (within [timeToleranceS]) **and** it was
 * received within [maxReceiptSkewMs] of that fix (so an old ETC whose time-of-day happens to match —
 * e.g. replayed after a reconnect, or 24 h earlier — is never used). Anything else is
 * [EmlidTiltState.UNKNOWN]; the latest ETC is never carried over to a newer fix.
 *
 * Defaults, from the stream rate: ETC runs at the GGA rate (5 Hz default; Emlid Flow does not let the
 * ETC rate be changed), i.e. epochs 200 ms apart (100 ms at 10 Hz). Times have 0.01 s resolution, so
 * [DEFAULT_TIME_TOLERANCE_S] = 0.005 s (half the 0.01 s NMEA time resolution) can only match the same
 * epoch. [DEFAULT_MAX_RECEIPT_SKEW_MS] = 1000 ms (5 epochs at 5 Hz) absorbs TCP batching. The stream is
 * called stale after [DEFAULT_STREAM_TIMEOUT_MS] = 2000 ms (10 missed epochs) without an ETC.
 *
 * In the stream an epoch's GGA arrives **before** its ETC, so associate after the ETC had a chance to
 * arrive (e.g. on the next GGA, or at capture time); an early call returns [TiltMatch.NOT_MATCHED].
 *
 * Feed ETCs with [onEtc] / [onSentence] (receive time from a monotonic clock in ms) and query with
 * [associate]. Thread-safe. No logging, no effect on any fix.
 *
 * @param timeToleranceS max |ETC time − fix time|, seconds (wraps at midnight).
 * @param maxReceiptSkewMs max |ETC receive time − fix receive time|, ms.
 * @param streamTimeoutMs no ETC for this long before the fix → [TiltMatch.ETC_STREAM_STALE].
 * @param capacity ETC epochs kept (64 ≈ 13 s at 5 Hz).
 */
public class EmlidTiltTracker(
    private val timeToleranceS: Double = DEFAULT_TIME_TOLERANCE_S,
    private val maxReceiptSkewMs: Long = DEFAULT_MAX_RECEIPT_SKEW_MS,
    private val streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(timeToleranceS >= 0.0 && timeToleranceS.isFinite()) { "timeToleranceS must be >= 0" }
        require(maxReceiptSkewMs >= 0) { "maxReceiptSkewMs must be >= 0" }
        require(streamTimeoutMs >= 0) { "streamTimeoutMs must be >= 0" }
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private class Entry(val reading: EmlidTiltReading, val receivedAtMs: Long)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var lastEtcAtMs: Long? = null
    private var latest: EmlidTiltReading? = null

    /** Records one ETC received at [receivedAtMs]. */
    public fun onEtc(etc: ETC, receivedAtMs: Long) {
        val reading = EmlidTiltReading.from(etc)
        synchronized(lock) {
            lastEtcAtMs = receivedAtMs
            latest = reading
            entries.addLast(Entry(reading, receivedAtMs))
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** Records [sentence] if it is an ETC; returns true when it was. */
    public fun onSentence(sentence: NmeaSentence, receivedAtMs: Long): Boolean {
        if (sentence !is ETC) return false
        onEtc(sentence, receivedAtMs)
        return true
    }

    /** The most recent ETC reading regardless of any fix (status display only, never for a position). */
    public fun latestReading(): EmlidTiltReading? = synchronized(lock) { latest }

    /** Receive time of the most recent ETC, or null when none since creation / [reset]. */
    public fun lastEtcReceivedAtMs(): Long? = synchronized(lock) { lastEtcAtMs }

    /** True when an ETC arrived within the stream timeout before [nowMs]. */
    public fun isEtcStreamLive(nowMs: Long): Boolean = synchronized(lock) {
        val last = lastEtcAtMs ?: return false
        nowMs - last in 0..streamTimeoutMs
    }

    /** Forgets every ETC (call when the source changes or reconnects). */
    public fun reset(): Unit = synchronized(lock) {
        entries.clear(); lastEtcAtMs = null; latest = null
    }

    /** [associate] for a GGA: its time field is the fix's time-of-day. */
    public fun associate(gga: GGA, ggaReceivedAtMs: Long): EmlidTiltAssociation =
        associate(EtcFields.parseTimeOfDay(gga.timeRaw), ggaReceivedAtMs)

    /**
     * Tilt state for the fix whose UTC time-of-day is [fixTimeOfDaySeconds] (from its GGA, not the
     * device clock) and which was received at [fixReceivedAtMs] (same clock as [onEtc]).
     */
    public fun associate(fixTimeOfDaySeconds: Double?, fixReceivedAtMs: Long): EmlidTiltAssociation =
        synchronized(lock) {
            val last = lastEtcAtMs ?: return EmlidTiltAssociation.unknown(TiltMatch.NO_ETC_SEEN)
            if (fixTimeOfDaySeconds == null || !fixTimeOfDaySeconds.isFinite()) {
                return EmlidTiltAssociation.unknown(TiltMatch.NO_FIX_TIME)
            }
            val candidates = entries.filter { e ->
                val t = e.reading.timeOfFixSecondsOfDay
                t != null &&
                    timeOfDayDistance(t, fixTimeOfDaySeconds) <= timeToleranceS &&
                    abs(e.receivedAtMs - fixReceivedAtMs) <= maxReceiptSkewMs
            }
            if (candidates.isEmpty()) {
                val stale = fixReceivedAtMs - last > streamTimeoutMs
                return EmlidTiltAssociation.unknown(if (stale) TiltMatch.ETC_STREAM_STALE else TiltMatch.NOT_MATCHED)
            }
            val readings = candidates.map { it.reading }.distinct()
            if (readings.size > 1) return EmlidTiltAssociation.unknown(TiltMatch.AMBIGUOUS)
            val reading = readings.single()
            if (reading.state == EmlidTiltState.UNKNOWN) {
                return EmlidTiltAssociation.unknown(TiltMatch.MALFORMED, reading)
            }
            EmlidTiltAssociation(reading.state, reading, TiltMatch.MATCHED)
        }

    public companion object {
        /**
         * 0.005 s: an ETC carries its GGA's own time and NMEA times have 0.01 s resolution, so a true match is
         * exact; half the resolution absorbs floating-point rounding and can never reach a neighbouring epoch,
         * even at 20 Hz (0.05 s apart).
         */
        public const val DEFAULT_TIME_TOLERANCE_S: Double = 0.005
        /** 1 s: five 5 Hz epochs of receive-time slack for TCP batching. */
        public const val DEFAULT_MAX_RECEIPT_SKEW_MS: Long = 1_000L
        /** 2 s: ten missed 5 Hz ETC epochs. */
        public const val DEFAULT_STREAM_TIMEOUT_MS: Long = 2_000L
        /** 64 epochs ≈ 13 s at 5 Hz. */
        public const val DEFAULT_CAPACITY: Int = 64

        private const val DAY_S = 86_400.0

        /** |a − b| on the 24 h circle, seconds. */
        internal fun timeOfDayDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % DAY_S
            return min(d, DAY_S - d)
        }
    }
}
