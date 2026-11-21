package app.surrealar.geo

import kotlin.math.sqrt

/**
 * The app's horizontal accuracy (`hAccM`) is a **68 % horizontal radius** — what Android's
 * `Location.accuracy` reports and what the mock location hands to ARCore.
 *
 * For a circular 2-D normal error with per-axis standard deviation σ, the radius containing a
 * fraction p of fixes is σ·sqrt(−2·ln(1 − p)). For p = 0.6827 that is **1.515·σ**.
 *
 * Receiver GST gives per-axis σ (error-ellipse semi-axes σ1/σ2, or lat/lon std-devs). We combine
 * them as the RMS per-axis σ = sqrt((σ1² + σ2²)/2) and scale by 1.515. For an elongated ellipse
 * this slightly over-states the true 68 % radius (conservative), which is acceptable.
 *
 * Vertical accuracy stays a plain 1σ (GST σ_alt, Android verticalAccuracyMeters).
 */
object HorizontalAccuracy {

    /** Radius / per-axis σ for 68.27 % of a circular 2-D normal: sqrt(−2·ln(1 − 0.6827)). */
    const val R68_PER_SIGMA = 1.515

    /** 68 % horizontal radius from two per-axis 1σ values (ellipse semi-axes or lat/lon σ). */
    fun r68(sigma1M: Double, sigma2M: Double): Double =
        R68_PER_SIGMA * sqrt((sigma1M * sigma1M + sigma2M * sigma2M) / 2.0)

    /** 68 % horizontal radius from a single per-axis 1σ (e.g. HDOP × UERE). */
    fun r68(perAxisSigmaM: Double): Double = R68_PER_SIGMA * perAxisSigmaM
}
