package io.github.pennstatecs.geokit.gnss.capture

import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CapturePresetTest {

    @Test fun `custom uses the saved fields for external and the fixed internal policy`() {
        val s = GnssCaptureSettings(minDurationSec = 45, maxDurationSec = 90, minSamples = 40,
            requiredMinStatus = RtkStatus.FLOAT, preset = CapturePreset.CUSTOM, precisionTargetM = 0.025)
        val ext = s.resolvePolicy(internal = false)
        assertEquals(45, ext.minDurationSec)
        assertEquals(RtkStatus.FLOAT, ext.requiredMinStatus)
        assertEquals(0.025, ext.horizontalPrecisionTargetM!!, 0.0)
        assertEquals(AveragingPolicy.INTERNAL, s.resolvePolicy(internal = true))
    }

    @Test fun `presets ignore the custom fields and keep separate internal rules`() {
        val s = GnssCaptureSettings(minDurationSec = 1, preset = CapturePreset.CONTROL)
        val ext = s.resolvePolicy(internal = false)
        val int = s.resolvePolicy(internal = true)
        assertEquals(180, ext.minDurationSec)
        assertEquals(RtkStatus.FIX, ext.requiredMinStatus)
        assertNotNull(ext.horizontalPrecisionTargetM)
        assertEquals(RtkStatus.SINGLE, int.requiredMinStatus)
        assertNull("device GNSS presets use time + fixes only", int.horizontalPrecisionTargetM)
    }

    @Test fun `every non-custom preset defines both policies, and unknown keys fall back to custom`() {
        CapturePreset.entries.filter { it != CapturePreset.CUSTOM }.forEach {
            assertNotNull(it.name, it.external); assertNotNull(it.name, it.internal)
        }
        assertEquals(CapturePreset.CUSTOM, CapturePreset.fromPrefKey("garbage"))
        assertEquals(CapturePreset.STANDARD, CapturePreset.fromPrefKey("standard"))
    }

    @Test fun `non-positive custom precision target means off`() {
        assertNull(GnssCaptureSettings(precisionTargetM = 0.0).toAveragingPolicy().horizontalPrecisionTargetM)
    }
}
