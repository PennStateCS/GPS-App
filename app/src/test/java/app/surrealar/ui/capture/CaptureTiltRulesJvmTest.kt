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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private suspend fun epoch(etc: ((Double) -> String)?, station: String? = null, stepS: Double = 0.2) {
        sod += stepS; now += Math.round(stepS * 1000)
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
        vm.start(policy(8))
        epoch(::etcActive, station = "0101")
        epoch(::etcActive, station = null)
        assertNull(vm.interruption.value)
        epoch(::etcActive, station = "0202")
        epoch(::etcActive, station = "0202")
        assertNull("a new id must persist before it counts as a base change", vm.interruption.value)
        assertEquals("its epochs are not averaged meanwhile", 2, samples(vm))
        epoch(::etcActive, station = "0202")   // third consecutive epoch with the new id
        assertEquals(CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE, vm.interruption.value)
    }

    // ── correction base id flicker (all receivers; RS2+ shown) ──────────────

    @Test fun rs2Plus_emptyAndAllZeroStationIds_neverStop() = runBlocking {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val vm = vm()
        vm.start(policy(6))
        epoch(null, station = "0101")
        epoch(null, station = "0000"); epoch(null, station = "0"); epoch(null, station = "")
        epoch(null, station = "  "); epoch(null, station = "0000")
        assertNull(vm.interruption.value)
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
    }

    @Test fun rs2Plus_transientStationId_isIgnored_andItsEpochsAreNotAveraged() = runBlocking {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val vm = vm()
        vm.start(policy(10))
        epoch(null, station = "0101"); epoch(null, station = "0101")
        epoch(null, station = "0202"); epoch(null, station = "0202")   // two epochs of another id, then back
        epoch(null, station = "0101")
        epoch(null, station = "0303")                                  // a different one-epoch flicker
        epoch(null, station = "0101")
        assertNull(vm.interruption.value)
        assertEquals(4, samples(vm))
    }

    @Test fun rs2Plus_realBaseChange_stillStops() = runBlocking {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val vm = vm()
        vm.start(policy(10))
        repeat(2) { epoch(null, station = "0101") }
        repeat(2) { epoch(null, station = "0202") }
        assertNull(vm.interruption.value)
        epoch(null, station = "0202")
        assertEquals(CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE, vm.interruption.value)
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test fun rs2Plus_baseChangeAt1Hz_stopsAfterOneSecond() = runBlocking {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val vm = vm()
        vm.start(policy(10))
        epoch(null, station = "0101", stepS = 1.0)
        epoch(null, station = "0202", stepS = 1.0)
        assertNull(vm.interruption.value)
        epoch(null, station = "0202", stepS = 1.0)   // the new id has now lasted 1 s
        assertEquals(CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE, vm.interruption.value)
    }

    @Test fun stationIdNormalisation_ignoresEmptyAndAllZero() {
        assertNull(CaptureViewModel.normalizedStationId(null))
        assertNull(CaptureViewModel.normalizedStationId("0"))
        assertNull(CaptureViewModel.normalizedStationId("0000"))
        assertNull(CaptureViewModel.normalizedStationId(" "))
        assertEquals("0101", CaptureViewModel.normalizedStationId(" 0101 "))
        assertEquals("1000", CaptureViewModel.normalizedStationId("1000"))
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

    // ── no needless ETC waits ────────────────────────────────────────────────

    /** A monitor that waits up to 1 s for an epoch's ETC (as in the app); the test dispatchers never advance time. */
    private fun waitingMonitor() {
        monitor = ReceiverTiltMonitor(monitorScope, profile, output, firmware, MutableStateFlow(0), { now },
            etcWaitMs = 1_000, tickMs = null)
    }

    @Test fun rs2Plus_andOutputDisabled_neverWaitForAnEtc() = runBlocking {
        waitingMonitor()
        monitor.onLine(etcOff(sod))                    // ETC stream live, but the epochs below have none (NOT_MATCHED)
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val rs2 = vm()
        rs2.start(policy(2))
        repeat(2) { epoch(null) }
        assertTrue("RS2+ epochs are admitted at once", (rs2.state.value as ObservationSession.State.Complete).requirementsMet)

        profile.value = ExternalReceiverProfile.REACH_RS4
        output.value = CompensatedOutputSetting.DISABLED
        monitor.onLine(etcOff(sod))
        val rs4 = vm()
        rs4.start(policy(2))
        repeat(2) { epoch(null) }
        assertTrue("output Disabled is the antenna whatever the ETC: no wait", (rs4.state.value as ObservationSession.State.Complete).requirementsMet)
        assertEquals(EpochReference.ANTENNA, rs4.positionReference.value)
    }

    // ── unreadable profile, RS4 output-unknown warning ───────────────────────

    private fun unreadableProfileVm(): CaptureViewModel {
        val s = settings()
        Mockito.`when`(s.externalReceiverProfile).thenReturn(kotlinx.coroutines.flow.flow { throw java.io.IOException("datastore") })
        return CaptureViewModel(
            fixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also { Mockito.`when`(it.fixes).thenReturn(fixes) },
            captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
            sourceSettings = sourceSettings, settingsRepository = s, feedDatum = feedDatum, tiltMonitor = monitor,
        )
    }

    @Test fun unreadableProfile_withTiltOn_isNeverAveraged_orPoleTip() = runBlocking {
        val vm = unreadableProfileVm()
        vm.start(policy(2))
        repeat(3) { epoch(::etcActive) }
        assertEquals("possibly compensated epochs are excluded, not passed through", 0, samples(vm))
        assertNull("never a pole tip", vm.positionReference.value)
        assertEquals(CaptureViewModel.PROFILE_UNREADABLE_WARNING, vm.referenceWarning.value)
    }

    @Test fun unreadableProfile_withoutEtc_isAConventionalCapture_noWarning_noAcknowledgment() = runBlocking {
        // Product decision 2026-11-25: a conventional (RS2+-like) stream with no ETC is not uncertain merely because
        // the profile could not be read: today's reduction, no warning, saved without acknowledgment.
        val vm = unreadableProfileVm()
        vm.start(policy(2))
        repeat(2) { epoch(null) }
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        assertNull(vm.referenceWarning.value)
        assertFalse(vm.referenceAcknowledgmentNeeded())
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertNotEquals(CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue, c.captureMethod)
        assertNotEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue, c.captureMethod)
        assertTrue("an antenna reduction was applied", c.altitude < 100.0)
    }

    @Test fun unreadableProfile_withEtcTiltOff_isAntenna_noAcknowledgment() = runBlocking {
        // The receiver reports its tilt engine OFF: it is not compensating, so the antenna reference is certain.
        val vm = unreadableProfileVm()
        vm.start(policy(2))
        repeat(2) { epoch(::etcOff) }
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        assertFalse(vm.referenceAcknowledgmentNeeded())
    }

    @Test fun outputUnknownWarning_onlyForRs4AndRs4Pro_withTheSettingUnknown() = runBlocking {
        output.value = CompensatedOutputSetting.UNKNOWN
        val rs4 = vm()
        rs4.start(policy(2))
        assertEquals(CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, rs4.referenceWarning.value)
        repeat(2) { epoch(null) }                      // no ETC: antenna (uncertain), today's maths, saving allowed
        assertTrue((rs4.state.value as ObservationSession.State.Complete).requirementsMet)
        assertEquals("the warning stays with the completed result", CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, rs4.referenceWarning.value)
        rs4.acknowledgeUnconfirmedReference()   // saving needs the acknowledgment (Phase 4 stabilization WP A)
        rs4.save("P", null, 0, "ic_pin")
        assertEquals(100.0 - (1.8 + ExternalReceiverProfile.REACH_RS4.phaseCenterOffsetM!!), repo.store.single().altitude, 1e-9)

        profile.value = ExternalReceiverProfile.REACH_RS4_PRO
        val pro = vm()
        pro.start(policy(2))
        assertEquals(CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, pro.referenceWarning.value)

        output.value = CompensatedOutputSetting.ENABLED
        val enabled = vm(); enabled.start(policy(2))
        assertNull(enabled.referenceWarning.value)
        output.value = CompensatedOutputSetting.DISABLED
        val disabled = vm(); disabled.start(policy(2))
        assertNull(disabled.referenceWarning.value)

        output.value = CompensatedOutputSetting.UNKNOWN
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val rs2 = vm(); rs2.start(policy(2))
        assertNull(rs2.referenceWarning.value)
        profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        val generic = vm(); generic.start(policy(2))
        assertNull(generic.referenceWarning.value)
        val internal = vm(); internal.start(policy(2), internal = true)
        assertNull(internal.referenceWarning.value)
    }

    // ── firmware flicker ─────────────────────────────────────────────────────

    @Test fun firmwareUnknownMidOccupation_doesNotStopAPoleTipOccupation() = runBlocking {
        val vm = vm()
        vm.start(policy(4))
        repeat(2) { epoch(::etcActive) }
        firmware.value = null                          // a device-info poll failed
        repeat(2) { epoch(::etcActive) }
        assertNull(vm.interruption.value)
        assertTrue((vm.state.value as ObservationSession.State.Complete).requirementsMet)
        assertEquals(EpochReference.POLE_TIP, vm.positionReference.value)
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
