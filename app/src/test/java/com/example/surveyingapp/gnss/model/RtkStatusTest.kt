package com.example.surveyingapp.gnss.model

import org.junit.Test
import org.junit.Assert.*

class RtkStatusTest {

    @Test
    fun `qualityRank returns correct rankings`() {
        // Highest quality
        assertEquals(4, RtkStatus.FIX.qualityRank(), "FIX should have highest rank")

        // High quality
        assertEquals(3, RtkStatus.FLOAT.qualityRank(), "FLOAT should rank 3")

        // Medium quality
        assertEquals(2, RtkStatus.DGPS.qualityRank(), "DGPS should rank 2")

        // Low quality (tied)
        assertEquals(1, RtkStatus.SINGLE.qualityRank(), "SINGLE should rank 1")
        assertEquals(1, RtkStatus.DEAD_RECKONING.qualityRank(), "DEAD_RECKONING should rank 1")

        // No quality
        assertEquals(0, RtkStatus.NONE.qualityRank(), "NONE should rank 0")
        assertEquals(0, RtkStatus.INVALID.qualityRank(), "INVALID should rank 0")
    }

    @Test
    fun `meetsOrExceeds correctly compares quality levels`() {
        // FIX meets or exceeds everything
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.FIX))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.FLOAT))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.SINGLE))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.NONE))

        // FLOAT meets FLOAT and below
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.FLOAT))
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.SINGLE))
        assertFalse(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.FIX))

        // DGPS meets DGPS and below
        assertTrue(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.SINGLE))
        assertFalse(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.FLOAT))
        assertFalse(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.FIX))

        // SINGLE and DEAD_RECKONING are equivalent
        assertTrue(RtkStatus.SINGLE.meetsOrExceeds(RtkStatus.DEAD_RECKONING))
        assertTrue(RtkStatus.DEAD_RECKONING.meetsOrExceeds(RtkStatus.SINGLE))

        // NONE and INVALID don't meet anything except themselves
        assertTrue(RtkStatus.NONE.meetsOrExceeds(RtkStatus.NONE))
        assertTrue(RtkStatus.NONE.meetsOrExceeds(RtkStatus.INVALID))
        assertFalse(RtkStatus.NONE.meetsOrExceeds(RtkStatus.SINGLE))
    }

    @Test
    fun `meetsOrExceeds replaces unsafe ordinal comparison`() {
        // Demonstrate ordinal comparison would be wrong:
        // SINGLE.ordinal (4) > DGPS.ordinal (1) but quality is opposite
        assertTrue(RtkStatus.SINGLE.ordinal > RtkStatus.DGPS.ordinal,
            "SINGLE has higher ordinal than DGPS (WRONG for quality)")

        // But quality-based comparison is correct:
        assertFalse(RtkStatus.SINGLE.meetsOrExceeds(RtkStatus.DGPS),
            "SINGLE does not meet DGPS quality (CORRECT)")

        assertTrue(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.SINGLE),
            "DGPS exceeds SINGLE quality (CORRECT)")
    }

    @Test
    fun `capture policy use case - require FLOAT or better`() {
        val requiredMin = RtkStatus.FLOAT

        // These pass
        assertTrue(RtkStatus.FIX.meetsOrExceeds(requiredMin), "FIX should pass FLOAT requirement")
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(requiredMin), "FLOAT should pass FLOAT requirement")

        // These fail
        assertFalse(RtkStatus.DGPS.meetsOrExceeds(requiredMin), "DGPS should fail FLOAT requirement")
        assertFalse(RtkStatus.SINGLE.meetsOrExceeds(requiredMin), "SINGLE should fail FLOAT requirement")
        assertFalse(RtkStatus.NONE.meetsOrExceeds(requiredMin), "NONE should fail FLOAT requirement")
    }
}

