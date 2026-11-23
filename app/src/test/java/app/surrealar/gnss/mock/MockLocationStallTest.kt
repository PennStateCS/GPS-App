package app.surrealar.gnss.mock

import androidx.test.core.app.ApplicationProvider
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** When the receiver stops sending fixes, the mock provider is removed, and it returns on a live fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationStallTest {

    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 8)
    private val fixStale = MutableStateFlow(false)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var publisher: AndroidMockLocationPublisher

    @After fun tearDown() { publisher.stop(); scope.cancel() }

    private fun fix(time: Instant) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = time, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.3, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 24, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    private fun start() {
        val sb = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(sb.fixes).thenReturn(fixes)
        Mockito.`when`(sb.fixStale).thenReturn(fixStale)
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.mockLocationEnabled).thenReturn(flowOf(true))
        Mockito.`when`(settings.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(settings.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings()))
        Mockito.`when`(settings.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.REACH_RS2_PLUS))
        publisher = AndroidMockLocationPublisher(ApplicationProvider.getApplicationContext(), sb, settings)
        publisher.start(scope)
    }

    @Test fun `stale fixes remove the provider, the frozen fix is not republished, a live fix restores it`() = runBlocking {
        start()
        val t0 = Instant.now()
        fixes.emit(fix(t0))
        assertTrue("publishing", MockInjectionStatus.providerActive)
        val injected = MockInjectionStatus.injectionCount

        fixStale.value = true
        assertFalse("provider removed while no live fix", MockInjectionStatus.providerActive)

        fixStale.value = false                 // the switchboard clears it just before the next fix
        assertFalse("frozen fix not republished", MockInjectionStatus.providerActive)
        assertEquals(injected, MockInjectionStatus.injectionCount)

        fixes.emit(fix(t0.plusSeconds(1)))
        assertTrue("live fix restores the provider", MockInjectionStatus.providerActive)
        assertEquals(injected + 1, MockInjectionStatus.injectionCount)
    }
}
