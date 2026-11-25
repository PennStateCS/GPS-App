package app.surrealar.ui.capture

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.domain.usecase.CaptureCoordinateUseCase
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Tests the capture screen's ViewModel: the live "OK to capture" gate, provider-switch safety, the
 * external-only averaging filter, and the save path (source → provider/method/device mapping,
 * antenna-height selection, and the refusal to persist a timed-out capture).
 *
 * Robolectric is used because SourceSettings / DiagnosticsLogger call android.util.Log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureViewModelTest {

    private val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    private val currentFix = MutableStateFlow<Fix?>(null)

    /** The feed's receiver datum; [push] normalises through it like the switchboard does. */
    private val feedDatum = app.surrealar.gnss.source.ReceiverFeedDatum(
        MutableStateFlow(app.surrealar.settings.model.ReceiverDatum.ITRF2014))

    /** Mirrors the switchboard: every fix is normalised, then lands on both the fixes stream and currentFix. */
    private suspend fun push(f: Fix) { val out = feedDatum.normalise(f); currentFix.value = out; fixes.emit(out) }
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(
        source: LocationSourceType = LocationSourceType.EXTERNAL,
        tcpName: String? = "Reach RS2+",
        tcpHost: String? = "192.168.42.1",
        receiver: GnssReceiverSettings = GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5),
        profile: app.surrealar.settings.model.ExternalReceiverProfile? =
            app.surrealar.settings.model.ExternalReceiverProfile.REACH_RS2_PLUS,
    ): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        if (profile != null) Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(profile))
        Mockito.`when`(it.locationSource).thenReturn(flowOf(source))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf(tcpName))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf(tcpHost))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(receiver))
    }

    private fun vm(
        settingsRepo: SettingsRepository = settings(),
    ): CaptureViewModel {
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.fixes).thenReturn(fixes)
        Mockito.`when`(switchboard.currentFix).thenReturn(currentFix)
        return CaptureViewModel(
            fixSwitchboard = switchboard,
            captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
            sourceSettings = sourceSettings,
            settingsRepository = settingsRepo,
            feedDatum = feedDatum,
        )
    }

    private fun fix(
        provider: Provider = Provider.EXTERNAL_TCP,
        rtk: RtkStatus = RtkStatus.FIX,
        sats: Int = 20,
        alt: Double = 100.0,
    ) = Fix(
        provider = provider, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = alt, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk, satsUsed = sats, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0
    )

    private fun policy(maxDur: Int = 100, minSamples: Int = 2) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = maxDur, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30
    )

    private suspend fun completeCapture(vm: CaptureViewModel, alt: Double = 100.0) {
        vm.start(policy())
        push(fix(alt = alt))
        push(fix(alt = alt))
        val s = vm.state.value
        assertTrue("expected Complete, was $s", s is ObservationSession.State.Complete)
        assertTrue((s as ObservationSession.State.Complete).requirementsMet)
    }

    // ── Provider switch ──────────────────────────────────────────────────────

    @Test
    fun providerSwitch_cancelsCapture() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 10))
        push(fix())
        assertTrue(vm.state.value !is ObservationSession.State.Idle)

        providerFlow.value = ProviderChoice.INTERNAL

        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    // ── Averaging ────────────────────────────────────────────────────────────

    @Test
    fun internalFixes_areNeverAveragedIntoCapture() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2))
        repeat(5) { push(fix(provider = Provider.INTERNAL)) }
        assertFalse(vm.state.value is ObservationSession.State.Complete)

        push(fix()); push(fix())
        val s = vm.state.value as ObservationSession.State.Complete
        assertEquals(2, s.result.samples)
    }

    @Test
    fun internalCapture_averagesOnlyInternalFixes_andReportsSpread() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL))
        vm.start(policy(minSamples = 2).copy(requiredMinStatus = RtkStatus.SINGLE), internal = true)
        repeat(3) { push(fix(provider = Provider.EXTERNAL_TCP)) }   // external fixes are ignored
        assertFalse(vm.state.value is ObservationSession.State.Complete)

        push(fix(provider = Provider.INTERNAL, rtk = RtkStatus.SINGLE).copy(latDeg = 41.0))
        push(fix(provider = Provider.INTERNAL, rtk = RtkStatus.SINGLE).copy(latDeg = 41.00002)) // ~2.2 m north
        val s = vm.state.value as ObservationSession.State.Complete
        assertEquals(2, s.result.samples)
        val spread = s.result.spreadHorizontalM!!
        assertTrue("spread was $spread", spread in 1.0..3.0)   // σ of two points 2.2 m apart ≈ 1.57 m
    }

    @Test
    fun precisionTarget_blocksCompletionUntilSpreadIsWithinTarget() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2).copy(horizontalPrecisionTargetM = 0.01))
        // Two samples ~2.2 m apart: time/fixes met, precision not → keeps capturing.
        push(fix().copy(latDeg = 41.0))
        push(fix().copy(latDeg = 41.00002))
        assertTrue("was ${vm.state.value}", vm.state.value is ObservationSession.State.Capturing)
    }

    @Test
    fun precisionTarget_met_completes() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2).copy(horizontalPrecisionTargetM = 0.01))
        push(fix()); push(fix())   // identical positions → spread 0
        val s = vm.state.value as ObservationSession.State.Complete
        assertTrue(s.requirementsMet)
    }

    @Test
    fun precisionTarget_notMetByTimeout_cannotBeSaved() = runBlocking {
        val vm = vm()
        vm.start(policy(maxDur = 0, minSamples = 1).copy(horizontalPrecisionTargetM = 0.001))
        push(fix().copy(latDeg = 41.0))
        val s = vm.state.value as ObservationSession.State.Complete
        assertFalse(s.requirementsMet)
    }

    @Test
    fun cancel_returnsToIdle() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 10))
        push(fix())
        vm.cancel()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        push(fix())   // the old session must not keep emitting into the UI
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    // ── Save path ────────────────────────────────────────────────────────────

    @Test
    fun save_withoutCompletedCapture_doesNothing() = runBlocking {
        val vm = vm()
        vm.save("Pt", null, 0, "ic_pin")
        vm.start(policy(minSamples = 10))
        push(fix())
        vm.save("Pt", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test
    fun save_timedOutCapture_isBlocked() = runBlocking {
        val vm = vm()
        vm.start(policy(maxDur = 0, minSamples = 5))
        push(fix())
        val s = vm.state.value as ObservationSession.State.Complete
        assertFalse(s.requirementsMet)

        vm.save("Pt", null, 0, "ic_pin")
        assertTrue("timed-out capture must not be persisted", repo.store.isEmpty())
    }

    @Test
    fun save_external_mapsProvenanceAndAppliesExternalAntennaHeight() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.EXTERNAL))
        completeCapture(vm, alt = 100.0)
        vm.save("Corner", "note", 7, "ic_pin")

        val c = repo.store.single()
        assertEquals("Corner", c.name)
        assertEquals("note", c.note)
        assertEquals("external_gnss", c.captureMethod)
        // sourceDevice is the active profile's label (not the user's device name / host).
        assertEquals("Emlid Reach RS2+", c.sourceDevice)
        assertEquals(98.066, c.altitude, 1e-9)   // 100.0 − (1.8 m pole + 0.134 m RS2+ phase-centre offset)
        assertEquals(2, c.averagedSamples)
    }

    @Test
    fun save_external_sourceDeviceIsProfileLabelThenGeneric() = runBlocking {
        val hostVm = vm(settings(tcpName = "  ", tcpHost = "10.0.0.5",
            profile = app.surrealar.settings.model.ExternalReceiverProfile.GENERIC_NMEA_TCP))
        completeCapture(hostVm)
        hostVm.save("A", null, 0, "ic_pin")
        assertEquals("Generic NMEA TCP", repo.store.last().sourceDevice)

        val genericVm = vm(settings(tcpName = null, tcpHost = null, profile = null))
        completeCapture(genericVm)
        genericVm.acknowledgeUnconfirmedReference()   // unreadable profile: unconfirmed reference (Phase 4 stabilization WP A)
        genericVm.save("B", null, 0, "ic_pin")
        assertEquals("External GNSS", repo.store.last().sourceDevice)
    }

    @Test
    fun save_internal_usesInternalAntennaHeight() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL))
        completeCapture(vm, alt = 100.0)
        vm.save("Pt", null, 0, "ic_pin")

        val c = repo.store.single()
        assertEquals("internal_gps", c.captureMethod)
        assertEquals("Internal GPS", c.sourceDevice)
        assertEquals(99.5, c.altitude, 1e-9)   // 100.0 − 0.5 m internal height
    }

    @Test
    fun antennaHeightChangedMidCapture_savedPointUsesTheValueFromCaptureStart() = runBlocking {
        val receiverFlow = MutableStateFlow(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5))
        val repoSettings = settings().also { Mockito.`when`(it.gnssReceiverSettings).thenReturn(receiverFlow) }
        val vm = vm(repoSettings)
        vm.start(policy())
        receiverFlow.value = GnssReceiverSettings(poleHeightM = 2.5, internalAntennaHeightM = 0.5)   // edited mid-capture
        push(fix(alt = 100.0)); push(fix(alt = 100.0))
        vm.save("Pt", null, 0, "ic_pin")
        assertEquals("altitude uses the 1.8 m pole height from capture start", 98.066, repo.store.single().altitude, 1e-9)
        assertEquals(1.8, vm.settingsAtStart()!!.receiver!!.poleHeightM, 0.0)
    }

    @Test
    fun save_antennaOffsetUnreadable_isBlockedWithError() = runBlocking {
        val repoSettings = settings(source = LocationSourceType.EXTERNAL).also {
            Mockito.`when`(it.gnssReceiverSettings).thenReturn(kotlinx.coroutines.flow.flow { throw java.io.IOException("datastore") })
        }
        val vm = vm(repoSettings)
        completeCapture(vm, alt = 100.0)
        vm.save("Pt", null, 0, "ic_pin")

        assertTrue("no point may be saved with a made-up 0 m antenna height", repo.store.isEmpty())
        assertEquals(CaptureViewModel.ANTENNA_OFFSET_ERROR, vm.saveError.value)
        vm.clearSaveError()
        assertNull(vm.saveError.value)
    }

    @Test
    fun save_simulator_appliesNoAntennaHeight() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.SIMULATOR))
        completeCapture(vm, alt = 100.0)
        vm.save("Pt", null, 0, "ic_pin")

        val c = repo.store.single()
        assertEquals("averaged", c.captureMethod)
        assertNull(c.sourceDevice)
        assertEquals(100.0, c.altitude, 1e-9)
    }
}
