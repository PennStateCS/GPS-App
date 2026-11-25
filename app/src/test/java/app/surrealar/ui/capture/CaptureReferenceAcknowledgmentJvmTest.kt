package app.surrealar.ui.capture

import app.surrealar.domain.model.CaptureMethod
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.PositionReferenceKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.Locale

/**
 * Phase 4 stabilization WP A (plain JVM, same set-up as [CaptureTiltRulesJvmTest]):
 *  - a capture with a genuinely unconfirmed position reference (RS4 / RS4 Pro with "Compensated NMEA output" Unknown
 *    and an averaged epoch of uncertain reference, or an unreadable profile whose stream carried ETC) is never saved without an explicit acknowledgment; once acknowledged (for that occupation
 *    only) it is saved as EXTERNAL_GNSS_REFERENCE_UNCONFIRMED with today's antenna reduction (no second reduction,
 *    no maths change);
 *  - verified settings (Enabled / Disabled) and RS2+ / generic / internal captures never ask;
 *  - the interruption / Restart workflow for datum, profile, host, base station, firmware and compensation-setting
 *    changes: specific actionable messages, completed results never discarded, Restart works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureReferenceAcknowledgmentJvmTest {

    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 64)
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()
    private val feedDatumFlow = MutableStateFlow(ReceiverDatum.ITRF2014)
    private val feedDatum = ReceiverFeedDatum(feedDatumFlow)

    private val profile = MutableStateFlow(ExternalReceiverProfile.REACH_RS4)
    private val output = MutableStateFlow(CompensatedOutputSetting.UNKNOWN)
    private val receiver = MutableStateFlow(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5))
    private val host = MutableStateFlow<String?>("192.168.42.1")
    private val firmware = MutableStateFlow<String?>("34.10")
    private val coordinateSystem = MutableStateFlow(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014))

    private var now = 5_000_000L
    private var sod = 61_200.0
    private lateinit var monitorScope: CoroutineScope
    private lateinit var monitor: ReceiverTiltMonitor

    private val rs4Offset = 1.8 + ExternalReceiverProfile.REACH_RS4.phaseCenterOffsetM!!

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
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(coordinateSystem)
        Mockito.`when`(it.externalReceiverProfile).thenReturn(profile)
        Mockito.`when`(it.compensatedNmeaOutput).thenReturn(output)
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(host)
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(receiver)
    }

    private fun vm(settingsRepo: SettingsRepository = settings()) = CaptureViewModel(
        fixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also { Mockito.`when`(it.fixes).thenReturn(fixes) },
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = sourceSettings,
        settingsRepository = settingsRepo,
        feedDatum = feedDatum,
        tiltMonitor = monitor,
    )

    private fun policy(minSamples: Int) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = 100, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30,
    )

    private suspend fun epoch(etc: ((Double) -> String)?, station: String? = null) {
        sod += 0.2; now += 200
        etc?.let { monitor.onLine(it(sod)) }
        fixes.emit(feedDatum.normalise(TiltTestNmea.fix(sod, station = station)))
    }

    private fun completed(vm: CaptureViewModel) =
        (vm.state.value as? ObservationSession.State.Complete)?.takeIf { it.requirementsMet } != null

    // ── acknowledgment before saving ─────────────────────────────────────────

    @Test fun unknownOutput_saveIsRefusedUntilAcknowledged_thenSavedAsUnconfirmed_withTheAntennaReductionOnce() = runBlocking {
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch(null) }
        assertTrue(completed(vm))
        assertEquals(CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, vm.unconfirmedReferenceWarning())
        assertTrue(vm.referenceAcknowledgmentNeeded())

        vm.save("P", null, 0, "ic_pin")
        assertTrue("never saved without the acknowledgment", repo.store.isEmpty())
        assertEquals(CaptureViewModel.REFERENCE_ACK_REQUIRED_ERROR, vm.saveError.value)
        assertTrue("the completed result is kept for the user's choice", completed(vm))

        vm.acknowledgeUnconfirmedReference()
        assertFalse(vm.referenceAcknowledgmentNeeded())
        assertNull(vm.saveError.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue, c.captureMethod)
        assertEquals("today's antenna reduction, applied once", 100.0 - rs4Offset, c.altitude, 1e-9)
        assertEquals(rs4Offset, c.antennaHeightM!!, 1e-12)
        assertEquals("external-tcp provider: list badges / filters treat it as any external capture",
            "Emlid Reach RS4", c.sourceDevice)
        assertEquals(PositionReferenceKind.UNCONFIRMED,
            PositionReferenceKind.of(c.captureMethod, app.surrealar.data.local.db.ProviderTokens.isExternal(c.provider)))
    }

    @Test fun unknownOutput_butReceiverReportsTiltOff_isAntenna_noAcknowledgment() = runBlocking {
        // Product decision 2026-11-25: only genuine uncertainty asks. ETC "tilt off" means the receiver is not
        // compensating, so the epochs are the antenna (today's reduction) and the standing setting hint stays.
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch(::etcOff) }
        assertTrue(completed(vm))
        assertNull(vm.unconfirmedReferenceWarning())
        assertFalse(vm.referenceAcknowledgmentNeeded())
        assertEquals(CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, vm.referenceWarning.value)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertNotEquals(CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue, c.captureMethod)
        assertEquals("today's antenna reduction, applied once", 100.0 - rs4Offset, c.altitude, 1e-9)
    }

    @Test fun acknowledgment_isRememberedForTheOccupationOnly() = runBlocking {
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch(null) }
        vm.acknowledgeUnconfirmedReference()
        assertFalse(vm.referenceAcknowledgmentNeeded())
        // A new occupation (Restart / next point) asks again.
        vm.start(policy(2))
        repeat(2) { epoch(null) }
        assertTrue(vm.referenceAcknowledgmentNeeded())
        vm.save("P2", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test fun verifiedSettings_andOtherReceivers_neverAsk() = runBlocking {
        // Enabled + tilt active: confirmed receiver pole tip, no reduction.
        output.value = CompensatedOutputSetting.ENABLED
        val tip = vm(); tip.start(policy(2)); repeat(2) { epoch(::etcActive) }
        assertEquals(EpochReference.POLE_TIP, tip.positionReference.value)
        assertNull(tip.unconfirmedReferenceWarning())
        assertFalse(tip.referenceAcknowledgmentNeeded())
        tip.save("T", null, 0, "ic_pin")
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue, repo.store.last().captureMethod)
        assertNull(repo.store.last().antennaHeightM)

        // Disabled: confirmed antenna.
        output.value = CompensatedOutputSetting.DISABLED
        val ant = vm(); ant.start(policy(2)); repeat(2) { epoch(::etcActive) }
        assertFalse(ant.referenceAcknowledgmentNeeded())
        ant.save("A", null, 0, "ic_pin")
        assertEquals("external_gnss", repo.store.last().captureMethod)
        assertEquals(100.0 - rs4Offset, repo.store.last().altitude, 1e-9)

        // RS2+ / generic with the setting Unknown: not applicable, unchanged.
        output.value = CompensatedOutputSetting.UNKNOWN
        for (p in listOf(ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.GENERIC_NMEA_TCP)) {
            profile.value = p
            val v = vm(); v.start(policy(2)); repeat(2) { epoch(null) }
            assertFalse(p.name, v.referenceAcknowledgmentNeeded())
            v.save(p.name, null, 0, "ic_pin")
            assertEquals(p.name, "external_gnss", repo.store.last().captureMethod)
        }
        assertEquals(4, repo.store.size)
    }

    @Test fun internalCapture_neverAsks() = runBlocking {
        val vm = vm()
        vm.start(policy(2), internal = true)
        assertNull(vm.unconfirmedReferenceWarning())
        assertFalse(vm.referenceAcknowledgmentNeeded())
    }

    @Test fun verifiedSettingChangedToUnknown_nextOccupationAsksAgain() = runBlocking {
        output.value = CompensatedOutputSetting.DISABLED
        val vm = vm()
        vm.start(policy(2)); repeat(2) { epoch(null) }
        assertFalse(vm.referenceAcknowledgmentNeeded())
        // The completed result keeps the setting it was captured with: still no acknowledgment, saved as antenna.
        output.value = CompensatedOutputSetting.UNKNOWN
        assertTrue(completed(vm))
        assertFalse(vm.referenceAcknowledgmentNeeded())
        vm.save("A", null, 0, "ic_pin")
        assertEquals("external_gnss", repo.store.single().captureMethod)
        // The next occupation runs under Unknown: acknowledgment needed.
        vm.start(policy(2)); repeat(2) { epoch(null) }
        assertTrue(vm.referenceAcknowledgmentNeeded())
    }

    @Test fun unknownOutput_withTiltOn_isStillRefused_notSavableByAcknowledgment() = runBlocking {
        val vm = vm()
        vm.referenceNoValidEpochsMs = 0
        vm.start(policy(2))
        epoch(::etcActive)
        assertEquals(CaptureViewModel.MAY_BE_COMPENSATED_MESSAGE, vm.interruption.value)
        vm.acknowledgeUnconfirmedReference()
        vm.save("P", null, 0, "ic_pin")
        assertTrue("possibly compensated epochs are never averaged or saved", repo.store.isEmpty())
    }

    @Test fun ackMessage_explainsTheDoubleReduction_withoutClaimingAccuracy() {
        for (w in listOf(CaptureViewModel.RS4_OUTPUT_UNKNOWN_WARNING, CaptureViewModel.PROFILE_UNREADABLE_WARNING)) {
            val m = CaptureViewModel.referenceAckMessage(w)
            assertTrue(m, m.contains("reduced twice"))
            assertTrue(m, m.contains("pole tip"))
            assertTrue(m, m.contains("Unconfirmed"))
            assertTrue(m, m.contains("Settings → Receiver"))
            val lower = m.lowercase(Locale.US)
            assertFalse(m, lower.contains("survey-grade") || lower.contains("verified") || lower.contains("centimet"))
        }
        assertTrue(CaptureViewModel.referenceAckMessage(CaptureViewModel.PROFILE_UNREADABLE_WARNING).contains("profile couldn't be read"))
    }

    // ── interruption / Restart workflow ──────────────────────────────────────

    private class Change(val name: String, val message: String, val code: String, val apply: () -> Unit, val undo: () -> Unit = {})

    /** Every interpretation-changing setup change: [CaptureViewModel] message + diagnostics code. */
    private fun changes(): List<Change> = listOf(
        Change("datum", CaptureViewModel.DATUM_CHANGED_MESSAGE, CaptureViewModel.INTERRUPT_DATUM, {
            coordinateSystem.value = CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.NAD83_2011)
            feedDatumFlow.value = ReceiverDatum.NAD83_2011
        }),
        Change("profile", CaptureViewModel.PROFILE_CHANGED_MESSAGE, CaptureViewModel.INTERRUPT_PROFILE,
            { profile.value = ExternalReceiverProfile.REACH_RS4_PRO }),
        Change("host", CaptureViewModel.RECEIVER_CHANGED_MESSAGE, CaptureViewModel.INTERRUPT_RECEIVER,
            { host.value = "192.168.42.7" }),
        Change("compensated output", CaptureViewModel.OUTPUT_SETTING_CHANGED_MESSAGE, CaptureViewModel.INTERRUPT_OUTPUT_SETTING,
            { output.value = CompensatedOutputSetting.DISABLED }),
    )

    private fun resetSetup() {
        coordinateSystem.value = CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014)
        feedDatumFlow.value = ReceiverDatum.ITRF2014
        profile.value = ExternalReceiverProfile.REACH_RS4
        host.value = "192.168.42.1"
        output.value = CompensatedOutputSetting.ENABLED
        firmware.value = "34.10"
    }

    @Test fun eachSetupChange_midOccupation_stopsWithItsOwnMessage_andRestartCompletesAndSaves() = runBlocking {
        for (ch in changes()) {
            resetSetup()
            CaptureInterruptions.reset()
            val before = repo.store.size
            val vm = vm()
            vm.start(policy(3))
            repeat(2) { epoch(::etcActive) }
            ch.apply()
            assertEquals(ch.name, ch.message, vm.interruption.value)
            assertEquals(ch.name, ObservationSession.State.Idle, vm.state.value)
            assertEquals(ch.name, 1, CaptureInterruptions.snapshot().counts[ch.code])
            vm.save("X", null, 0, "ic_pin")
            assertEquals("${ch.name}: an interrupted capture is never saved", before, repo.store.size)

            // Restart (the dialog's Restart capture button calls start again with the same policy).
            vm.start(policy(2))
            assertNull(ch.name, vm.interruption.value)
            val disabled = output.value == CompensatedOutputSetting.DISABLED
            repeat(2) { epoch(etc = { t: Double -> if (disabled) etcOff(t) else etcActive(t) }) }
            assertTrue("${ch.name}: restart completes", completed(vm))
            assertFalse("${ch.name}: verified setting, no acknowledgment", vm.referenceAcknowledgmentNeeded())
            vm.save("R", null, 0, "ic_pin")
            assertEquals("${ch.name}: restart saves", before + 1, repo.store.size)
        }
    }

    @Test fun correctionBaseChange_midOccupation_stops_andRestartWorks() = runBlocking {
        resetSetup()
        val vm = vm()
        vm.start(policy(6))
        repeat(2) { epoch(::etcActive, station = "0101") }
        repeat(CaptureViewModel.STATION_CHANGE_MIN_EPOCHS) { epoch(::etcActive, station = "0202") }
        assertEquals(CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE, vm.interruption.value)
        vm.start(policy(2))
        assertNull(vm.interruption.value)
        repeat(2) { epoch(::etcActive, station = "0202") }
        assertTrue(completed(vm))
        vm.save("R", null, 0, "ic_pin")
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue, repo.store.single().captureMethod)
    }

    @Test fun firmwareLost_duringAPoleTipOccupation_stopsWithTheFirmwareMessage_andRestartWorks() = runBlocking {
        resetSetup()
        firmware.value = null                      // not known when the occupation starts
        val vm = vm()
        vm.start(policy(4))
        firmware.value = "34.10"                   // device info arrives: pole tip confirmed
        repeat(2) { epoch(::etcActive) }
        assertEquals(EpochReference.POLE_TIP, vm.positionReference.value)
        firmware.value = null                      // and is lost again
        epoch(::etcActive)
        assertEquals(CaptureViewModel.FIRMWARE_UNKNOWN_MESSAGE, vm.interruption.value)
        assertTrue(repo.store.isEmpty())
        firmware.value = "34.10"
        vm.start(policy(2))
        repeat(2) { epoch(::etcActive) }
        assertTrue(completed(vm))
        vm.save("R", null, 0, "ic_pin")
        assertEquals(1, repo.store.size)
    }

    @Test fun completedResult_isNeverDiscarded_byAnyLaterSetupChange() = runBlocking {
        for (ch in changes()) {
            resetSetup()
            val before = repo.store.size
            val vm = vm()
            vm.start(policy(2))
            repeat(2) { epoch(::etcActive) }
            assertTrue(ch.name, completed(vm))
            ch.apply()
            epoch(::etcActive, station = "0909")
            assertNull(ch.name, vm.interruption.value)
            assertTrue("${ch.name}: completed result kept", completed(vm))
            vm.save("C", null, 0, "ic_pin")
            assertEquals(ch.name, before + 1, repo.store.size)
            assertEquals("${ch.name}: saved with the start settings", CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue,
                repo.store.last().captureMethod)
        }
    }

    @Test fun interruptionMessages_areSpecificAndActionable() {
        val messages = listOf(
            CaptureViewModel.DATUM_CHANGED_MESSAGE, CaptureViewModel.PROFILE_CHANGED_MESSAGE,
            CaptureViewModel.RECEIVER_CHANGED_MESSAGE, CaptureViewModel.CORRECTION_BASE_CHANGED_MESSAGE,
            CaptureViewModel.FIRMWARE_UNKNOWN_MESSAGE, CaptureViewModel.OUTPUT_SETTING_CHANGED_MESSAGE,
            CaptureViewModel.REFERENCE_CHANGED_MESSAGE, CaptureViewModel.TILT_LOST_MESSAGE,
        )
        assertEquals("each change has its own message", messages.size, messages.toSet().size)
        for (m in messages) {
            val lower = m.lowercase(Locale.US)
            assertTrue(m, lower.startsWith("capture stopped:"))
            assertTrue(m, lower.contains("restart") || lower.contains("again"))
        }
        assertNotNull(CaptureViewModel.MAY_BE_COMPENSATED_MESSAGE.takeIf { it.contains("Settings → Receiver") })
    }
}
