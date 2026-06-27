package app.surrealar.gnss.settings

import app.surrealar.gnss.capture.GnssCaptureSettings
import app.surrealar.gnss.capture.toAveragingPolicy
import app.surrealar.gnss.model.RtkStatus
import org.junit.Assert.*
import org.junit.Test

class GnssCaptureSettingsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default settings have requiredMinStatus FIX`() {
        assertEquals(RtkStatus.FIX, GnssCaptureSettings().requiredMinStatus)
    }

    @Test
    fun `default settings convert to AveragingPolicy with FIX status`() {
        val policy = GnssCaptureSettings().toAveragingPolicy()
        assertEquals(RtkStatus.FIX, policy.requiredMinStatus)
    }

    @Test
    fun `default settings convert to AveragingPolicy with correct timing`() {
        val policy = GnssCaptureSettings().toAveragingPolicy()
        assertEquals(60,  policy.minDurationSec)
        assertEquals(120, policy.maxDurationSec)
        assertEquals(150, policy.minSamples)
        assertEquals(3,   policy.maxFixAgeSec)
        assertEquals(10,  policy.maxDiffAgeSec)
    }

    // ── RTK status mapping ────────────────────────────────────────────────────

    @Test
    fun `FLOAT setting produces FLOAT policy`() {
        val policy = GnssCaptureSettings(requiredMinStatus = RtkStatus.FLOAT).toAveragingPolicy()
        assertEquals(RtkStatus.FLOAT, policy.requiredMinStatus)
    }

    @Test
    fun `FIX policy rejects FLOAT fixes`() {
        val policy = GnssCaptureSettings(requiredMinStatus = RtkStatus.FIX).toAveragingPolicy()
        // RtkStatus ordinal: FIX > FLOAT > DGPS > SINGLE; the policy stores the minimum enum,
        // so only fixes at or above FIX are accepted. Verify the policy carries FIX as the minimum.
        assertEquals(RtkStatus.FIX, policy.requiredMinStatus)
        // FLOAT is below FIX in quality so a FIX-only policy should NOT accept it.
        assertNotEquals(RtkStatus.FLOAT, policy.requiredMinStatus)
    }

    @Test
    fun `FIX policy accepts FIX fixes`() {
        val policy = GnssCaptureSettings(requiredMinStatus = RtkStatus.FIX).toAveragingPolicy()
        assertEquals(RtkStatus.FIX, policy.requiredMinStatus)
    }

    // ── Validation clamping in toAveragingPolicy ──────────────────────────────

    @Test
    fun `minDurationSec below 1 is clamped to 1`() {
        val policy = GnssCaptureSettings(minDurationSec = 0).toAveragingPolicy()
        assertEquals(1, policy.minDurationSec)
    }

    @Test
    fun `minDurationSec above 600 is clamped to 600`() {
        val policy = GnssCaptureSettings(minDurationSec = 999).toAveragingPolicy()
        assertEquals(600, policy.minDurationSec)
    }

    @Test
    fun `maxDurationSec above 600 is clamped to 600`() {
        val policy = GnssCaptureSettings(maxDurationSec = 999).toAveragingPolicy()
        assertEquals(600, policy.maxDurationSec)
    }

    @Test
    fun `maxDurationSec less than minDurationSec is clamped up to minDurationSec`() {
        val policy = GnssCaptureSettings(minDurationSec = 90, maxDurationSec = 60).toAveragingPolicy()
        // minDur stays 90; maxDur must be at least 90
        assertEquals(90, policy.minDurationSec)
        assertEquals(90, policy.maxDurationSec)
    }

    @Test
    fun `maxDurationSec below 1 is clamped to minDurationSec floor`() {
        val policy = GnssCaptureSettings(minDurationSec = 30, maxDurationSec = 0).toAveragingPolicy()
        assertEquals(30, policy.minDurationSec)
        assertEquals(30, policy.maxDurationSec)
    }

    @Test
    fun `minSamples of 0 is clamped to 1`() {
        val policy = GnssCaptureSettings(minSamples = 0).toAveragingPolicy()
        assertEquals(1, policy.minSamples)
    }

    @Test
    fun `minSamples negative is clamped to 1`() {
        val policy = GnssCaptureSettings(minSamples = -5).toAveragingPolicy()
        assertEquals(1, policy.minSamples)
    }

    @Test
    fun `maxFixAgeSec of 0 is clamped to 1`() {
        val policy = GnssCaptureSettings(maxFixAgeSec = 0).toAveragingPolicy()
        assertEquals(1, policy.maxFixAgeSec)
    }

    @Test
    fun `maxDiffAgeSec of 0 is clamped to 1`() {
        val policy = GnssCaptureSettings(maxDiffAgeSec = 0).toAveragingPolicy()
        assertEquals(1, policy.maxDiffAgeSec)
    }

    // ── Valid configurations pass through unchanged ───────────────────────────

    @Test
    fun `valid settings are preserved exactly`() {
        val settings = GnssCaptureSettings(
            requiredMinStatus = RtkStatus.DGPS,
            minDurationSec    = 30,
            maxDurationSec    = 180,
            minSamples        = 100,
            maxFixAgeSec      = 5,
            maxDiffAgeSec     = 15
        )
        val policy = settings.toAveragingPolicy()
        assertEquals(RtkStatus.DGPS, policy.requiredMinStatus)
        assertEquals(30,  policy.minDurationSec)
        assertEquals(180, policy.maxDurationSec)
        assertEquals(100, policy.minSamples)
        assertEquals(5,   policy.maxFixAgeSec)
        assertEquals(15,  policy.maxDiffAgeSec)
    }

    @Test
    fun `equal min and max duration is valid`() {
        val policy = GnssCaptureSettings(minDurationSec = 60, maxDurationSec = 60).toAveragingPolicy()
        assertEquals(60, policy.minDurationSec)
        assertEquals(60, policy.maxDurationSec)
    }

    @Test
    fun `SINGLE status setting is preserved`() {
        val policy = GnssCaptureSettings(requiredMinStatus = RtkStatus.SINGLE).toAveragingPolicy()
        assertEquals(RtkStatus.SINGLE, policy.requiredMinStatus)
    }
}
