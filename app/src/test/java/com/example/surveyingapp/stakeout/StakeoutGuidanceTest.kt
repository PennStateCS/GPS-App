package com.example.surveyingapp.stakeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StakeoutGuidanceTest {

    // ── status ──────────────────────────────────────────────────────────────────

    @Test
    fun `no target gives NO_TARGET`() {
        assertEquals(
            StakeoutStatus.NO_TARGET,
            StakeoutGuidance.status(hasTarget = false, hasPosition = true, distanceMeters = 1.0, toleranceMeters = 0.1)
        )
    }

    @Test
    fun `target but no position gives WAITING_FOR_POSITION`() {
        assertEquals(
            StakeoutStatus.WAITING_FOR_POSITION,
            StakeoutGuidance.status(hasTarget = true, hasPosition = false, distanceMeters = null, toleranceMeters = 0.1)
        )
        // hasPosition true but distance null is still "waiting".
        assertEquals(
            StakeoutStatus.WAITING_FOR_POSITION,
            StakeoutGuidance.status(hasTarget = true, hasPosition = true, distanceMeters = null, toleranceMeters = 0.1)
        )
    }

    @Test
    fun `distance beyond tolerance is NAVIGATING`() {
        assertEquals(
            StakeoutStatus.NAVIGATING,
            StakeoutGuidance.status(true, true, distanceMeters = 1.42, toleranceMeters = 0.10)
        )
    }

    @Test
    fun `within tolerance and at-target thresholds`() {
        // Between half-tolerance and tolerance → WITHIN_TOLERANCE.
        assertEquals(
            StakeoutStatus.WITHIN_TOLERANCE,
            StakeoutGuidance.status(true, true, distanceMeters = 0.08, toleranceMeters = 0.10)
        )
        // At/under half the tolerance → AT_TARGET.
        assertEquals(
            StakeoutStatus.AT_TARGET,
            StakeoutGuidance.status(true, true, distanceMeters = 0.04, toleranceMeters = 0.10)
        )
        // Exactly at tolerance is still WITHIN_TOLERANCE (inclusive).
        assertEquals(
            StakeoutStatus.WITHIN_TOLERANCE,
            StakeoutGuidance.status(true, true, distanceMeters = 0.10, toleranceMeters = 0.10)
        )
    }

    @Test
    fun `non-positive tolerance does not crash and still distinguishes`() {
        assertEquals(
            StakeoutStatus.NAVIGATING,
            StakeoutGuidance.status(true, true, distanceMeters = 0.05, toleranceMeters = 0.0)
        )
    }

    // ── low accuracy ────────────────────────────────────────────────────────────

    @Test
    fun `low accuracy detection`() {
        assertFalse(StakeoutGuidance.isLowAccuracy(null, 0.30))
        assertFalse(StakeoutGuidance.isLowAccuracy(0.03, 0.30))
        assertFalse(StakeoutGuidance.isLowAccuracy(0.30, 0.30))
        assertTrue(StakeoutGuidance.isLowAccuracy(0.45, 0.30))
    }

    // ── angle normalisation ───────────────────────────────────────────────────────

    @Test
    fun `normalize360 wraps both directions`() {
        assertEquals(10.0, StakeoutGuidance.normalize360(370.0), 1e-9)
        assertEquals(350.0, StakeoutGuidance.normalize360(-10.0), 1e-9)
        assertEquals(0.0, StakeoutGuidance.normalize360(360.0), 1e-9)
    }

    @Test
    fun `normalize180 centers around zero`() {
        assertEquals(-170.0, StakeoutGuidance.normalize180(190.0), 1e-9)
        assertEquals(180.0, StakeoutGuidance.normalize180(180.0), 1e-9)
        assertEquals(-90.0, StakeoutGuidance.normalize180(270.0), 1e-9)
    }

    // ── relative bearing ──────────────────────────────────────────────────────────

    @Test
    fun `relative bearing is null without heading`() {
        assertNull(StakeoutGuidance.relativeBearing(42.0, null))
    }

    @Test
    fun `relative bearing subtracts heading and normalises`() {
        assertEquals(30.0, StakeoutGuidance.relativeBearing(90.0, 60.0)!!, 1e-9)
        // target behind to the left: 10 - 350 = -340 → 20
        assertEquals(20.0, StakeoutGuidance.relativeBearing(10.0, 350.0)!!, 1e-9)
    }

    // ── heading resolution (Task 5 fallback chain) ────────────────────────────────

    @Test
    fun `compass used when preferred and available`() {
        val (src, hdg) = StakeoutGuidance.resolveHeading(
            preferCompass = true, compassHeadingDeg = 123.0, courseOverGroundDeg = 200.0, speedMps = 2.0
        )
        assertEquals(HeadingSource.COMPASS, src)
        assertEquals(123.0, hdg!!, 1e-9)
    }

    @Test
    fun `course used when moving and compass unavailable`() {
        val (src, hdg) = StakeoutGuidance.resolveHeading(
            preferCompass = true, compassHeadingDeg = null, courseOverGroundDeg = 200.0, speedMps = 2.0
        )
        assertEquals(HeadingSource.COURSE_OVER_GROUND, src)
        assertEquals(200.0, hdg!!, 1e-9)
    }

    @Test
    fun `north-up fallback when not moving and no compass`() {
        val (src, hdg) = StakeoutGuidance.resolveHeading(
            preferCompass = true, compassHeadingDeg = null, courseOverGroundDeg = 200.0, speedMps = 0.1
        )
        assertEquals(HeadingSource.NORTH_UP, src)
        assertNull(hdg)
    }

    @Test
    fun `compass ignored when preference is off, falls through to course`() {
        val (src, _) = StakeoutGuidance.resolveHeading(
            preferCompass = false, compassHeadingDeg = 123.0, courseOverGroundDeg = 200.0, speedMps = 2.0
        )
        assertEquals(HeadingSource.COURSE_OVER_GROUND, src)
    }

    // ── compass labels ────────────────────────────────────────────────────────────

    @Test
    fun `compass point and direction word`() {
        assertEquals("N", StakeoutGuidance.compassPoint(0.0))
        assertEquals("NE", StakeoutGuidance.compassPoint(42.0))
        assertEquals("E", StakeoutGuidance.compassPoint(90.0))
        assertEquals("N", StakeoutGuidance.compassPoint(359.0))
        assertEquals("northeast", StakeoutGuidance.directionWord(42.0))
        assertEquals("west", StakeoutGuidance.directionWord(270.0))
    }
}
