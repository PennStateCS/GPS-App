package app.surrealar.gnss.capture.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Numerical-correctness tests for [RunningStats] (Welford's online mean/variance), which underpins
 * the standard-deviation/spread figures reported for averaged GNSS captures. A regression here would
 * silently corrupt displayed survey precision.
 */
class RunningStatsTest {

    private val tol = 1e-9

    @Test fun `zero samples reports safe defaults`() {
        val s = RunningStats()
        assertEquals(0L, s.n)
        assertEquals(0.0, s.mean(), tol)
        assertEquals(0.0, s.variance(), tol)   // undefined → 0.0 for safe downstream use
        assertEquals(0.0, s.stddev(), tol)
    }

    @Test fun `one sample has the value as mean and zero variance`() {
        val s = RunningStats().apply { push(5.0) }
        assertEquals(1L, s.n)
        assertEquals(5.0, s.mean(), tol)
        assertEquals(0.0, s.variance(), tol)   // n < 2 → variance undefined → 0.0
        assertEquals(0.0, s.stddev(), tol)
    }

    @Test fun `two samples use Bessel-corrected sample variance`() {
        val s = RunningStats().apply { push(10.0); push(20.0) }
        assertEquals(2L, s.n)
        assertEquals(15.0, s.mean(), tol)
        assertEquals(50.0, s.variance(), tol)         // M2=50, /(n-1)=1
        assertEquals(sqrt(50.0), s.stddev(), tol)
    }

    @Test fun `known dataset matches expected mean and sample stddev`() {
        // [2,4,4,4,5,5,7,9] → mean 5, sum of squared deviations 32, sample variance 32/7.
        val s = RunningStats()
        listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0).forEach { s.push(it) }
        assertEquals(8L, s.n)
        assertEquals(5.0, s.mean(), tol)
        assertEquals(32.0 / 7.0, s.variance(), 1e-9)
        assertEquals(sqrt(32.0 / 7.0), s.stddev(), 1e-9)
    }

    @Test fun `identical samples have zero variance`() {
        val s = RunningStats().apply { repeat(50) { push(3.14) } }
        assertEquals(3.14, s.mean(), tol)
        assertEquals(0.0, s.variance(), tol)
        assertEquals(0.0, s.stddev(), tol)
    }

    @Test fun `handles negative and positive values`() {
        val s = RunningStats().apply { push(-2.0); push(2.0) }   // mean 0, M2 = 8
        assertEquals(0.0, s.mean(), tol)
        assertEquals(8.0, s.variance(), tol)
        assertEquals(sqrt(8.0), s.stddev(), tol)
    }

    @Test fun `large clustered values stay numerically stable (Welford vs naive cancellation)`() {
        // ECEF-like: huge magnitude, tiny spread. Variance of (1,2,3) offset by 1e8 is still 1.0.
        val base = 1e8
        val s = RunningStats().apply { push(base + 1); push(base + 2); push(base + 3) }
        assertEquals(base + 2, s.mean(), 1e-3)
        assertEquals(1.0, s.variance(), 1e-6)
        assertEquals(1.0, s.stddev(), 1e-6)
    }
}
