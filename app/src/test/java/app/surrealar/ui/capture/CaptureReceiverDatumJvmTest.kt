package app.surrealar.ui.capture

import app.surrealar.domain.model.FrameTokens
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.coordinateEpochOf
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
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.time.Instant

/**
 * Receiver-datum provenance of external captures (plain JVM, same set-up as [CaptureViewModelJvmTest]):
 * an occupation is bound to one External receiver datum, averages only fixes the feed normalised under
 * it, and is stopped (never saved, message shown) when the datum or the GNSS source changes while it
 * runs. The saved `receiverFrame` is the datum that normalised every averaged fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureReceiverDatumJvmTest {

    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 64)
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()

    /** Settings → Coordinates (what the user chose) and the datum the feed applies (follows it a moment later). */
    private val setting = MutableStateFlow(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014))
    private val feedDatumFlow = MutableStateFlow(ReceiverDatum.ITRF2014)
    private val feedDatum = ReceiverFeedDatum(feedDatumFlow)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(
        coordinateSystem: Flow<CoordinateSystemSettings> = setting,
    ): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(coordinateSystem)
        Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.REACH_RS2_PLUS))
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf("192.168.42.1"))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5)))
    }

    private fun vm(settingsRepo: SettingsRepository = settings()) = CaptureViewModel(
        fixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also { Mockito.`when`(it.fixes).thenReturn(fixes) },
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = sourceSettings,
        settingsRepository = settingsRepo,
        feedDatum = feedDatum,
    )

    private fun fix(provider: Provider = Provider.EXTERNAL_TCP) = Fix(
        provider = provider, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 100.0, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0
    )

    private fun policy(minSamples: Int) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = 100, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30
    )

    /** Publishes a fix as the switchboard does: normalised (and datum-tagged) by the feed. */
    private suspend fun push(f: Fix = fix()) = fixes.emit(feedDatum.normalise(f))

    /** The user changes Settings → External receiver datum; the feed picks it up too. */
    private fun changeDatum(d: ReceiverDatum) {
        setting.value = CoordinateSystemSettings(externalReceiverDatum = d)
        feedDatumFlow.value = d
    }

    private fun samples(vm: CaptureViewModel) = when (val s = vm.state.value) {
        is ObservationSession.State.Capturing -> s.samples
        is ObservationSession.State.Complete -> s.result.samples
        else -> 0
    }

    // ── change during the occupation ─────────────────────────────────────────

    @Test fun datumSettingChange_midOccupation_stopsCapture_nothingSaved_messageShown() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 3))
        push()
        assertTrue(vm.state.value is ObservationSession.State.Capturing)

        changeDatum(ReceiverDatum.NAD83_2011)

        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.DATUM_CHANGED_MESSAGE, vm.interruption.value)
        repeat(5) { push() }
        assertEquals("the stopped occupation never resumes", ObservationSession.State.Idle, vm.state.value)
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test fun feedDatumChange_midOccupation_withoutSettingEmission_stopsOnTheFirstOtherDatumFix() = runBlocking {
        // The setting flow is not observed changing (e.g. it emitted later): the per-fix check still stops it.
        val vm = vm(settings(coordinateSystem = flowOf(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014))))
        vm.start(policy(minSamples = 3))
        push(); push()
        assertEquals(2, samples(vm))

        feedDatumFlow.value = ReceiverDatum.NAD83_2011
        push()

        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.DATUM_CHANGED_MESSAGE, vm.interruption.value)
        repeat(3) { push() }
        vm.save("P", null, 0, "ic_pin")
        assertTrue("no mixed-frame occupation is saved", repo.store.isEmpty())
    }

    @Test fun unreadableDatumSetting_occupationBoundToFeedDatum_stillStopsOnChange() = runBlocking {
        val vm = vm(settings(coordinateSystem = flow { throw java.io.IOException("datastore") }))
        vm.start(policy(minSamples = 3))
        push()
        assertEquals(1, samples(vm))
        feedDatumFlow.value = ReceiverDatum.NAD83_2011
        push()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.DATUM_CHANGED_MESSAGE, vm.interruption.value)
    }

    // ── change immediately before start ──────────────────────────────────────

    @Test fun datumChangedJustBeforeStart_staleFixesDiscarded_onlyNewDatumFixesAveraged() = runBlocking {
        // Already normalised under the old datum and still buffered when the capture starts.
        val staleBuffered = feedDatum.normalise(fix())
        // The user switched to NAD83(2011); the feed has not picked it up yet.
        setting.value = CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.NAD83_2011)
        val vm = vm()
        vm.start(policy(minSamples = 2))

        fixes.emit(staleBuffered)
        push(); push()                                  // still ITRF2014-normalised (feed lagging)
        assertEquals("old-datum fixes are never averaged", ObservationSession.State.Idle, vm.state.value)
        assertNull("settling is not an interruption", vm.interruption.value)

        feedDatumFlow.value = ReceiverDatum.NAD83_2011  // the feed catches up
        push(); push()
        val done = vm.state.value as ObservationSession.State.Complete
        assertTrue(done.requirementsMet)
        assertEquals(2, done.result.samples)

        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(FrameTokens.NAD83_2011, c.receiverFrame)
        assertEquals(FrameTokens.WGS84_AS_ITRF2014, c.sourceFrame)
        assertEquals(coordinateEpochOf(c.timestamp), c.coordinateEpoch!!, 0.0)
        // The averaged numbers are the NAD83→WGS84-normalised fixes, not the raw (ITRF2014-read) ones.
        val nad83 = FixDatumNormalizer.nad83ToWgs84(fix())
        assertEquals(nad83.latDeg, c.latitude, 1e-9)
        assertEquals(nad83.lonDeg, c.longitude, 1e-9)
        assertNotEquals(41.0, c.latitude, 1e-7)
    }

    // ── source switch ────────────────────────────────────────────────────────

    @Test fun sourceSwitch_midOccupation_stopsCapture_withMessage_nothingSaved() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 3))
        push()
        providerFlow.value = ProviderChoice.INTERNAL
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.SOURCE_CHANGED_MESSAGE, vm.interruption.value)
        push(fix(Provider.INTERNAL)); push()
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    // ── idle / completed ─────────────────────────────────────────────────────

    @Test fun datumChange_whileIdle_hasNoCaptureEffect_andNextCaptureUsesNewDatum() = runBlocking {
        val vm = vm()
        changeDatum(ReceiverDatum.NAD83_2011)
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertNull(vm.interruption.value)

        vm.start(policy(minSamples = 2))
        push(); push()
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        vm.save("P", null, 0, "ic_pin")
        assertEquals(FrameTokens.NAD83_2011, repo.store.single().receiverFrame)
    }

    @Test fun datumChange_afterCompletion_keepsTheResult_withTheOccupationDatum() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2))
        push(); push()
        assertTrue(vm.state.value is ObservationSession.State.Complete)

        changeDatum(ReceiverDatum.NAD83_2011)

        assertTrue("all fixes were ITRF2014-normalised: the result stands", vm.state.value is ObservationSession.State.Complete)
        assertNull(vm.interruption.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(FrameTokens.ITRF2014, c.receiverFrame)
        assertEquals(41.0, c.latitude, 1e-9)          // ITRF2014 normalisation is the identity
        assertEquals(FrameTokens.WGS84_AS_ITRF2014, c.sourceFrame)
        assertEquals(coordinateEpochOf(c.timestamp), c.coordinateEpoch!!, 0.0)
    }

    @Test fun newCapture_afterInterruption_clearsTheMessage_andStartsClean() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 3))
        push()
        changeDatum(ReceiverDatum.NAD83_2011)
        assertEquals(CaptureViewModel.DATUM_CHANGED_MESSAGE, vm.interruption.value)

        vm.start(policy(minSamples = 2))
        assertNull(vm.interruption.value)
        push(); push()
        assertEquals(2, samples(vm))
        vm.save("P", null, 0, "ic_pin")
        assertEquals(FrameTokens.NAD83_2011, repo.store.single().receiverFrame)
    }

    @Test fun fixWithoutRecordedDatum_isNeverAveraged() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 1))
        fixes.emit(fix())       // published without going through the feed's normaliser
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertNull(vm.interruption.value)
        push()
        assertTrue(vm.state.value is ObservationSession.State.Complete)
    }

    @Test fun internalCapture_ignoresReceiverDatumChanges() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2).copy(requiredMinStatus = RtkStatus.SINGLE), internal = true)
        push(fix(Provider.INTERNAL))
        changeDatum(ReceiverDatum.NAD83_2011)
        push(fix(Provider.INTERNAL))
        assertTrue(vm.state.value is ObservationSession.State.Complete)
        assertNull(vm.interruption.value)
        assertNull(vm.occupationReceiverDatum())
    }
}
