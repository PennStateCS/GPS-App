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
 *
 * **The two factors in plain words.** There are two common ways to quote one horizontal number:
 * - *Radial RMSE* (DRMS, the surveyor's "1σ radial error"): sqrt(σlat² + σlon²) — [radialRmse].
 *   No scale factor at all. This is what a survey report should show next to the vertical 1σ.
 * - *68 % radius* (Android's `Location.accuracy`, used for `hAccM`, the "±" in the toolbar and the
 *   mock location): the circle that holds 68 % of fixes. It needs a factor because a 2-D error
 *   spreads over two axes: radius = 1.515 × per-axis σ ([R68_PER_SIGMA]), and since the radial RMSE
 *   is √2 × per-axis σ for a circular error, radius = 1.515 / √2 ≈ 1.0713 × radial RMSE
 *   ([R68_PER_HORIZONTAL_RMS]). So the 68 % radius is only about 7 % larger than the radial RMSE.
 *
 * Example (σlat 0.008 m, σlon 0.006 m): radial RMSE = sqrt(0.008² + 0.006²) = 0.0100 m; 68 % radius =
 * 1.515 × sqrt((0.008² + 0.006²)/2) = 0.0107 m. Neither factor changes the underlying σ values; they
 * only pick which summary of them is quoted.
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

    /**
     * Radial RMSE (DRMS) from the latitude and longitude 1σ values, in metres: sqrt(σlat² + σlon²).
     * This is the 1σ radial horizontal error as surveyors report it (no 68 % scaling); quote it with
     * a separate vertical 1σ. Example: σlat 0.008, σlon 0.006 → 0.0100 m.
     */
    public fun radialRmse(sigmaLatM: Double, sigmaLonM: Double): Double =
        sqrt(sigmaLatM * sigmaLatM + sigmaLonM * sigmaLonM)
}
