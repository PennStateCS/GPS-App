package app.surrealar.ui.capture

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.domain.usecase.CaptureCoordinateUseCase
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.capture.AveragingPolicy
import app.surrealar.gnss.capture.FixAcceptanceSettings
import app.surrealar.gnss.capture.ObservationSession
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
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

    /** Mirrors the switchboard: every fix lands on both the fixes stream and currentFix. */
    private suspend fun push(f: Fix) { currentFix.value = f; fixes.emit(f) }
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(
        source: LocationSourceType = LocationSourceType.EXTERNAL,
        tcpName: String? = "Reach RS2+",
        tcpHost: String? = "192.168.42.1",
        receiver: GnssReceiverSettings = GnssReceiverSettings(antennaHeightM = 1.8, internalAntennaHeightM = 0.5),
    ): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.locationSource).thenReturn(flowOf(source))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf(tcpName))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf(tcpHost))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(receiver))
    }

    private fun vm(
        settingsRepo: SettingsRepository = settings(),
        minDwell: Duration = Duration.ZERO,
    ): CaptureViewModel {
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.fixes).thenReturn(fixes)
        Mockito.`when`(switchboard.currentFix).thenReturn(currentFix)
        return CaptureViewModel(
            fixSwitchboard = switchboard,
            captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
            fixAcceptance = FixAcceptanceSettings(minDwell = minDwell),
            sourceSettings = sourceSettings,
            settingsRepository = settingsRepo,
        )
    }

    private fun fix(
        provider: Provider = Provider.RS2_EXTERNAL,
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

    // ── OK-to-capture gate ───────────────────────────────────────────────────

    @Test
    fun okToCapture_followsAcceptance() = runBlocking {
        val vm = vm()
        assertFalse(vm.okToCapture.value)
        push(fix())
        assertTrue(vm.okToCapture.value)
        push(fix(sats = 3))   // below minSats
        assertFalse(vm.okToCapture.value)
        push(fix(rtk = RtkStatus.SINGLE))
        assertFalse(vm.okToCapture.value)
    }

    @Test
    fun okToCapture_waitsForDwell() = runBlocking {
        val vm = vm(minDwell = 1.hours)
        repeat(3) { push(fix()) }
        assertFalse(vm.okToCapture.value)
    }

    @Test
    fun okToCapture_clearsWhenFeedLosesFix() = runBlocking {
        val vm = vm()
        push(fix())
        assertTrue(vm.okToCapture.value)
        currentFix.value = null   // switchboard liveness watchdog: fixes stopped
        assertFalse(vm.okToCapture.value)
    }

    @Test
    fun providerSwitch_resetsReadinessAndCancelsCapture() = runBlocking {
        val vm = vm()
        push(fix())
        assertTrue(vm.okToCapture.value)
        vm.start(policy(minSamples = 10))
        push(fix())
        assertTrue(vm.state.value !is ObservationSession.State.Idle)

        providerFlow.value = ProviderChoice.INTERNAL

        assertFalse(vm.okToCapture.value)
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
        repeat(3) { push(fix(provider = Provider.RS2_EXTERNAL)) }   // external fixes are ignored
        assertFalse(vm.state.value is ObservationSession.State.Complete)

        push(fix(provider = Provider.INTERNAL, rtk = RtkStatus.SINGLE).copy(latDeg = 41.0))
        push(fix(provider = Provider.INTERNAL, rtk = RtkStatus.SINGLE).copy(latDeg = 41.00002)) // ~2.2 m north
        val s = vm.state.value as ObservationSession.State.Complete
        assertEquals(2, s.result.samples)
        val spread = s.result.spreadHorizontalM!!
        assertTrue("spread was $spread", spread in 1.0..3.0)   // σ of two points 2.2 m apart ≈ 1.57 m
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
        assertEquals("Reach RS2+", c.sourceDevice)
        assertEquals(98.2, c.altitude, 1e-9)   // 100.0 − 1.8 m external pole height
        assertEquals(2, c.averagedSamples)
    }

    @Test
    fun save_external_sourceDeviceFallsBackToHostThenGeneric() = runBlocking {
        val hostVm = vm(settings(tcpName = "  ", tcpHost = "10.0.0.5"))
        completeCapture(hostVm)
        hostVm.save("A", null, 0, "ic_pin")
        assertEquals("10.0.0.5", repo.store.last().sourceDevice)

        val genericVm = vm(settings(tcpName = null, tcpHost = null))
        completeCapture(genericVm)
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
