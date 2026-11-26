package app.surrealar.gnss.mock

import androidx.test.core.app.ApplicationProvider
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.bus.adapters.SourceAdapter
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
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

/**
 * Stabilization item 2: the real [FixSwitchboard] with a fix transform (datum normalisation) that throws, feeding
 * the real mock-location publisher. A fix that could not be normalised is never injected as the device's
 * location, the last good one is withdrawn (test provider removed), and publishing resumes once fixes normalise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationTransformFailureTest {

    private class FakeExternal : SourceAdapter {
        val raw = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        override val fixes: SharedFlow<Fix> get() = raw
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var publisher: AndroidMockLocationPublisher
    private var failing = false

    @After fun tearDown() { publisher.stop(); scope.cancel() }

    private fun fix(time: Instant, lat: Double) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = time, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = lat, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = null, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 24, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    @Test fun `a fix whose transform throws is never injected and the last position is withdrawn`() {
        val external = FakeExternal()
        val sb = FixSwitchboard(
            scope = scope,
            sourceSettings = SourceSettings(MutableStateFlow(ProviderChoice.EXTERNAL_TCP), MutableStateFlow(emptyList()), MutableStateFlow(null)),
            adapters = mapOf(ProviderChoice.EXTERNAL_TCP to external),
            fixTransform = { f -> if (failing) throw IllegalStateException("SYNTHETIC normalisation failure") else f },
        ).also { it.start() }
        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.mockLocationEnabled).thenReturn(flowOf(true))
        Mockito.`when`(settings.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(settings.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings()))
        Mockito.`when`(settings.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.REACH_RS2_PLUS))
        publisher = AndroidMockLocationPublisher(ApplicationProvider.getApplicationContext(), sb, settings)
        publisher.start(scope)

        val t0 = Instant.now()
        external.raw.tryEmit(fix(t0, lat = 41.0))
        assertTrue("good fix published", MockInjectionStatus.providerActive)
        val injected = MockInjectionStatus.injectionCount
        assertEquals(41.0, MockInjectionStatus.lastInjectedLat!!, 0.0)

        failing = true
        repeat(3) { i -> external.raw.tryEmit(fix(t0.plusSeconds(1L + i), lat = 41.5)) }
        assertEquals("no failed fix injected", injected, MockInjectionStatus.injectionCount)
        assertFalse("last good position withdrawn from the device", MockInjectionStatus.providerActive)
        assertEquals(41.0, MockInjectionStatus.lastInjectedLat!!, 0.0)

        failing = false
        external.raw.tryEmit(fix(t0.plusSeconds(10), lat = 41.0))
        assertTrue("publishing resumes once fixes normalise", MockInjectionStatus.providerActive)
        assertEquals(injected + 1, MockInjectionStatus.injectionCount)
    }
}
