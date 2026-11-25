package app.surrealar.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import app.surrealar.receiver.tilt.TiltTestNmea
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.util.diagnostics.CaptureInterruptions
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
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
import org.mockito.Mockito

/**
 * Phase 5 capture safety (plain JVM, same set-up as the Phase 4 capture tests, RS2+ so no tilt rules):
 * - [ActiveCaptureTracker] is set while an occupation runs and cleared on complete, cancel, interruption and
 *   onCleared;
 * - a receiver PORT change during an external occupation stops it like a host change (nothing saved, Restart works).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureReceiverSwitchJvmTest {

    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 64)
    private val sourceSettings = SourceSettings(MutableStateFlow(ProviderChoice.EXTERNAL_TCP), MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()
    private val feedDatum = ReceiverFeedDatum(MutableStateFlow(ReceiverDatum.ITRF2014))
    private val host = MutableStateFlow<String?>("192.168.42.1")
    private val port = MutableStateFlow<Int?>(9001)
    private val tracker = ActiveCaptureTracker()
    private var sod = 61_200.0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CaptureInterruptions.reset()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(MutableStateFlow(CoordinateSystemSettings(externalReceiverDatum = ReceiverDatum.ITRF2014)))
        Mockito.`when`(it.externalReceiverProfile).thenReturn(MutableStateFlow(ExternalReceiverProfile.REACH_RS2_PLUS))
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(host)
        Mockito.`when`(it.externalTcpPort).thenReturn(port)
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(MutableStateFlow(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5)))
    }

    private fun vm() = CaptureViewModel(
        fixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also { Mockito.`when`(it.fixes).thenReturn(fixes) },
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = sourceSettings,
        settingsRepository = settings(),
        feedDatum = feedDatum,
        captureTracker = tracker,
    )

    private fun policy(minSamples: Int) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = 100, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30,
    )

    private suspend fun epoch() {
        sod += 0.2
        fixes.emit(feedDatum.normalise(TiltTestNmea.fix(sod)))
    }

    private fun completed(vm: CaptureViewModel) =
        (vm.state.value as? ObservationSession.State.Complete)?.takeIf { it.requirementsMet } != null

    // ── ActiveCaptureTracker ─────────────────────────────────────────────────

    @Test fun trackerOwners_areIndependent() {
        val t = ActiveCaptureTracker()
        val a = Any(); val b = Any()
        assertFalse(t.active.value)
        t.begin(a); t.begin(b)
        t.end(a)
        assertTrue("b still capturing", t.active.value)
        t.end(b)
        assertFalse(t.active.value)
        t.end(b)   // no-op
        assertFalse(t.active.value)
    }

    @Test fun tracker_setWhileRunning_clearedOnComplete() = runBlocking {
        val vm = vm()
        vm.start(policy(2))
        assertTrue(tracker.active.value)
        repeat(2) { epoch() }
        assertTrue(completed(vm))
        assertFalse("a completed occupation is over", tracker.active.value)
    }

    @Test fun tracker_clearedOnCancel() {
        val vm = vm()
        vm.start(policy(5))
        assertTrue(tracker.active.value)
        vm.cancel()
        assertFalse(tracker.active.value)
    }

    @Test fun tracker_clearedWhenTheViewModelIsCleared() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm() as T
        })[CaptureViewModel::class.java]
        vm.start(policy(5))
        assertTrue(tracker.active.value)
        store.clear()                       // dialog dismissed: onCleared
        assertFalse(tracker.active.value)
    }

    // ── port watcher ─────────────────────────────────────────────────────────

    @Test fun portChange_midOccupation_stops_andRestartCompletesAndSaves() = runBlocking {
        val vm = vm()
        vm.start(policy(3))
        repeat(2) { epoch() }
        port.value = 9002
        assertEquals(CaptureViewModel.RECEIVER_CHANGED_MESSAGE, vm.interruption.value)
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertEquals(1, CaptureInterruptions.snapshot().counts[CaptureViewModel.INTERRUPT_RECEIVER])
        assertFalse("interrupted: no capture running", tracker.active.value)
        vm.save("X", null, 0, "ic_pin")
        assertTrue("an interrupted capture is never saved", repo.store.isEmpty())

        vm.start(policy(2))
        assertNull(vm.interruption.value)
        repeat(2) { epoch() }
        assertTrue(completed(vm))
        vm.save("R", null, 0, "ic_pin")
        assertEquals(1, repo.store.size)
    }

    @Test fun portChange_afterCompletion_keepsTheResult() = runBlocking {
        val vm = vm()
        vm.start(policy(2))
        repeat(2) { epoch() }
        assertTrue(completed(vm))
        port.value = 9002
        assertNull(vm.interruption.value)
        assertTrue(completed(vm))
    }
}
