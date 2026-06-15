package com.example.surveyingapp.gnss.model

import org.junit.Test
import org.junit.Assert.*

class RtkStatusTest {

    @Test
    fun `qualityRank returns correct rankings`() {
        assertEquals("FIX should have highest rank", 4, RtkStatus.FIX.qualityRank())
        assertEquals("FLOAT should rank 3", 3, RtkStatus.FLOAT.qualityRank())
        assertEquals("DGPS should rank 2", 2, RtkStatus.DGPS.qualityRank())
        assertEquals("SINGLE should rank 1", 1, RtkStatus.SINGLE.qualityRank())
        assertEquals("DEAD_RECKONING should rank 1", 1, RtkStatus.DEAD_RECKONING.qualityRank())
        assertEquals("NONE should rank 0", 0, RtkStatus.NONE.qualityRank())
        assertEquals("INVALID should rank 0", 0, RtkStatus.INVALID.qualityRank())
    }

    @Test
    fun `meetsOrExceeds correctly compares quality levels`() {
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.FIX))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.FLOAT))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.SINGLE))
        assertTrue(RtkStatus.FIX.meetsOrExceeds(RtkStatus.NONE))
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.FLOAT))
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.SINGLE))
        assertFalse(RtkStatus.FLOAT.meetsOrExceeds(RtkStatus.FIX))
        assertTrue(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.SINGLE))
        assertFalse(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.FLOAT))
        assertFalse(RtkStatus.DGPS.meetsOrExceeds(RtkStatus.FIX))
        assertTrue(RtkStatus.SINGLE.meetsOrExceeds(RtkStatus.DEAD_RECKONING))
        assertTrue(RtkStatus.DEAD_RECKONING.meetsOrExceeds(RtkStatus.SINGLE))
        assertTrue(RtkStatus.NONE.meetsOrExceeds(RtkStatus.NONE))
        assertTrue(RtkStatus.NONE.meetsOrExceeds(RtkStatus.INVALID))
        assertFalse(RtkStatus.NONE.meetsOrExceeds(RtkStatus.SINGLE))
    }

    @Test
    fun `meetsOrExceeds replaces unsafe ordinal comparison`() {
        assertTrue("SINGLE has higher ordinal than DGPS (WRONG for quality)",
            RtkStatus.SINGLE.ordinal > RtkStatus.DGPS.ordinal)
        assertFalse("SINGLE does not meet DGPS quality (CORRECT)",
            RtkStatus.SINGLE.meetsOrExceeds(RtkStatus.DGPS))
        assertTrue("DGPS exceeds SINGLE quality (CORRECT)",
            RtkStatus.DGPS.meetsOrExceeds(RtkStatus.SINGLE))
    }

    @Test
    fun `capture policy use case - require FLOAT or better`() {
        val requiredMin = RtkStatus.FLOAT
        assertTrue("FIX should pass FLOAT requirement", RtkStatus.FIX.meetsOrExceeds(requiredMin))
        assertTrue("FLOAT should pass FLOAT requirement", RtkStatus.FLOAT.meetsOrExceeds(requiredMin))
        assertFalse("DGPS should fail FLOAT requirement", RtkStatus.DGPS.meetsOrExceeds(requiredMin))
        assertFalse("SINGLE should fail FLOAT requirement", RtkStatus.SINGLE.meetsOrExceeds(requiredMin))
        assertFalse("NONE should fail FLOAT requirement", RtkStatus.NONE.meetsOrExceeds(requiredMin))
    }
}
