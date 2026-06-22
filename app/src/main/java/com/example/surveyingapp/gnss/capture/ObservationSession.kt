package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.accuracy.AccuracyEstimator
import com.example.surveyingapp.gnss.accuracy.UereTable
import com.example.surveyingapp.gnss.capture.math.Geodesy
import com.example.surveyingapp.gnss.capture.math.RunningStats
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
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
    private val fixes: SharedFlow<Fix>,
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
            val vAccM: Double?
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
        var samples = 0

        job = scope.launch {
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
                    val freshFix = Duration.between(fix.timeUtc, Instant.now()).seconds <= policy.maxFixAgeSec
                    val freshCorr = fix.diffAgeS?.let { it <= policy.maxDiffAgeSec } ?: true
                    okMode && freshFix && freshCorr && fix.altEllipsoidalM != null
                }
                .onEach { fix ->
                    /*
                     * Convert the accepted geodetic position to ECEF before averaging.
                     * This puts the samples into a meter-based 3D coordinate system.
                     */
                    val (x, y, z) = Geodesy.llaToEcef(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM!!)
                    xs.push(x); ys.push(y); zs.push(z)
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

                    val elapsed = Duration.between(start, Instant.now()).seconds.toInt()
                    _state.value = State.Capturing(
                        startedAt = start,
                        elapsedSec = elapsed,
                        samples = samples,
                        rtkStatus = fix.rtkStatus.name,
                        hAccM = h,
                        vAccM = v
                    )

                    val reachedDuration = elapsed >= policy.minDurationSec
                    val reachedSamples = samples >= policy.minSamples
                    val capDuration = elapsed >= policy.maxDurationSec

                    /*
                     * Finish when both minimum quality gates are satisfied, or when the
                     * maximum duration is reached. The maximum duration prevents a session
                     * from running indefinitely if the sample count is slow. When the cap
                     * forces the finish without meeting the minimums, requirementsMet is
                     * false so the UI can flag the capture as a timeout.
                     */
                    val requirementsMet = reachedDuration && reachedSamples
                    if (requirementsMet || capDuration) {
                        finalize(start, xs, ys, zs, samples, requirementsMet)
                    }
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
        xs: RunningStats, ys: RunningStats, zs: RunningStats, samples: Int,
        requirementsMet: Boolean
    ) {
        /*
         * Convert the averaged ECEF coordinate back to latitude, longitude, and
         * ellipsoidal height for storage and display.
         */
        val (lat, lon, h) = Geodesy.ecefToLla(xs.mean(), ys.mean(), zs.mean())

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
            correctionStationId = lastCorrectionStationId
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