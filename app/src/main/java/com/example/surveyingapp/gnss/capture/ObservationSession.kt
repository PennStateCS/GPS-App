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
 * Collects fixes under a policy, averages in ECEF, and produces a CaptureResult.
 * Pauses collection if fixes become stale or quality drops below the policy.
 */
class ObservationSession(
    private val scope: CoroutineScope,
    private val fixes: SharedFlow<Fix>,
    private val policy: AveragingPolicy,
    private val uere: UereTable = UereTable()
) {

    sealed interface State {
        data object Idle : State
        data class Capturing(
            val startedAt: Instant,
            val elapsedSec: Int,
            val samples: Int,
            val rtkStatus: String,
            val hAccM: Double?,
            val vAccM: Double?
        ) : State
        data class Complete(val result: CaptureResult) : State
        data class Paused(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    // Track the latest quality snapshot so we can persist it in the result
    private var lastRtk: RtkStatus? = null
    private var lastSatsUsed: Int? = null
    private var lastHdop: Double? = null
    private var lastHAcc: Double? = null
    private var lastVAcc: Double? = null
    private var lastDiffAge: Double? = null

    fun start() {
        if (job != null) return
        val start = Instant.now()

        val xs = RunningStats(); val ys = RunningStats(); val zs = RunningStats()
        var samples = 0

        job = scope.launch {
            fixes
                .filter { fix ->
                    // Quality gate: RTK status and corrections age if present
                    val okMode = fix.rtkStatus.meetsOrExceeds(policy.requiredMinStatus)
                    val freshFix = Duration.between(fix.timeUtc, Instant.now()).seconds <= policy.maxFixAgeSec
                    val freshCorr = fix.diffAgeS?.let { it <= policy.maxDiffAgeSec } ?: true
                    okMode && freshFix && freshCorr && fix.altEllipsoidalM != null
                }
                .onEach { fix ->
                    // Accumulate in ECEF
                    val (x, y, z) = Geodesy.llaToEcef(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM!!)
                    xs.push(x); ys.push(y); zs.push(z)
                    samples++

                    // Update latest quality snapshot
                    lastRtk = fix.rtkStatus
                    lastSatsUsed = fix.satsUsed
                    lastHdop = fix.hDop
                    val (h, v) = AccuracyEstimator.estimate1SigmaMeters(fix, uere)
                    lastHAcc = h
                    lastVAcc = v
                    lastDiffAge = fix.diffAgeS

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
                    if ((reachedDuration && reachedSamples) || capDuration) {
                        finalize(start, xs, ys, zs, samples)
                    }
                }
                .catch { e -> _state.value = State.Paused("error: ${e.message}") }
                .collect()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun finalize(
        start: Instant,
        xs: RunningStats, ys: RunningStats, zs: RunningStats, samples: Int
    ) {
        val (lat, lon, h) = Geodesy.ecefToLla(xs.mean(), ys.mean(), zs.mean())
        val result = CaptureResult(
            startedAt = start,
            endedAt = Instant.now(),
            samples = samples,
            latDeg = lat,
            lonDeg = lon,
            altEllipsoidalM = h,
            ecefStd = Triple(xs.stddev(), ys.stddev(), zs.stddev()),
            // New quality fields (from the latest good epoch)
            rtkStatus = lastRtk,
            satsUsed = lastSatsUsed,
            hdop = lastHdop,
            hAccM = lastHAcc,
            vAccM = lastVAcc,
            diffAgeS = lastDiffAge
        )
        // Stop collection job without resetting state to Idle (preserve Complete state)
        job?.cancel()
        job = null
        _state.value = State.Complete(result)
    }
}
