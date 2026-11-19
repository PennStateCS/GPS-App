package app.surrealar.gnss.capture

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The live "OK to capture" eligibility gate used by the capture screen. */
class FixAcceptanceSettingsTest {

    private fun fix(sats: Int = 12, pdop: Double? = 1.5, rtk: RtkStatus = RtkStatus.FIX) = Fix(
        provider = Provider.RS2_EXTERNAL, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 100.0, altMslM = null, geoidSeparationM = null,
        hDop = 1.0, vDop = 1.0, pDop = pdop, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk, satsUsed = sats, satsVisible = 20, diffAgeS = 1.0, speedMps = null, courseDeg = null
    )

    private val defaults = FixAcceptanceSettings()

    @Test fun goodRtkFix_isAccepted() = assertTrue(defaults.accepts(fix()))

    @Test fun floatIsAcceptedByDefault() = assertTrue(defaults.accepts(fix(rtk = RtkStatus.FLOAT)))

    @Test fun lowerQualityModes_areRejectedByDefault() {
        listOf(RtkStatus.DGPS, RtkStatus.SINGLE, RtkStatus.NONE, RtkStatus.INVALID, RtkStatus.DEAD_RECKONING)
            .forEach { assertFalse("$it should be rejected", defaults.accepts(fix(rtk = it))) }
    }

    @Test fun satelliteThreshold_isInclusive() {
        assertTrue(defaults.accepts(fix(sats = defaults.minSats)))
        assertFalse(defaults.accepts(fix(sats = defaults.minSats - 1)))
    }

    @Test fun pdopThreshold_isInclusive() {
        assertTrue(defaults.accepts(fix(pdop = defaults.maxPdop)))
        assertFalse(defaults.accepts(fix(pdop = defaults.maxPdop + 0.01)))
    }

    @Test fun missingPdop_isRejected_notAssumedGood() = assertFalse(defaults.accepts(fix(pdop = null)))

    @Test fun customAllowedSet_isHonored() {
        val fixOnly = FixAcceptanceSettings(allowed = setOf(RtkStatus.FIX))
        assertTrue(fixOnly.accepts(fix(rtk = RtkStatus.FIX)))
        assertFalse(fixOnly.accepts(fix(rtk = RtkStatus.FLOAT)))
    }
}
