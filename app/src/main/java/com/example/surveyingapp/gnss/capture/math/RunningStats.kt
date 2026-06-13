package com.example.surveyingapp.gnss.capture.math

/**
 * Online mean and variance using Welford's algorithm.
 *
 * This approach accumulates statistics in a single pass without storing all the
 * data points, and it stays numerically stable even for very large or closely
 * clustered values. Used to track the mean and spread of ECEF coordinates during
 * a capture session.
 */
class RunningStats {
    var n: Long = 0; private set    // Number of samples pushed so far
    private var mean = 0.0
    private var m2 = 0.0            // Sum of squared deviations (used to compute variance)

    fun push(x: Double) {
        n++
        val delta = x - mean        // Difference from current mean before update
        mean += delta / n
        val delta2 = x - mean       // Difference from updated mean
        m2 += delta * delta2        // Accumulate squared deviations (Welford update step)
    }

    fun mean(): Double = mean
    fun variance(): Double = if (n > 1) m2 / (n - 1) else 0.0  // Sample variance (Bessel's correction)
    fun stddev(): Double = kotlin.math.sqrt(variance())
}
