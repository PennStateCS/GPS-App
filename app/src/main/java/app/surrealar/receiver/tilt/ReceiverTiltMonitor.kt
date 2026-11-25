package app.surrealar.receiver.tilt

import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EmlidNmea
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltAssociation
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltReading
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltTracker
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receiver tilt status for the active external source (Phase 4 WP C): one [EmlidTiltTracker], fed with the
 * ETC sentences of the live TCP NMEA stream, reset when the source, receiver profile or connection changes.
 *
 * Status only: it never changes a fix. Positions keep flowing through geokit-gnss unchanged; ETC lines are
 * read here from the stream's line hook ([onLine], beside DiagnosticsService) with a private Emlid ETC parser
 * (checksum required), so geokit-gnss parsing and fusion are untouched.
 *
 * - [associate] pairs a fix with its own epoch's ETC (geokit rules: same UTC time-of-fix, received within 1 s;
 *   a stale ETC is never paired with a fresh fix). ETC follows its GGA in the stream, so it waits for that ETC
 *   until at most [etcWaitMs] after the fix's *arrival* ([recordArrival]), never after its processing time.
 * - [classify] adds the profile / "Compensated NMEA output" / firmware interpretation ([PositionReferencePolicy]).
 * - [status] is the latest interpretation for the UI and diagnostics (Settings → Receiver, Developer page).
 *
 * @param profile the active receiver profile.
 * @param compensatedOutput Settings → Receiver → "Compensated NMEA output (Emlid Flow)".
 * @param firmware receiver firmware from ReceiverDeviceManager's device info (null when not known).
 * @param resetSignals each emission after the first resets the tracker (source / profile / connection change).
 * @param clockMs monotonic clock (SystemClock.elapsedRealtime in the app), same clock for ETC and fixes.
 * @param tickMs status refresh period while no fix arrives (ETC freshness); null = no ticker (tests).
 */
class ReceiverTiltMonitor(
    private val scope: CoroutineScope,
    private val profile: StateFlow<ExternalReceiverProfile>,
    private val compensatedOutput: StateFlow<CompensatedOutputSetting>,
    private val firmware: StateFlow<String?>,
    resetSignals: Flow<Any?>,
    private val clockMs: () -> Long,
    private val etcWaitMs: Long = DEFAULT_ETC_WAIT_MS,
    tickMs: Long? = DEFAULT_TICK_MS,
) {
    private val tracker = EmlidTiltTracker()
    /** ETC only (the registry rejects every other sentence as unknown without parsing it). */
    private val etcRegistry = NmeaRegistry(
        mapOf(EmlidNmea.TAG_ETC to EmlidNmea.parsers.getValue(EmlidNmea.TAG_ETC)),
        verifyChecksum = true, requireChecksum = true,
    )

    /** Incremented per ETC (wakes [associate] waiting for an epoch's ETC). */
    private val etcArrivals = MutableStateFlow(0L)
    private val rateLock = Any()
    private val recentEtcAtMs = ArrayDeque<Long>()
    @Volatile private var etcTotal = 0L
    @Volatile private var lastFixClassification: EpochClassification? = null
    @Volatile private var lastFixClassifiedAtMs: Long? = null

    private val _status = MutableStateFlow(TiltStatus.NONE)
    /** Latest tilt status / position-reference interpretation (status only). */
    val status: StateFlow<TiltStatus> = _status.asStateFlow()

    init {
        resetOn(resetSignals)
        scope.launch {
            combine(profile, compensatedOutput, firmware) { _, _, _ -> Unit }.collect { refreshStatus() }
        }
        if (tickMs != null) scope.launch {
            while (true) { delay(tickMs); refreshStatus() }
        }
    }

    /**
     * One raw NMEA line from the external TCP stream (called on the reader thread for every line). Only
     * lines that look like ETC are parsed; never throws.
     */
    fun onLine(line: String) {
        if (!line.contains("ETC,")) return
        val sentence = try { etcRegistry.parse(line) } catch (_: Exception) { null } ?: return
        val now = clockMs()
        if (tracker.onSentence(sentence, now)) {
            etcTotal++
            synchronized(rateLock) {
                recentEtcAtMs.addLast(now)
                while (recentEtcAtMs.size > RATE_WINDOW) recentEtcAtMs.removeFirst()
            }
            etcArrivals.value = etcArrivals.value + 1
        }
    }

    /** Resets the tracker on every emission of [signals] after its first (e.g. the link's connection state). */
    fun resetOn(signals: Flow<Any?>) {
        scope.launch { signals.distinctUntilChanged().drop(1).collect { reset() } }
    }

    /** Forgets every ETC and the last interpretation (source, profile or connection changed). */
    fun reset() {
        tracker.reset()
        synchronized(rateLock) { recentEtcAtMs.clear() }
        lastFixClassification = null
        lastFixClassifiedAtMs = null
        refreshStatus()
    }

    /** Arrival time ([clockMs]) per published fix instance, see [recordArrival]. */
    private class Key(val fix: Fix) {
        override fun equals(other: Any?) = other is Key && other.fix === fix
        override fun hashCode() = System.identityHashCode(fix)
    }
    private val arrivals = object : LinkedHashMap<Key, Long>(ARRIVAL_CAPACITY * 2) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>?) = size > ARRIVAL_CAPACITY
    }

    /**
     * Records when [fix] arrived from the feed (the switchboard's fix transform calls this for every published
     * fix, before any subscriber buffers or suspends). Returns [fix] unchanged; never throws.
     *
     * Why: the capture's filter chain and [observe] process fixes one after another and may wait for an ETC, so the
     * time a fix is *processed* can trail its arrival. Pairing by processing time would make every later fix look
     * later than its ETC (NOT_MATCHED, then another wait), a lag that never recovers. [associate] and [classify]
     * use the arrival time instead.
     */
    fun recordArrival(fix: Fix): Fix {
        try {
            val now = clockMs()
            synchronized(arrivals) { arrivals[Key(fix)] = now }
        } catch (_: Exception) { /* status only: never disturb the feed */ }
        return fix
    }

    /** When [fix] arrived from the feed ([recordArrival]); null when not recorded (evicted, or not from the feed). */
    fun arrivalOf(fix: Fix): Long? = synchronized(arrivals) { arrivals[Key(fix)] }

    /**
     * The ETC association of an external [fix] that arrived at [seenAtMs] (clock of [clockMs]; default: its
     * recorded arrival, else now). While the fix's own ETC has not arrived yet (ETC follows its GGA) it waits until
     * at most [seenAtMs] + [etcWaitMs]: a fix processed late never waits beyond that, so a dropped ETC costs at
     * most one wait and never delays the fixes behind it. Never pairs an older ETC.
     */
    suspend fun associate(fix: Fix, seenAtMs: Long = arrivalOf(fix) ?: clockMs()): EmlidTiltAssociation {
        val tod = timeOfDaySeconds(fix)
        var a = tracker.associate(tod, seenAtMs)
        if (a.match == TiltMatch.NOT_MATCHED && etcWaitMs > 0) {
            val remaining = seenAtMs + etcWaitMs - clockMs()
            if (remaining > 0) withTimeoutOrNull(remaining) {
                etcArrivals.first {
                    a = tracker.associate(tod, seenAtMs)
                    a.match != TiltMatch.NOT_MATCHED
                }
            }
        }
        return a
    }

    /**
     * Interprets [fix] with the given settings ([profile] / [output]; [firmware], default the live firmware). A
     * non-RS4 profile is [EpochReference.NOT_APPLICABLE] at once, without waiting for an ETC.
     *
     * @param profileKnown false when the receiver profile could not be read: the fix is then interpreted
     *   conservatively as an IMU-capable receiver with the output setting UNKNOWN (never a pole tip; possibly
     *   compensated when the ETC shows the tilt engine on), instead of being passed through untouched.
     * @param waitOnlyWhenNeeded true (captures): do not wait for the epoch's ETC when its match cannot change the
     *   reference (output DISABLED is the antenna whatever the ETC says). The status path keeps waiting so the
     *   shown reason (e.g. "tilt off") is the epoch's own.
     */
    suspend fun classify(
        fix: Fix,
        profile: ExternalReceiverProfile? = this.profile.value,
        output: CompensatedOutputSetting = compensatedOutput.value,
        seenAtMs: Long = arrivalOf(fix) ?: clockMs(),
        firmware: String? = this.firmware.value,
        profileKnown: Boolean = true,
        waitOnlyWhenNeeded: Boolean = false,
    ): EpochClassification {
        if (fix.provider == Provider.INTERNAL || fix.provider == Provider.REPLAY) {
            return EpochClassification(EpochReference.NOT_APPLICABLE, null, null)
        }
        if (profileKnown && !PositionReferencePolicy.appliesTo(profile)) {
            return EpochClassification(EpochReference.NOT_APPLICABLE, null, null)
        }
        val effectiveOutput = if (profileKnown) output else CompensatedOutputSetting.UNKNOWN
        val tilt = if (waitOnlyWhenNeeded && profileKnown && effectiveOutput == CompensatedOutputSetting.DISABLED) {
            tracker.associate(timeOfDaySeconds(fix), seenAtMs)
        } else {
            associate(fix, seenAtMs)
        }
        return if (profileKnown) {
            PositionReferencePolicy.evaluate(profile, effectiveOutput, firmware, tilt, recentTiltOn(seenAtMs))
        } else {
            PositionReferencePolicy.evaluateUnknownProfile(firmware, tilt, recentTiltOn(seenAtMs))
        }
    }

    /** The receiver firmware the rules use now (last known for this receiver; null = unknown). */
    fun currentFirmware(): String? = firmware.value

    /** True when the stream is live and its latest ETC showed the tilt engine on (in any state). */
    fun recentTiltOn(nowMs: Long = clockMs()): Boolean =
        tracker.isEtcStreamLive(nowMs) && PositionReferencePolicy.isTiltOn(tracker.latestReading()?.state)

    /**
     * Status path: classify each external fix with the live settings and publish [status]. Call once with the
     * switchboard's fixes (GnssModule).
     */
    fun observe(fixes: Flow<Fix>) {
        scope.launch {
            fixes.collect { fix ->
                if (fix.provider != Provider.EXTERNAL_TCP && fix.provider != Provider.EXTERNAL_BT) return@collect
                // Arrival time (recorded by the feed before any buffering), not this collector's processing time.
                val seen = arrivalOf(fix) ?: clockMs()
                val c = classify(fix, seenAtMs = seen)
                lastFixClassification = c
                lastFixClassifiedAtMs = seen
                refreshStatus()
            }
        }
    }

    /**
     * True when the latest external positions may be the pole tip (receiver-compensated, or possibly so). The
     * AR camera height is derived from the antenna (antenna height above the camera), so such fixes must not
     * feed it: the mock-location publisher then leaves the altitude out.
     */
    fun heightsMayBePoleTip(): Boolean {
        val s = _status.value
        if (!s.applicable) return false
        return s.reference == EpochReference.POLE_TIP || s.reference == EpochReference.MAY_BE_COMPENSATED
    }

    /** Recomputes [status] now (also run by the ticker, each observed fix, settings changes and resets). */
    internal fun refreshStatus() {
        val now = clockMs()
        val p = profile.value
        val latest = tracker.latestReading()
        val lastAt = tracker.lastEtcReceivedAtMs()
        val live = tracker.isEtcStreamLive(now)
        val classifiedAt = lastFixClassifiedAtMs
        val c = lastFixClassification?.takeIf { classifiedAt != null && now - classifiedAt <= FIX_STATUS_MAX_AGE_MS }
        _status.value = TiltStatus(
            applicable = PositionReferencePolicy.appliesTo(p),
            profile = p,
            compensatedOutput = compensatedOutput.value,
            firmware = firmware.value,
            latest = if (live) latest else null,
            etcSeen = lastAt != null,
            etcLive = live,
            etcAgeMs = lastAt?.let { now - it },
            etcRateHz = etcRate(now),
            etcCount = etcTotal,
            reference = c?.reference,
            result = c?.result,
            match = c?.tilt?.match,
        )
    }

    private fun etcRate(now: Long): Double? = synchronized(rateLock) {
        if (recentEtcAtMs.size < 2) return null
        val last = recentEtcAtMs.last()
        if (now - last > EmlidTiltTracker.DEFAULT_STREAM_TIMEOUT_MS) return 0.0
        val span = last - recentEtcAtMs.first()
        if (span <= 0) null else (recentEtcAtMs.size - 1) * 1000.0 / span
    }

    companion object {
        /** Up to 1 s for an epoch's ETC to follow its GGA (TCP batching); normally a few ms. */
        const val DEFAULT_ETC_WAIT_MS = 1_000L
        const val DEFAULT_TICK_MS = 1_000L
        /** A fix interpretation older than this is not shown as current. */
        const val FIX_STATUS_MAX_AGE_MS = 5_000L
        private const val RATE_WINDOW = 26
        /** Fix arrival times remembered: twice the switchboard's 64-fix buffer (as ReceiverFeedDatum). */
        const val ARRIVAL_CAPACITY = 128
        private const val DAY_MS = 86_400_000L

        /**
         * UTC time of day of a fix, seconds: the feed's fix time is the GGA time-of-day on a date from ZDA/RMC
         * (or the device date). A pure device-clock time (no GGA time at all) is not on the 10 ms NMEA grid and
         * is treated as unknown, so it is never matched to an ETC by chance.
         */
        fun timeOfDaySeconds(fix: Fix): Double? {
            val ms = fix.timeUtc.toEpochMilli()
            if (fix.timestampSource == TimestampSource.DEVICE && Math.floorMod(ms, 10L) != 0L) return null
            if (fix.timestampSource == TimestampSource.UNKNOWN) return null
            return Math.floorMod(ms, DAY_MS) / 1000.0
        }
    }
}

/**
 * Tilt status for the UI and diagnostics. [latest] is the newest ETC while its stream is live (status only,
 * never paired with a position); [reference] / [result] / [match] describe the most recent external fix.
 */
data class TiltStatus(
    val applicable: Boolean,
    val profile: ExternalReceiverProfile?,
    val compensatedOutput: CompensatedOutputSetting,
    val firmware: String?,
    val latest: EmlidTiltReading?,
    val etcSeen: Boolean,
    val etcLive: Boolean,
    val etcAgeMs: Long?,
    val etcRateHz: Double?,
    val etcCount: Long,
    val reference: EpochReference?,
    val result: io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference.Result?,
    val match: TiltMatch?,
) {
    /** Tilt state as shown: from the live ETC, or null when there is none. */
    val tiltState: EmlidTiltState? get() = latest?.state

    companion object {
        val NONE = TiltStatus(false, null, CompensatedOutputSetting.DEFAULT, null, null, false, false, null, null, 0, null, null, null)
    }
}
