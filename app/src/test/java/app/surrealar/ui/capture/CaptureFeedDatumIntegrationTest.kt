package app.surrealar.ui.capture

import app.surrealar.domain.model.FrameTokens
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.domain.usecase.CaptureCoordinateUseCase
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.ReceiverFeedDatum
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.gnss.bus.adapters.SourceAdapter
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.time.Instant

/**
 * Integration (Phase 4 WP C task 3): the real [ReceiverFeedDatum] wired as the real [FixSwitchboard]'s fix
 * transform, with the switchboard's real fix buffer (replay 1 + 64, DROP_OLDEST), feeding a real
 * [CaptureViewModel]. The capture's collector runs on a queued (standard) dispatcher, so fixes the switchboard
 * normalised under the OLD datum really sit in its buffer when the datum changes, and are delivered afterwards.
 * Proves they never leak into an occupation bound to the new datum.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureFeedDatumIntegrationTest {

    /** The external adapter: raw (receiver-frame) fixes, as the TCP source would publish them. */
    private class FakeExternal : SourceAdapter {
        val raw = MutableSharedFlow<Fix>(extraBufferCapacity = 512)
        override val fixes: SharedFlow<Fix> get() = raw
    }

    private val setting = MutableStateFlow(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014))
    private val feedDatumFlow = MutableStateFlow(ReceiverDatum.ITRF2014)
    private val feedDatum = ReceiverFeedDatum(feedDatumFlow)
    private val external = FakeExternal()
    private val repo = FakeCoordinateRepository()

    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(setting)
        Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.REACH_RS2_PLUS))
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf("192.168.42.1"))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5)))
    }

    private var seq = 0L
    private fun raw(lat: Double = 41.0) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.now().plusMillis(200 * seq++), timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = lat, lonDeg = -76.0, altEllipsoidalM = 100.0, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0,
    )

    private fun policy(minSamples: Int) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = 100, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30,
    )

    /** Real switchboard on an unconfined dispatcher (it normalises each fix the moment the adapter emits it). */
    private fun TestScope.switchboard(): FixSwitchboard {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))   // the capture's collector: queued
        val sbScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val providers = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
        return FixSwitchboard(
            scope = sbScope,
            sourceSettings = SourceSettings(providers, MutableStateFlow(emptyList()), MutableStateFlow(null)),
            adapters = mapOf(ProviderChoice.EXTERNAL_TCP to external),
            fixTransform = feedDatum::normalise,
        ).also { it.start() }
    }

    private fun vm(sb: FixSwitchboard) = CaptureViewModel(
        fixSwitchboard = sb,
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = SourceSettings(MutableStateFlow(ProviderChoice.EXTERNAL_TCP), MutableStateFlow(emptyList()), MutableStateFlow(null)),
        settingsRepository = settings(),
        feedDatum = feedDatum,
    )

    @Test fun datumChangedBeforeStart_bufferedOldDatumFixes_includingOverflow_neverReachTheOccupation() = runTest {
        val sb = switchboard()
        // The user switched to NAD83(2011); the feed has not picked it up yet.
        setting.value = CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.NAD83_2011)
        val vm = vm(sb)
        vm.start(policy(minSamples = 3))
        runCurrent()

        // 70 fixes normalised under ITRF2014 pile up in the switchboard buffer (more than 64: DROP_OLDEST).
        repeat(70) { external.raw.emit(raw(lat = 41.5)) }
        // The feed catches up; the next fixes are normalised under NAD83(2011).
        feedDatumFlow.value = ReceiverDatum.NAD83_2011
        repeat(3) { external.raw.emit(raw()) }
        runCurrent()                                  // only now does the capture see the buffered fixes

        val done = vm.state.value as ObservationSession.State.Complete
        assertTrue(done.requirementsMet)
        assertEquals(3, done.result.samples)
        assertNull(vm.interruption.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(FrameTokens.NAD83_2011, c.receiverFrame)
        // Only the NAD83-normalised fixes (raw lat 41.0) were averaged; the buffered lat 41.5 ones never were.
        assertEquals(FixDatumNormalizer.nad83ToWgs84(raw()).latDeg, c.latitude, 1e-9)
    }

    @Test fun datumChangedMidOccupation_withFixesStillBuffered_stopsAndSavesNothing() = runTest {
        val sb = switchboard()
        val vm = vm(sb)
        vm.start(policy(minSamples = 10))
        runCurrent()
        external.raw.emit(raw()); runCurrent()
        assertEquals(1, (vm.state.value as ObservationSession.State.Capturing).samples)

        repeat(3) { external.raw.emit(raw()) }        // ITRF2014-normalised, buffered
        setting.value = CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.NAD83_2011)
        feedDatumFlow.value = ReceiverDatum.NAD83_2011
        repeat(8) { external.raw.emit(raw()) }        // NAD83-normalised, buffered behind them
        runCurrent()

        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.DATUM_CHANGED_MESSAGE, vm.interruption.value)
        vm.save("P", null, 0, "ic_pin")
        assertTrue("an occupation never mixes datums", repo.store.isEmpty())
    }

    @Test fun everyBufferedFix_carriesTheDatumThatNormalisedIt() = runTest {
        val sb = switchboard()
        val seen = mutableListOf<Fix>()
        val job = backgroundScope.launchCollector(sb, seen)
        runCurrent()
        repeat(2) { external.raw.emit(raw()) }
        feedDatumFlow.value = ReceiverDatum.NAD83_2011
        repeat(2) { external.raw.emit(raw()) }
        runCurrent()
        job.cancel()
        assertEquals(listOf(ReceiverDatum.ITRF2014, ReceiverDatum.ITRF2014, ReceiverDatum.NAD83_2011, ReceiverDatum.NAD83_2011),
            seen.map { feedDatum.datumOf(it) })
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollector(sb: FixSwitchboard, into: MutableList<Fix>) =
        launch(Dispatchers.Main) { sb.fixes.collect { into += it } }
}
