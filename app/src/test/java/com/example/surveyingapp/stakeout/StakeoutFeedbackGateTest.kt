package com.example.surveyingapp.stakeout

import org.junit.Assert.assertEquals
import org.junit.Test

class StakeoutFeedbackGateTest {

    @Test
    fun `entering tolerance fires once, not repeatedly`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 1000)
        assertEquals(StakeoutFeedback.ENTERED_TOLERANCE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 0))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 100))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.AT_TARGET, 200))
    }

    @Test
    fun `leaving and re-entering tolerance fires again`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 1000)
        assertEquals(StakeoutFeedback.ENTERED_TOLERANCE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 0))
        // Walk away → navigating (first nav pulse allowed immediately).
        assertEquals(StakeoutFeedback.NAVIGATING_PULSE, gate.onUpdate(StakeoutStatus.NAVIGATING, 100))
        // Come back → fires the enter event again.
        assertEquals(StakeoutFeedback.ENTERED_TOLERANCE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 200))
    }

    @Test
    fun `navigating pulses are throttled to the interval`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 1000)
        assertEquals(StakeoutFeedback.NAVIGATING_PULSE, gate.onUpdate(StakeoutStatus.NAVIGATING, 0))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.NAVIGATING, 500))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.NAVIGATING, 999))
        assertEquals(StakeoutFeedback.NAVIGATING_PULSE, gate.onUpdate(StakeoutStatus.NAVIGATING, 1000))
    }

    @Test
    fun `nav pulses disabled when interval is non-positive`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 0)
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.NAVIGATING, 0))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.NAVIGATING, 100000))
    }

    @Test
    fun `no feedback without target or position`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 1000)
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.NO_TARGET, 0))
        assertEquals(StakeoutFeedback.NONE, gate.onUpdate(StakeoutStatus.WAITING_FOR_POSITION, 100))
    }

    @Test
    fun `reset clears the tolerance latch and throttle`() {
        val gate = StakeoutFeedbackGate(navIntervalMs = 1000)
        assertEquals(StakeoutFeedback.ENTERED_TOLERANCE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 0))
        gate.reset()
        // After reset, being within tolerance is treated as a fresh entry.
        assertEquals(StakeoutFeedback.ENTERED_TOLERANCE, gate.onUpdate(StakeoutStatus.WITHIN_TOLERANCE, 50))
    }
}
