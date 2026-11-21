package io.github.pennstatecs.geokit.accuracy

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
 * HDOP × UERE is different: HDOP = sqrt(σE² + σN²)/UERE, so HDOP × UERE is already the horizontal
 * RMS (DRMS ≈ √2·σ ≈ 1.41σ per axis for a circular error), not a per-axis σ. Only the
 * RMS → 68 %-radius step remains: 1.515 / √2 ≈ **1.071** ([R68_PER_HORIZONTAL_RMS]).
 *
 * Vertical accuracy stays a plain 1σ (GST σ_alt, Android verticalAccuracyMeters, VDOP × UERE).
 */
public object HorizontalAccuracy {

    /** Radius / per-axis σ for 68.27 % of a circular 2-D normal: sqrt(−2·ln(1 − 0.6827)). */
    public const val R68_PER_SIGMA: Double = 1.515

    /** Radius / horizontal RMS (DRMS = √2·σ) for 68.27 %: 1.515 / √2 ≈ 1.071. */
    public val R68_PER_HORIZONTAL_RMS: Double = R68_PER_SIGMA / sqrt(2.0)

    /** 68 % horizontal radius from two per-axis 1σ values (ellipse semi-axes or lat/lon σ). */
    public fun r68(sigma1M: Double, sigma2M: Double): Double =
        R68_PER_SIGMA * sqrt((sigma1M * sigma1M + sigma2M * sigma2M) / 2.0)

    /** 68 % horizontal radius from a horizontal RMS (DRMS), e.g. HDOP × UERE. */
    public fun r68FromHorizontalRms(horizontalRmsM: Double): Double = R68_PER_HORIZONTAL_RMS * horizontalRmsM
}
