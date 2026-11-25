package app.surrealar.ui.capture

import app.surrealar.domain.model.CaptureMethod
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
import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.receiver.tilt.ReceiverTiltMonitor
import app.surrealar.receiver.tilt.TiltTestNmea
import app.surrealar.receiver.tilt.TiltTestNmea.etcActive
import app.surrealar.receiver.tilt.TiltTestNmea.etcAligning
import app.surrealar.receiver.tilt.TiltTestNmea.etcOff
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.util.diagnostics.CaptureInterruptions
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Phase 4 WP C capture rules for RS4 / RS4 Pro external captures (plain JVM, same set-up as
 * [CaptureReceiverDatumJvmTest]): the occupation is bound to one position reference; receiver-compensated
 * pole-tip epochs are saved WITHOUT the app's pole-height / phase-centre reduction; possibly-compensated epochs
 * are never averaged; reference changes, tilt loss and interpretation-changing settings stop the occupation.
 * RS2+ captures are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureTiltRulesJvmTest {

    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 64)
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()
    private val feedDatum = ReceiverFeedDatum(MutableStateFlow(ReceiverDatum.ITRF2014))

    private val profile = MutableStateFlow(ExternalReceiverProfile.REACH_RS4)
    private val output = MutableStateFlow(CompensatedOutputSetting.ENABLED)
    private val receiver = MutableStateFlow(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5))
    private val host = MutableStateFlow<String?>("192.168.42.1")
    private val firmware = MutableStateFlow<String?>("34.10")

    private var now = 5_000_000L
    private var sod = 61_200.0
    private lateinit var monitorScope: CoroutineScope
    private lateinit var monitor: ReceiverTiltMonitor

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        monitorScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        monitor = ReceiverTiltMonitor(monitorScope, profile, output, firmware, MutableStateFlow(0), { now }, etcWaitMs = 0, tickMs = null)
        CaptureInterruptions.reset()
    }

    @After fun tearDown() {
        monitorScope.cancel()
        Dispatchers.resetMain()
    }

    private fun settings(): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(flowOf(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014)))
        Mockito.`when`(it.externalReceiverProfile).thenReturn(profile)
        Mockito.`when`(it.compensatedNmeaOutput).thenReturn(output)
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(host)
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(receiver)
    }

    private fun vm(withMonitor: Boolean = true) = CaptureViewModel(
        fixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also { Mockito.`when`(it.fixes).thenReturn(fixes) },
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = sourceSettings,
        settingsRepository = settings(),
        feedDatum = feedDatum,
        tiltMonitor = if (withMonitor) monitor else null,
    )

    private fun policy(minSamples: Int) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = 100, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30,
    )

    /** One epoch: its ETC line (if any) arrives, then the fix is published through the feed. */
    private suspend fun epoch(etc: ((Double) -> String)?, station: String? = null) {
        sod += 0.2; now += 200
        etc?.let { monitor.onLine(it(sod)) }
        fixes.emit(feedDatum.normalise(TiltTestNmea.fix(sod, station = station)))
    }

    private fun samples(vm: CaptureViewModel) = when (val s = vm.state.value) {
        is ObservationSession.State.Capturing -> s.samples
        is ObservationSession.State.Complete -> s.result.samples
        else -> 0
    }

    // ── saving per reference ─────────────────────────────────────────────────

    @Test fun poleTipOccupation_isSavedWithoutAnyPoleOrPhaseCentreReduction() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        repeat(3) { epoch(::etcActive) }
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        assertEquals(EpochReference.POLE_TIP, vm.positionReference.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals("receiver pole tip: altitude as streamed", 100.0, c.altitude, 1e-9)
        assertNull("no app offset applied", c.antennaHeightM)
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue, c.captureMethod)
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP, CaptureMethod.fromStorage(c.captureMethod))
    }

    @Test fun antennaOccupation_tiltOff_keepsTodaysReduction() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        repeat(3) { epoch(::etcOff) }
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        val offset = 1.8 + ExternalReceiverProfile.REACH_RS4.phaseCenterOffsetM!!
        assertEquals(100.0 - offset, c.altitude, 1e-9)
        assertEquals(offset, c.antennaHeightM!!, 1e-12)
        assertEquals("external_gnss", c.captureMethod)
    }

    @Test fun outputDisabled_isAntenna_evenWithTiltActive() = runBlocking {
        output.value = CompensatedOutputSetting.DISABLED
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch(::etcActive) }
        vm.save("P", null, 0, "ic_pin")
        assertEquals(100.0 - 1.895, repo.store.single().altitude, 1e-9)
    }

    // ── possibly compensated ─────────────────────────────────────────────────

    @Test fun unknownSetting_withTiltOn_neverAverages_andStopsWithTheSettingsMessage() = runBlocking {
        output.value = CompensatedOutputSetting.UNKNOWN
        val vm = vm()
        vm.referenceNoValidEpochsMs = 0
        vm.start(policy(2))
        epoch(::etcActive)
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.MAY_BE_COMPENSATED_MESSAGE, vm.interruption.value)
        repeat(3) { epoch(::etcActive) }
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
        assertEquals(1, CaptureInterruptions.snapshot().counts["may_be_compensated"])
    }

    @Test fun enabledButFirmwareUnknown_isRefused() = runBlocking {
        firmware.value = null
        val vm = vm()
        vm.start(policy(2))
        repeat(3) { epoch(::etcActive) }
        assertEquals("excluded epochs never count", 0, samples(vm))
    }

    // ── changes during the occupation ────────────────────────────────────────

    @Test fun referenceChange_poleTipToAntenna_stops_neverMixes() = runBlocking {
        val vm = vm()
        vm.start(policy(5))
        repeat(2) { epoch(::etcActive) }
        assertEquals(2, samples(vm))
        epoch(::etcOff)
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(CaptureViewModel.REFERENCE_CHANGED_MESSAGE, vm.interruption.value)
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test fun tiltLoss_duringPoleTipOccupation_stops() = runBlocking {
        val vm = vm()
        vm.start(policy(5))
        repeat(2) { epoch(::etcActive) }
        epoch(::etcAligning)
        assertEquals(CaptureViewModel.TILT_LOST_MESSAGE, vm.interruption.value)
        assertTrue(repo.store.isEmpty())
    }

    @Test fun oneMissingEtc_duringPoleTipOccupation_isOnlyExcluded() = runBlocking {
        val vm = vm()
        vm.start(policy(4))
        repeat(2) { epoch(::etcActive) }
        epoch(null)                       // that epoch's ETC was lost
        assertNull(vm.interruption.value)
        assertEquals(2, samples(vm))
        repeat(2) { epoch(::etcActive) }
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
    }

    @Test fun poleTipWarning_epochsAreNotAveraged() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        epoch(::etcActive)
        epoch(etc = { t -> etcActive(t, notification = "10") })   // fast motion
        assertEquals(1, samples(vm))
        assertNull(vm.interruption.value)
    }

    @Test fun profileChange_stops_antennaSettingChange_continues() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        epoch(::etcActive)
        receiver.value = receiver.value.copy(poleHeightM = 2.0)
        assertNull("pole height edits do not stop it", vm.interruption.value)
        epoch(::etcActive)
        profile.value = ExternalReceiverProfile.REACH_RS4_PRO
        assertEquals(CaptureViewModel.PROFILE_CHANGED_MESSAGE, vm.interruption.value)
    }

    @Test fun antennaSettingChange_midCapture_savedPointUsesTheStartSettings() = runBlocking {
        output.value = CompensatedOutputSetting.DISABLED
        val vm = vm()
        vm.start(policy(2))
        epoch(::etcOff)
        receiver.value = receiver.value.copy(poleHeightM = 2.5)
        epoch(::etcOff)
        vm.save("P", null, 0, "ic_pin")
        assertEquals(100.0 - 1.895, repo.store.single().altitude, 1e-9)
    }

    @Test fun compensatedOutputSettingChange_stops() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        epoch(::etcActive)
        output.value = CompensatedOutputSetting.DISABLED
        assertEquals(CaptureViewModel.OUTPUT_SETTING_CHANGED_MESSAGE, vm.interruption.value)
    }

    @Test fun receiverHostChange_stops() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        epoch(::etcActive)
        host.value = "192.168.42.7"
        assertEquals(CaptureViewModel.RECEIVER_CHANGED_MESSAGE, vm.interruption.value)
    }

    @Test fun correctionBaseChange_stops_missingStationIdDoesNot() = runBlocking {
        val vm = vm()
        vm.start(policy(5))
        epoch(::etcActive, station = "0101")
        epoch(::etcActive, station = null)
        assertNull(vm.interruption.value)
        epoch(::etcActive, station = "0202")
        assertEquals(CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE, vm.interruption.value)
    }

    @Test fun restart_afterAnInterruption_startsClean() = runBlocking {
        val vm = vm()
        vm.start(policy(5))
        repeat(2) { epoch(::etcActive) }
        epoch(::etcOff)
        assertEquals(CaptureViewModel.REFERENCE_CHANGED_MESSAGE, vm.interruption.value)
        vm.start(policy(2))
        assertNull(vm.interruption.value)
        assertNull(vm.positionReference.value)
        repeat(2) { epoch(::etcOff) }
        vm.save("P", null, 0, "ic_pin")
        assertEquals("external_gnss", repo.store.single().captureMethod)
    }

    @Test fun completedResult_isKept_whenTheReferenceChangesAfterwards() = runBlocking {
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch(::etcActive) }
        assertTrue(vm.state.value is ObservationSession.State.Complete)
        epoch(::etcOff)
        profile.value = ExternalReceiverProfile.REACH_RS4_PRO
        assertTrue(vm.state.value is ObservationSession.State.Complete)
        vm.save("P", null, 0, "ic_pin")
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue, repo.store.single().captureMethod)
    }

    // ── RS2+ unchanged ───────────────────────────────────────────────────────

    @Test fun rs2Plus_withTheMonitor_behavesAsBefore_evenWithStrayEtc() = runBlocking {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val vm = vm()
        vm.start(policy(3))
        epoch(null); epoch(::etcActive); epoch(null)
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        assertNull(vm.positionReference.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(100.0 - (1.8 + ExternalReceiverProfile.REACH_RS2_PLUS.phaseCenterOffsetM!!), c.altitude, 1e-9)
        assertEquals("external_gnss", c.captureMethod)
    }
}
