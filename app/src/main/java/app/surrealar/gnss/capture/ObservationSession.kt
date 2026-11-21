package app.surrealar.gnss.capture

import app.surrealar.gnss.accuracy.AccuracyEstimator
import app.surrealar.gnss.accuracy.UereTable
import app.surrealar.geo.ellipsoid.Geodesy
import app.surrealar.geo.stats.RunningStats
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.RtkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Runs a GNSS averaging capture session.
 *
 * The session listens to a stream of fixes, accepts only fixes that satisfy the
 * averaging policy, converts each accepted latitude/longitude/height position to
 * ECEF, and averages the ECEF coordinates.
 *
 * ECEF is used for averaging because latitude and longitude are angular values on
 * an ellipsoid. Averaging them directly can introduce small geometric errors,
 * especially when points are spread out or near coordinate wrap boundaries. ECEF
 * represents each fix as an X/Y/Z position in meters, so the mean is computed in
 * a linear coordinate space before being converted back to latitude, longitude,
 * and ellipsoidal height.
 *
 * Collection pauses by ignoring fixes that are stale, below the required RTK
 * quality, missing ellipsoidal height, or using stale correction data.
 */
class ObservationSession(
    private val scope: CoroutineScope,
    // A plain Flow (not SharedFlow) so callers can pass a provider-filtered stream — e.g. an
    // external capture passes only external-provider fixes. Only Flow operators are used below,
    // so the averaging behavior is unchanged.
    private val fixes: Flow<Fix>,
    private val policy: AveragingPolicy,
    private val uere: UereTable = UereTable()
) {

    /**
     * Public state of the capture session.
     *
     * UI code can observe this state to show capture progress, pause reasons, or
     * the final averaged result.
     */
    sealed interface State {
        data object Idle : State

        /**
         * Capture is actively accepting fixes that pass the policy gates.
         *
         * The accuracy values shown here are from the most recent accepted fix, not
         * from the final averaged result.
         */
        data class Capturing(
            val startedAt: Instant,
            val elapsedSec: Int,
            val samples: Int,
            val rtkStatus: String,
            val hAccM: Double?,
            val vAccM: Double?,
            /** Measured horizontal spread of the samples so far (precision-based stop); null with < 2 samples. */
            val spreadHorizontalM: Double? = null
        ) : State

        /**
         * Capture finished and produced an averaged result.
         *
         * [requirementsMet] is true when both the minimum sampling time and the
         * minimum accepted-fix count were satisfied. It is false when the session
         * finished only because the maximum sampling time was reached first — the
         * averaged result is still valid but under-sampled, and the UI should say so.
         */
        data class Complete(
            val result: CaptureResult,
            val requirementsMet: Boolean
        ) : State

        /** Capture could not continue because the stream failed or became invalid. */
        data class Paused(val reason: String) : State
    }

    companion object {
        /** [State.Paused] reason when the maximum duration passed without one accepted fix. */
        const val TIMEOUT_NO_ACCEPTED_FIXES = "timeout_no_accepted_fixes"
        /** Consecutive stale fixes after which the freshness baseline is rebuilt (clock step). */
        internal const val STALE_RESET_COUNT = 5
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /*
     * Quality snapshot copied from the most recent accepted fix.
     *
     * These fields are reported with the final CaptureResult so the stored point
     * includes the receiver quality, satellite counts, DOP values, accuracy, and
     * correction metadata available at the end of the capture.
     */
    private var lastRtk: RtkStatus? = null
    private var lastSatsUsed: Int? = null
    private var lastSatsVisible: Int? = null
    private var lastHdop: Double? = null
    private var lastVDop: Double? = null
    private var lastPDop: Double? = null
    private var lastHAcc: Double? = null
    private var lastVAcc: Double? = null
    private var lastDiffAge: Double? = null
    private var lastCorrectionStationId: String? = null
    private var lastFixTimeUtc: Instant? = null
    private var lastTimestampSource: app.surrealar.gnss.model.TimestampSource? = null
    private var lastMultipathIndex: Double? = null
    private var lastStdDevEast: Double? = null
    private var lastStdDevNorth: Double? = null
    private var lastStdDevUp: Double? = null

    /**
     * Starts collecting fixes for the averaging session.
     *
     * Calling start while a session is already active has no effect.
     */
    fun start() {
        if (job != null) return
        val start = Instant.now()

        /*
         * RunningStats keeps the mean and spread of each ECEF axis without storing
         * every accepted fix. X, Y, and Z are tracked independently.
         */
        val xs = RunningStats(); val ys = RunningStats(); val zs = RunningStats()
        // Local East/North/Up offsets from the first accepted sample, for horizontal/vertical spread.
        val es = RunningStats(); val ns = RunningStats(); val us = RunningStats()
        // Geoid separation N per accepted fix (reported, or ellipsoidal − MSL). MSL is derived at the
        // end as averaged ellipsoidal height − mean N, so altitude − altitudeMsl == N exactly.
        val geoidN = RunningStats()
        var ref: Triple<Double, Double, Double>? = null      // first sample ECEF
        var refLatLon: Pair<Double, Double>? = null
        var samples = 0
        var freshness = FixFreshnessGate(maxAgeMs = policy.maxFixAgeSec * 1_000L)
        var consecutiveStale = 0

        fun spreadH() = if (samples > 1) kotlin.math.sqrt(es.variance() + ns.variance()) else null
        fun spreadV() = if (samples > 1) us.stddev() else null

        /**
         * Completes the session when the requirements are met, or when the maximum duration is
         * reached (requirementsMet = false → the UI reports a timeout and Save stays disabled).
         * Returns true when the session ended. Called for every accepted fix AND once per second, so
         * a capture still times out — or completes — when fixes stop being accepted.
         */
        fun evaluate(elapsed: Int): Boolean {
            val target = policy.horizontalPrecisionTargetM
            val reachedPrecision = target == null || (spreadH()?.let { it <= target } == true)
            val requirementsMet = elapsed >= policy.minDurationSec && samples >= policy.minSamples && reachedPrecision
            if (!requirementsMet && elapsed < policy.maxDurationSec) return false
            if (samples == 0) {
                // Timed out without a single accepted fix: nothing to average.
                job?.cancel(); job = null
                _state.value = State.Paused(TIMEOUT_NO_ACCEPTED_FIXES)
                return true
            }
            finalize(start, xs, ys, zs, geoidN, samples, requirementsMet, spreadH(), spreadV())
            return true
        }

        job = scope.launch {
            // Wall-clock tick: keeps the elapsed timer moving and enforces the timeout even when no
            // fix is being accepted (RTK dropped to Float, receiver disconnected, …).
            launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000L)
                    val elapsed = Duration.between(start, Instant.now()).seconds.toInt()
                    (_state.value as? State.Capturing)?.let { _state.value = it.copy(elapsedSec = elapsed) }
                    if (evaluate(elapsed)) break
                }
            }
            fixes
                .filter { fix ->
                    /*
                     * A fix is accepted only when it passes every policy gate:
                     *
                     * - RTK status is at or above the required quality.
                     * - Fix timestamp is recent enough.
                     * - Differential/RTK correction age is still valid.
                     * - Ellipsoidal height exists so the fix can be converted to ECEF.
                     */
                    val okMode = fix.rtkStatus.meetsOrExceeds(policy.requiredMinStatus)
                    // Skew-tolerant (see FixFreshnessGate): tablet clock vs GNSS time no longer matters.
                    val freshFix = freshness.isFresh(fix)
                    // A tablet clock step (e.g. network time sync) would make every later fix look
                    // late against the old baseline; rebuild it after a run of stale fixes.
                    if (freshFix) consecutiveStale = 0
                    else if (++consecutiveStale >= STALE_RESET_COUNT) {
                        freshness = FixFreshnessGate(maxAgeMs = policy.maxFixAgeSec * 1_000L)
                        consecutiveStale = 0
                    }
                    // Only when the capture requires corrections (DGPS/FLOAT/FIX); see AveragingPolicy.
                    val freshCorr = !policy.requiresFreshCorrections ||
                        (fix.diffAgeS?.let { it <= policy.maxDiffAgeSec } ?: true)
                    okMode && freshFix && freshCorr && fix.altEllipsoidalM != null
                }
                .onEach { fix ->
                    /*
                     * Convert the accepted geodetic position to ECEF before averaging.
                     * This puts the samples into a meter-based 3D coordinate system.
                     */
                    val (x, y, z) = Geodesy.llaToEcef(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM!!)
                    xs.push(x); ys.push(y); zs.push(z)
                    val r = ref ?: Triple(x, y, z).also { ref = it; refLatLon = fix.latDeg to fix.lonDeg }
                    val (rl, rn) = refLatLon!!
                    val (de, dn, du) = Geodesy.ecefDeltaToEnu(x - r.first, y - r.second, z - r.third, rl, rn)
                    es.push(de); ns.push(dn); us.push(du)
                    samples++

                    /*
                     * Keep a quality snapshot from the accepted epoch. The final
                     * CaptureResult stores these as the session's ending quality state.
                     */
                    lastRtk = fix.rtkStatus
                    lastSatsUsed = fix.satsUsed
                    lastSatsVisible = fix.satsVisible
                    lastHdop = fix.hDop
                    lastVDop = fix.vDop
                    lastPDop = fix.pDop
                    val (h, v) = AccuracyEstimator.estimate1SigmaMeters(fix, uere)
                    lastHAcc = h
                    lastVAcc = v
                    lastDiffAge = fix.diffAgeS
                    lastCorrectionStationId = fix.correctionStationId
                    (fix.geoidSeparationM ?: fix.altMslM?.let { fix.altEllipsoidalM!! - it })
                        ?.takeIf { it.isFinite() }
                        ?.let { geoidN.push(it) }
                    lastFixTimeUtc = fix.timeUtc
                    lastTimestampSource = fix.timestampSource
                    lastMultipathIndex = fix.multipathIndex
                    lastStdDevEast = fix.stdDevEastM
                    lastStdDevNorth = fix.stdDevNorthM
                    lastStdDevUp = fix.stdDevUpM

                    val elapsed = Duration.between(start, Instant.now()).seconds.toInt()
                    _state.value = State.Capturing(
                        startedAt = start,
                        elapsedSec = elapsed,
                        samples = samples,
                        rtkStatus = fix.rtkStatus.name,
                        hAccM = h,
                        vAccM = v,
                        spreadHorizontalM = spreadH()
                    )

                    /*
                     * Finish when all requirements are satisfied (minimum time, minimum fixes, and the
                     * precision target when one is set), or when the maximum duration is reached (then
                     * requirementsMet is false: the UI flags a timeout and the capture cannot be saved).
                     */
                    evaluate(elapsed)
                }
                .catch { e -> _state.value = State.Paused("error: ${e.message}") }
                .collect()
        }
    }

    /**
     * Cancels the current capture and returns the session to Idle.
     *
     * This is for user-initiated cancellation. A completed session is finalized
     * through finalize(), which leaves the state as Complete.
     */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun finalize(
        start: Instant,
        xs: RunningStats, ys: RunningStats, zs: RunningStats, geoidN: RunningStats, samples: Int,
        requirementsMet: Boolean,
        spreadHorizontalM: Double?,
        spreadVerticalM: Double?,
    ) {
        /*
         * Convert the averaged ECEF coordinate back to latitude, longitude, and
         * ellipsoidal height for storage and display.
         */
        val (lat, lon, h) = Geodesy.ecefToLla(xs.mean(), ys.mean(), zs.mean())
        val meanN = if (geoidN.n > 0) geoidN.mean() else null

        val result = CaptureResult(
            startedAt = start,
            endedAt = Instant.now(),
            samples = samples,
            latDeg = lat,
            lonDeg = lon,
            altEllipsoidalM = h,
            ecefStd = Triple(xs.stddev(), ys.stddev(), zs.stddev()),
            rtkStatus = lastRtk,
            satsUsed = lastSatsUsed,
            satsVisible = lastSatsVisible,
            hdop = lastHdop,
            vDop = lastVDop,
            pDop = lastPDop,
            hAccM = lastHAcc,
            vAccM = lastVAcc,
            diffAgeS = lastDiffAge,
            correctionStationId = lastCorrectionStationId,
            altMslM = meanN?.let { h - it },
            geoidSeparationM = meanN,
            lastFixTimeUtc = lastFixTimeUtc,
            timestampSource = lastTimestampSource,
            multipathIndex = lastMultipathIndex,
            stdDevEastM = lastStdDevEast,
            stdDevNorthM = lastStdDevNorth,
            stdDevUpM = lastStdDevUp,
            spreadHorizontalM = spreadHorizontalM,
            spreadVerticalM = spreadVerticalM
        )

        /*
         * Stop collecting after the result is created. Do not call cancel(), because
         * cancel() resets the public state to Idle and would hide the completed result.
         */
        job?.cancel()
        job = null
        _state.value = State.Complete(result, requirementsMet)
    }
}