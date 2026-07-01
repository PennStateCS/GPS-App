package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.GeoAnchorGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure geospatial-anchor placement gate. This is the guard that fixes the "all models
 * loaded where the user was standing" bug: anchors must not be committed until ARCore's own Earth
 * localization is good enough (or a timeout fallback fires), and must re-anchor when it improves.
 */
class GeoAnchorGateTest {

    // ── decide() ─────────────────────────────────────────────────────────────────────────────

    @Test fun `waits when horizontal accuracy is poor`() {
        assertEquals(Decision.WAIT, GeoAnchorGate.decide(horizontalAccuracyM = 12.0, yawAccuracyDeg = 5.0, trackingElapsedMs = 1_000))
    }

    @Test fun `waits when yaw accuracy is poor even if horizontal is good`() {
        assertEquals(Decision.WAIT, GeoAnchorGate.decide(horizontalAccuracyM = 2.0, yawAccuracyDeg = 30.0, trackingElapsedMs = 1_000))
    }

    @Test fun `places when both horizontal and yaw are within thresholds`() {
        assertEquals(Decision.PLACE_LOCALIZED, GeoAnchorGate.decide(horizontalAccuracyM = 5.0, yawAccuracyDeg = 15.0, trackingElapsedMs = 1_000))
        assertEquals(Decision.PLACE_LOCALIZED, GeoAnchorGate.decide(horizontalAccuracyM = 1.0, yawAccuracyDeg = 3.0, trackingElapsedMs = 0))
    }

    @Test fun `poor accuracy just over the threshold still waits`() {
        assertEquals(Decision.WAIT, GeoAnchorGate.decide(horizontalAccuracyM = 5.01, yawAccuracyDeg = 10.0, trackingElapsedMs = 1_000))
        assertEquals(Decision.WAIT, GeoAnchorGate.decide(horizontalAccuracyM = 4.0, yawAccuracyDeg = 15.01, trackingElapsedMs = 1_000))
    }

    @Test fun `places via timeout fallback when localization never gets good enough`() {
        // No-VPS / rural: accuracy stays poor, but after the timeout we place anyway so models appear.
        assertEquals(Decision.PLACE_TIMEOUT, GeoAnchorGate.decide(horizontalAccuracyM = 18.0, yawAccuracyDeg = 40.0, trackingElapsedMs = GeoAnchorGate.LOCALIZE_TIMEOUT_MS))
    }

    @Test fun `non-finite accuracy waits (until timeout)`() {
        assertEquals(Decision.WAIT, GeoAnchorGate.decide(Double.NaN, Double.NaN, 1_000))
        assertEquals(Decision.PLACE_TIMEOUT, GeoAnchorGate.decide(Double.NaN, Double.NaN, GeoAnchorGate.LOCALIZE_TIMEOUT_MS))
    }

    // ── shouldReanchorOnImprovement() ────────────────────────────────────────────────────────

    @Test fun `re-anchors when accuracy halves and improves by the minimum metres`() {
        // Placed at 12 m (timeout), later reaches 4 m → re-anchor to correct placement.
        assertTrue(GeoAnchorGate.shouldReanchorOnImprovement(accuracyAtCreationM = 12.0, currentAccuracyM = 4.0, cooldownElapsed = true))
    }

    @Test fun `does not re-anchor during cooldown`() {
        assertFalse(GeoAnchorGate.shouldReanchorOnImprovement(12.0, 4.0, cooldownElapsed = false))
    }

    @Test fun `does not re-anchor for a tiny improvement`() {
        // 4 m → 3.5 m: not half, and only 0.5 m better → no churn.
        assertFalse(GeoAnchorGate.shouldReanchorOnImprovement(4.0, 3.5, cooldownElapsed = true))
    }

    @Test fun `does not re-anchor when accuracy is not meaningfully better`() {
        // Halved but only 1.5 m improvement (< 2 m min) → skip.
        assertFalse(GeoAnchorGate.shouldReanchorOnImprovement(3.0, 1.5, cooldownElapsed = true))
    }

    @Test fun `does not re-anchor when accuracy got worse`() {
        assertFalse(GeoAnchorGate.shouldReanchorOnImprovement(4.0, 9.0, cooldownElapsed = true))
    }
}
