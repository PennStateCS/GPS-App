package com.example.surveyingapp.gnss.capture.math

/**
 * Welford running mean/variance for stable online stats.
 */
class RunningStats {
    var n: Long = 0; private set    // Count of data points processed
    private var mean = 0.0          // Running mean (average) of all values
    private var m2 = 0.0            // Sum of squares of differences from current mean

    fun push(x: Double) {
        n++                         // Increment sample count
        val delta = x - mean        // Difference between new value and current mean
        mean += delta / n           // Update mean: new_mean = old_mean + (x - old_mean) / n
        val delta2 = x - mean       // Difference between new value and updated mean
        m2 += delta * delta2        // Update sum of squares: M2 = M2 + delta * delta2
    }

    fun mean(): Double = mean       // Return current running mean
    fun variance(): Double = if (n > 1) m2 / (n - 1) else 0.0  // Sample variance (Bessel's correction: n-1)
    fun stddev(): Double = kotlin.math.sqrt(variance())         // Standard deviation (square root of variance)
}
