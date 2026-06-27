package com.example.surveyingapp.gnss.capture.math

/**
 * Tracks running mean, variance, and standard deviation for a stream of samples.
 *
 * This uses Welford's online algorithm. Instead of storing every sample and
 * recalculating statistics at the end, it updates the mean and variance state
 * each time a new value is pushed.
 *
 * This is useful for GNSS capture because ECEF coordinates can be large numbers
 * with small changes between samples. A naive variance formula such as
 * `E[x²] - E[x]²` can lose precision when values are large and tightly clustered.
 * Welford's method avoids most of that cancellation error.
 */
class RunningStats {
    var n: Long = 0; private set    // Number of samples included in the running statistics.
    private var mean = 0.0

    /*
     * Sum of squared deviations from the running mean.
     *
     * This is often written as M2 in descriptions of Welford's algorithm.
     * It is not the variance by itself. Sample variance is computed as:
     *
     * variance = M2 / (n - 1)
     *
     * after at least two samples have been added.
     */
    private var m2 = 0.0

    fun push(x: Double) {
        n++

        /*
         * delta is the difference between the new sample and the old mean.
         * The mean is then moved a fraction of that distance based on the
         * new sample count.
         */
        val delta = x - mean
        mean += delta / n

        /*
         * delta2 is the difference between the new sample and the updated mean.
         * Multiplying delta by delta2 gives the stable Welford update for M2.
         */
        val delta2 = x - mean
        m2 += delta * delta2
    }

    fun mean(): Double = mean

    /*
     * Returns sample variance, using Bessel's correction.
     *
     * Dividing by n - 1 estimates the variance of the underlying population from
     * the captured sample set. With fewer than two samples, variance is undefined,
     * so this returns 0.0 for safe downstream use.
     */
    fun variance(): Double = if (n > 1) m2 / (n - 1) else 0.0

    fun stddev(): Double = kotlin.math.sqrt(variance())
}