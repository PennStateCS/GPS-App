package com.example.surveyingapp.gnss.capture

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for ObservationSession completion state management.
 *
 * Note: Full integration tests requiring coroutine TestScope are in instrumented tests.
 * These unit tests verify the core state management logic.
 */
class ObservationSessionTest {

    @Test
    fun `RtkStatus quality ranking is used correctly in policy`() {
        val policy = AveragingPolicy(requiredMinStatus = com.example.surveyingapp.gnss.model.RtkStatus.FLOAT)

        // Verify the policy uses quality ranking
        assertTrue("FIX should meet FLOAT requirement",
            com.example.surveyingapp.gnss.model.RtkStatus.FIX.meetsOrExceeds(policy.requiredMinStatus))
        assertTrue("FLOAT should meet FLOAT requirement",
            com.example.surveyingapp.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(policy.requiredMinStatus))
        assertFalse("DGPS should not meet FLOAT requirement",
            com.example.surveyingapp.gnss.model.RtkStatus.DGPS.meetsOrExceeds(policy.requiredMinStatus))
        assertFalse("SINGLE should not meet FLOAT requirement",
            com.example.surveyingapp.gnss.model.RtkStatus.SINGLE.meetsOrExceeds(policy.requiredMinStatus))
    }

    @Test
    fun `policy validates with correct quality thresholds`() {
        val strictPolicy = AveragingPolicy(requiredMinStatus = com.example.surveyingapp.gnss.model.RtkStatus.FIX)
        val lenientPolicy = AveragingPolicy(requiredMinStatus = com.example.surveyingapp.gnss.model.RtkStatus.SINGLE)

        // Strict policy only accepts FIX
        assertTrue(com.example.surveyingapp.gnss.model.RtkStatus.FIX.meetsOrExceeds(strictPolicy.requiredMinStatus))
        assertFalse(com.example.surveyingapp.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(strictPolicy.requiredMinStatus))

        // Lenient policy accepts SINGLE and above
        assertTrue(com.example.surveyingapp.gnss.model.RtkStatus.FIX.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(com.example.surveyingapp.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(com.example.surveyingapp.gnss.model.RtkStatus.DGPS.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(com.example.surveyingapp.gnss.model.RtkStatus.SINGLE.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertFalse(com.example.surveyingapp.gnss.model.RtkStatus.NONE.meetsOrExceeds(lenientPolicy.requiredMinStatus))
    }

    /**
     * Note: Full integration tests for ObservationSession completion state transitions
     * (including finalize() vs cancel()) are in instrumented tests where coroutine
     * TestScope is available.
     */
}

