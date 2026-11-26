package app.surrealar.gnss.status

import app.surrealar.gnss.status.GnssStatusInterpreter.Quality
import app.surrealar.gnss.status.GnssStatusInterpreter.Reference
import app.surrealar.gnss.status.GnssStatusInterpreter.Warning
import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.settings.model.CompensatedOutputSetting
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared interpretation used by the toolbar, the GNSS Status report and the Home card. */
class GnssStatusInterpreterTest {

    private fun interp(
        rtk: RtkStatus = RtkStatus.FIX, h: Double? = 0.02, est: Boolean = false,
        corr: CorrectionHealth = CorrectionHealth.OK, ref: Reference? = null, vInvalid: Boolean = false,
    ) = GnssStatusInterpreter.interpret(GnssStatusInterpreter.Inputs(rtk, h, est, corr, ref, vInvalid))

    @Test fun `RTK fix is high accuracy only with a reported hAcc within 5 cm`() {
        assertEquals(Quality.HIGH_ACCURACY, interp(h = 0.05).quality)
        assertTrue(interp(h = 0.02).isGood)
        assertEquals(Quality.ACCURACY_BELOW_TARGET, interp(h = 0.051).quality)
        assertEquals(Quality.FIX_UNVERIFIED, interp(h = null).quality)
        assertEquals("an estimate never verifies RTK Fix", Quality.FIX_UNVERIFIED, interp(h = 0.01, est = true).quality)
        assertFalse(interp(h = 0.01, est = true).isGood)
    }

    @Test fun `float, single and low accuracy`() {
        assertEquals(Quality.FLOAT, interp(RtkStatus.FLOAT, 0.01).quality)
        assertEquals(Quality.LOW_ACCURACY, interp(RtkStatus.SINGLE, 1.5).quality)
        assertEquals(Quality.POSITION_AVAILABLE, interp(RtkStatus.SINGLE, 0.9).quality)
        assertEquals(Quality.POSITION_AVAILABLE, interp(RtkStatus.DGPS, null).quality)
    }

    @Test fun `any warning outranks a good quality and is the headline`() {
        val i = interp(corr = CorrectionHealth.STALE, vInvalid = true)
        assertEquals(Quality.HIGH_ACCURACY, i.quality)
        assertFalse("never green with a warning", i.isGood)
        assertEquals("Corrections stale", i.headline)
        assertEquals(listOf(Warning.CORRECTIONS_STALE, Warning.VERTICAL_INVALID), i.warnings)
    }

    @Test fun `warnings follow the precedence order`() {
        val i = interp(corr = CorrectionHealth.AGING, ref = Reference.UNCONFIRMED, vInvalid = true)
        assertEquals(listOf(Warning.REFERENCE_UNCONFIRMED, Warning.CORRECTIONS_AGING, Warning.VERTICAL_INVALID), i.warnings)
        assertEquals(listOf(Warning.CORRECTIONS_UNAVAILABLE, Warning.REFERENCE_MAY_BE_COMPENSATED),
            interp(corr = CorrectionHealth.UNAVAILABLE, ref = Reference.MAY_BE_COMPENSATED).warnings)
        assertEquals(GnssStatusInterpreter.Severity.WARNING, Warning.REFERENCE_MAY_BE_COMPENSATED.severity)
        assertEquals(GnssStatusInterpreter.Severity.CAUTION, Warning.CORRECTIONS_AGING.severity)
        assertTrue(interp(ref = Reference.POLE_TIP).warnings.isEmpty())
        assertTrue(interp(ref = Reference.ANTENNA).warnings.isEmpty())
    }

    @Test fun `raw staleness is the Home rule`() {
        assertEquals(CorrectionHealth.OK, GnssStatusInterpreter.rawStale(15.0))
        assertEquals(CorrectionHealth.STALE, GnssStatusInterpreter.rawStale(15.1))
        assertEquals(CorrectionHealth.OK, GnssStatusInterpreter.rawStale(null))
    }

    @Test fun `position reference from tilt state`() {
        val r = GnssStatusInterpreter::referenceOf
        assertNull(r(false, EpochReference.POLE_TIP, CompensatedOutputSetting.UNKNOWN))
        assertEquals(Reference.POLE_TIP, r(true, EpochReference.POLE_TIP, CompensatedOutputSetting.ENABLED))
        assertEquals(Reference.UNCONFIRMED, r(true, EpochReference.ANTENNA_UNCERTAIN, CompensatedOutputSetting.DISABLED))
        assertEquals(Reference.UNCONFIRMED, r(true, null, CompensatedOutputSetting.UNKNOWN))
        assertNull(r(true, null, CompensatedOutputSetting.DISABLED))
        assertEquals(Reference.MAY_BE_COMPENSATED, r(true, EpochReference.MAY_BE_COMPENSATED, CompensatedOutputSetting.UNKNOWN))
    }

    @Test fun `short toolbar texts are shorter than the full texts`() {
        Warning.entries.forEach { assertTrue(it.name, it.shortText.length < it.text.length) }
    }
}
