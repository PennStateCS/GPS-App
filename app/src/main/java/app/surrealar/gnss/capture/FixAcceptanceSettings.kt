package app.surrealar.gnss.capture

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.RtkStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime (non-persisted) thresholds a live fix must pass to be eligible for capture. Provided with
 * defaults via DI (`SettingsModule`) — it does NOT come from DataStore.
 *
 * Distinct from [GnssCaptureSettings], which is the *persisted* user capture/averaging policy
 * (min/max duration, min samples, required RTK status, fix/correction age) and is what
 * [GnssCaptureSettings.toAveragingPolicy] feeds into [ObservationSession]. This class only gates the
 * live "OK to capture" eligibility check.
 *
 * (Renamed from the former `gnss.settings.CaptureSettings` to remove the naming clash with
 * [GnssCaptureSettings]; same fields, defaults, and `accepts` logic.)
 */
data class FixAcceptanceSettings(
    val minSats: Int = 10,
    val maxPdop: Double = 3.0,
    val allowed: Set<RtkStatus> = setOf(RtkStatus.FIX, RtkStatus.FLOAT),
    val minDwell: Duration = 2.seconds
) {
    /**
     * Returns true if [fix] passes all quality thresholds.
     * Does not consider dwell time or windowing — the session handles those separately.
     */
    fun accepts(fix: Fix): Boolean {
        val satsOk = fix.satsUsed >= minSats
        val pdopOk = (fix.pDop ?: Double.POSITIVE_INFINITY) <= maxPdop
        val modeOk = fix.rtkStatus in allowed
        return satsOk && pdopOk && modeOk
    }
}
