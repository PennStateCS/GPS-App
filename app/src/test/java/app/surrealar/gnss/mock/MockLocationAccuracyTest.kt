package app.surrealar.gnss.mock

import androidx.test.core.app.ApplicationProvider
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.receiver.AccuracyClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.math.sqrt

/**
 * The accuracy sent to ARCore with a mock location: the receiver's own when it reports a usable one
 * ("reported"); else an ESTIMATE ("estimated") = DOP × UERE for the fix's accuracy class, never below the
 * conservative per-status floor (RTK fixed 0.10 m, float 0.5 m, DGPS 1.5 m, else 5 m).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationAccuracyTest {

    private val publisher = AndroidMockLocationPublisher(
        context = ApplicationProvider.getApplicationContext(),
        fixSwitchboard = Mockito.mock(FixSwitchboard::class.java),
        settingsRepo = Mockito.mock(SettingsRepository::class.java),
    )
    private val r68 = 1.515 / sqrt(2.0)

    private fun fix(
        rtk: RtkStatus, hDop: Double?, hAccM: Double? = null, cls: AccuracyClass? = null,
        vAccM: Double? = null, altM: Double? = 300.0,
    ) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.now(), timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = altM, altMslM = null, geoidSeparationM = null,
        hDop = hDop, vDop = null, pDop = null, hAccM = hAccM, vAccM = vAccM,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null, accuracyClass = cls,
    )

    private fun source(loc: android.location.Location) =
        loc.extras?.getString(AndroidMockLocationPublisher.EXTRA_ACCURACY_SOURCE)

    @Test fun `FLOAT without GST - an optimistic DOP x UERE estimate is raised to the 0_5 m floor`() {
        // 1.2 × 0.25 × r68 ≈ 0.21 m would claim better than float can be trusted for without a receiver value.
        val loc = publisher.fixToLocation(fix(RtkStatus.FLOAT, hDop = 1.2))
        assertEquals(0.5f, loc.accuracy, 0f)
        assertEquals("estimated", source(loc))
    }

    @Test fun `FLOAT with a poor HDOP keeps the larger DOP x UERE estimate`() {
        val loc = publisher.fixToLocation(fix(RtkStatus.FLOAT, hDop = 4.0))
        assertEquals((4.0 * 0.25 * r68).toFloat().coerceAtLeast(0.5f), loc.accuracy, 1e-6f)
        assertEquals("estimated", source(loc))
    }

    @Test fun `accuracy class decides the UERE (generic receiver SINGLE)`() {
        val f = fix(RtkStatus.SINGLE, hDop = 2.0, cls = AccuracyClass.SINGLE_FREQUENCY)
        assertEquals((2.0 * 5.0 * r68).toFloat(), publisher.fixToLocation(f).accuracy, 1e-5f)
    }

    @Test fun `RTK FIXED without receiver accuracy is never presented as cm-level`() {
        val withHdop = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 0.8))       // DOP × UERE ≈ 1 cm
        assertEquals(0.10f, withHdop.accuracy, 0f)
        assertEquals("estimated", source(withHdop))
        val noHdop = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = null))
        assertEquals(0.10f, noHdop.accuracy, 0f)
        assertEquals("estimated", source(noHdop))
    }

    @Test fun `RTK FIXED with receiver accuracy publishes it as reported`() {
        val loc = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 0.8, hAccM = 0.012))
        assertEquals(0.012f, loc.accuracy, 1e-6f)
        assertEquals("reported", source(loc))
    }

    @Test fun `invalid receiver horizontal accuracies are estimates, and Android always gets a valid value`() {
        for (bad in listOf(Double.NaN, 0.0, -0.5, Double.POSITIVE_INFINITY, 1e300)) {
            val loc = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = null, hAccM = bad))
            assertTrue("hAcc=$bad must give a finite > 0 accuracy", loc.hasAccuracy() && loc.accuracy.isFinite() && loc.accuracy > 0f)
            assertEquals("hAcc=$bad", 0.10f, loc.accuracy, 0f)
            assertEquals("hAcc=$bad", "estimated", source(loc))
        }
    }

    @Test fun `invalid vertical accuracy - no vertical accuracy, and the 999 m marker withholds the altitude`() {
        val marker = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.02, vAccM = 999.0))
        assertFalse("999 m marker: height unusable", marker.hasAltitude())
        assertFalse(marker.hasVerticalAccuracy())
        assertEquals(41.0, marker.latitude, 0.0)                   // horizontal unchanged
        assertEquals("reported", source(marker))
        for (bad in listOf(Double.NaN, 0.0, -1.0)) {
            val loc = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.02, vAccM = bad))
            assertFalse("vAcc=$bad never published", loc.hasVerticalAccuracy())
        }
        val valid = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.02, vAccM = 0.03))
        assertTrue(valid.hasAltitude())
        assertEquals(0.03f, valid.verticalAccuracyMeters, 1e-6f)
        val missing = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.02, vAccM = null))
        assertTrue("no vertical accuracy is not an invalid one", missing.hasAltitude())
        assertFalse("never an estimated vertical accuracy", missing.hasVerticalAccuracy())
    }

    @Test fun `pole-tip altitude omission still omits vertical accuracy`() {
        val loc = publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.02, vAccM = 0.03), omitAltitude = true)
        assertFalse(loc.hasAltitude())
        assertFalse(loc.hasVerticalAccuracy())
        assertEquals("reported", source(loc))
    }

    @Test fun `receiver transitions - provenance follows each fix, never carried over`() {
        val seq = listOf(
            fix(RtkStatus.FIX, hDop = 0.8, hAccM = 0.014) to "reported",
            fix(RtkStatus.FIX, hDop = 0.8) to "estimated",                 // GST stopped
            fix(RtkStatus.FLOAT, hDop = 1.0, hAccM = 0.3) to "reported",
            fix(RtkStatus.SINGLE, hDop = 1.0, cls = AccuracyClass.DEVICE) to "estimated",
            fix(RtkStatus.FIX, hDop = 0.8, hAccM = 0.015) to "reported",
        )
        val out = seq.map { (f, _) -> publisher.fixToLocation(f) }
        assertEquals(seq.map { it.second }, out.map { source(it) })
        assertEquals(0.014f, out[0].accuracy, 1e-6f)
        assertEquals(0.10f, out[1].accuracy, 0f)
        assertTrue("an estimate is never better than the floor", out[3].accuracy >= 5.0f)
    }

    @Test fun `receiver accuracy wins, flat fallback only without HDOP`() {
        assertEquals(0.014f, publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.014)).accuracy, 1e-6f)
        assertEquals(0.5f, publisher.fixToLocation(fix(RtkStatus.FLOAT, hDop = null)).accuracy, 0f)
        assertEquals(5.0f, publisher.fixToLocation(fix(RtkStatus.NONE, hDop = 1.0)).accuracy, 0f)   // no solution → no estimate
    }
}
