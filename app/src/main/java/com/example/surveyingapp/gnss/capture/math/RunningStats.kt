package com.example.surveyingapp.gnss.capture.math

/**
 * Welford running mean/variance for stable online stats.
 */
class RunningStats {
    var n: Long = 0; private set
    private var mean = 0.0
    private var m2 = 0.0

    fun push(x: Double) {
        n++
        val delta = x - mean
        mean += delta / n
        val delta2 = x - mean
        m2 += delta * delta2
    }

    fun mean(): Double = mean
    fun variance(): Double = if (n > 1) m2 / (n - 1) else 0.0
    fun stddev(): Double = kotlin.math.sqrt(variance())
}
