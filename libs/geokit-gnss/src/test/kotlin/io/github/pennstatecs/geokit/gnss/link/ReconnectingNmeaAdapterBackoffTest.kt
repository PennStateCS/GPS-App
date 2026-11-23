package io.github.pennstatecs.geokit.gnss.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Reconnect backoff: 1 s doubling to a 30 s cap, attempt 0 (a drop from CONNECTED) treated as 1. */
class ReconnectingNmeaAdapterBackoffTest {

    /** A Random whose nextLong(from, until) always returns `from`, which removes the jitter. */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(from: Long, until: Long): Long = from
    }

    @Test
    fun `attempts 0 to 8 back off 1s doubling to the 30s cap`() {
        val expected = listOf(1_000L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        val actual = (0..8).map { ReconnectingNmeaAdapter.calculateBackoffDelay(it, noJitter) }
        assertEquals(expected, actual)
    }

    @Test
    fun `jitter stays under 500 ms and the delay is never negative`() {
        val r = Random(42)
        for (attempt in 0..8) {
            repeat(50) {
                val d = ReconnectingNmeaAdapter.calculateBackoffDelay(attempt, r)
                val base = ReconnectingNmeaAdapter.calculateBackoffDelay(attempt, noJitter)
                assertTrue("attempt $attempt: $d", d >= base && d < base + 500)
            }
        }
    }
}
