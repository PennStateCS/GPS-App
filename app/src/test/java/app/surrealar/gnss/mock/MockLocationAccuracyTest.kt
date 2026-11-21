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
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.math.sqrt

/**
 * The accuracy sent to ARCore with a mock location is the app-wide number: the receiver's own when
 * present, else DOP × UERE for the fix's accuracy class; the flat per-status value only without HDOP.
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

    private fun fix(rtk: RtkStatus, hDop: Double?, hAccM: Double? = null, cls: AccuracyClass? = null) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.now(), timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = null, geoidSeparationM = null,
        hDop = hDop, vDop = null, pDop = null, hAccM = hAccM, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null, accuracyClass = cls,
    )

    @Test fun `FLOAT without GST - DOP x UERE like the toolbar, not the flat 0_5 m`() {
        assertEquals((1.2 * 0.25 * r68).toFloat(), publisher.fixToLocation(fix(RtkStatus.FLOAT, hDop = 1.2)).accuracy, 1e-6f)
    }

    @Test fun `accuracy class decides the UERE (generic receiver SINGLE)`() {
        val f = fix(RtkStatus.SINGLE, hDop = 1.0, cls = AccuracyClass.SINGLE_FREQUENCY)
        assertEquals((5.0 * r68).toFloat(), publisher.fixToLocation(f).accuracy, 1e-6f)
    }

    @Test fun `receiver accuracy wins, flat fallback only without HDOP`() {
        assertEquals(0.014f, publisher.fixToLocation(fix(RtkStatus.FIX, hDop = 1.0, hAccM = 0.014)).accuracy, 1e-6f)
        assertEquals(0.5f, publisher.fixToLocation(fix(RtkStatus.FLOAT, hDop = null)).accuracy, 0f)
        assertEquals(5.0f, publisher.fixToLocation(fix(RtkStatus.NONE, hDop = 1.0)).accuracy, 0f)   // no solution → no estimate
    }
}
