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
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.time.Instant

/**
 * Plain-JVM (no Robolectric) tests for [CaptureViewModel]: start/stop/cancel of the averaging
 * session, the provider filter (REPLAY and other-mode fixes are never averaged, nor the fix replayed
 * from before the session), saved-policy start, timed-out completions and every save branch.
 * Running without Robolectric keeps the class visible to JaCoCo's on-the-fly agent.
 * android.util.Log calls return defaults (unitTests.isReturnDefaultValues).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelJvmTest {

    /** replay = 1 like FixSwitchboard.fixes, so the "replayed before start" filter is exercised. */
    private val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 64)
    private val providerFlow = MutableStateFlow(ProviderChoice.EXTERNAL_TCP)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val repo = FakeCoordinateRepository()

    /** The feed's receiver datum; [push] normalises through it like the switchboard does. */
    private val feedDatumFlow = MutableStateFlow(app.surrealar.settings.model.ReceiverDatum.ITRF2014)
    private val feedDatum = app.surrealar.gnss.source.ReceiverFeedDatum(feedDatumFlow)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settings(
        source: LocationSourceType = LocationSourceType.EXTERNAL,
        sourceFlow: Flow<LocationSourceType> = flowOf(source),
        receiver: Flow<GnssReceiverSettings> =
            flowOf(GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5)),
        profile: Flow<ExternalReceiverProfile> = flowOf(ExternalReceiverProfile.REACH_RS2_PLUS),
        capture: Flow<GnssCaptureSettings>? = null,
        coordinateSystem: Flow<app.surrealar.settings.model.CoordinateSystemSettings>? = null,
    ): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        if (coordinateSystem != null) Mockito.`when`(it.coordinateSystemSettings).thenReturn(coordinateSystem)
        Mockito.`when`(it.externalReceiverProfile).thenReturn(profile)
        Mockito.`when`(it.locationSource).thenReturn(sourceFlow)
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf("Reach"))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf("192.168.42.1"))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(receiver)
        if (capture != null) Mockito.`when`(it.gnssCaptureSettings).thenReturn(capture)
    }

    private fun switchboard(): FixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also {
        Mockito.`when`(it.fixes).thenReturn(fixes)
    }

    private fun vm(settingsRepo: SettingsRepository = settings()) = CaptureViewModel(
        fixSwitchboard = switchboard(),
        captureCoordinate = CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase()),
        sourceSettings = sourceSettings,
        settingsRepository = settingsRepo,
        feedDatum = feedDatum,
    )

    private fun fix(provider: Provider = Provider.EXTERNAL_TCP, rtk: RtkStatus = RtkStatus.FIX, alt: Double = 100.0) = Fix(
        provider = provider, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = alt, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0
    )

    private fun policy(maxDur: Int = 100, minSamples: Int = 2, minStatus: RtkStatus = RtkStatus.FIX) = AveragingPolicy(
        minDurationSec = 0, maxDurationSec = maxDur, minSamples = minSamples,
        requiredMinStatus = minStatus, maxFixAgeSec = 30, maxDiffAgeSec = 30
    )

    /** Publishes [f] as the switchboard does: normalised (and datum-tagged) by the feed first. */
    private suspend fun push(f: Fix) = fixes.emit(feedDatum.normalise(f))

    private suspend fun complete(vm: CaptureViewModel, internal: Boolean = false, alt: Double = 100.0) {
        val p = if (internal) Provider.INTERNAL else Provider.EXTERNAL_TCP
        vm.start(policy(minStatus = RtkStatus.SINGLE), internal)
        push(fix(p, alt = alt)); push(fix(p, alt = alt))
        val s = vm.state.value
        assertTrue("expected Complete, was $s", s is ObservationSession.State.Complete && s.requirementsMet)
    }

    // ── start / filter ───────────────────────────────────────────────────────

    @Test fun initialState_isIdle_andNoSaveError() {
        val vm = vm()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        assertNull(vm.saveError.value)
    }

    @Test fun replayFixes_areNeverAveraged() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 2))
        repeat(5) { push(fix(Provider.REPLAY)) }
        assertFalse(vm.state.value is ObservationSession.State.Complete)
        push(fix()); push(fix())
        assertEquals(2, (vm.state.value as ObservationSession.State.Complete).result.samples)
    }

    @Test fun fixReplayedFromBeforeStart_isSkipped() = runBlocking {
        val vm = vm()
        push(fix())                     // already in the replay cache when the capture starts
        vm.start(policy(minSamples = 1))
        assertFalse("the pre-start fix must not count", vm.state.value is ObservationSession.State.Complete)
        push(fix())
        assertEquals(1, (vm.state.value as ObservationSession.State.Complete).result.samples)
    }

    @Test fun internalMode_ignoresExternalFixes() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL))
        vm.start(policy(minSamples = 1, minStatus = RtkStatus.SINGLE), internal = true)
        repeat(3) { push(fix(Provider.EXTERNAL_TCP)) }
        assertFalse(vm.state.value is ObservationSession.State.Complete)
        push(fix(Provider.INTERNAL, rtk = RtkStatus.SINGLE))
        assertTrue(vm.state.value is ObservationSession.State.Complete)
    }

    @Test fun belowRequiredStatus_isNotAveraged() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 1, minStatus = RtkStatus.FIX))
        repeat(3) { push(fix(rtk = RtkStatus.SINGLE)) }
        assertFalse(vm.state.value is ObservationSession.State.Complete)
        push(fix(rtk = RtkStatus.FIX))
        assertTrue(vm.state.value is ObservationSession.State.Complete)
    }

    @Test fun startingAgain_cancelsThePreviousSession() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 3))
        push(fix())
        assertTrue(vm.state.value is ObservationSession.State.Capturing)
        vm.start(policy(minSamples = 2))
        // The new session starts from zero: one more fix is not enough, two are.
        push(fix())
        assertFalse(vm.state.value is ObservationSession.State.Complete)
        push(fix())
        assertEquals(2, (vm.state.value as ObservationSession.State.Complete).result.samples)
    }

    @Test fun startWithSavedPolicy_usesSavedSettings() = runBlocking {
        val saved = GnssCaptureSettings(minDurationSec = 1, maxDurationSec = 1, minSamples = 1)
        val vm = vm(settings(capture = flowOf(saved)))
        vm.startWithSavedPolicy()
        assertEquals("Idle until the first accepted fix", ObservationSession.State.Idle, vm.state.value)
        push(fix())
        assertTrue("was ${vm.state.value}", vm.state.value is ObservationSession.State.Capturing)
        vm.cancel()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    @Test fun startWithSavedPolicy_unreadableSettings_fallsBackToDefaults() = runBlocking {
        val vm = vm(settings(capture = flow { throw java.io.IOException("datastore") }))
        vm.startWithSavedPolicy()
        push(fix())
        assertTrue("a failed read still starts a capture with the defaults", vm.state.value is ObservationSession.State.Capturing)
        vm.cancel()
    }

    @Test fun startInternal_startsAnInternalCapture() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL, capture = flowOf(GnssCaptureSettings())))
        vm.startInternal()
        push(fix(Provider.EXTERNAL_TCP))
        assertEquals("external fixes are ignored by an internal capture", ObservationSession.State.Idle, vm.state.value)
        push(fix(Provider.INTERNAL))
        assertTrue("was ${vm.state.value}", vm.state.value is ObservationSession.State.Capturing)
        vm.cancel()
    }

    // ── provider switch / cancel ─────────────────────────────────────────────

    @Test fun providerSwitch_withoutSession_keepsIdle() {
        val vm = vm()
        providerFlow.value = ProviderChoice.INTERNAL
        providerFlow.value = ProviderChoice.REPLAY
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    @Test fun providerSwitch_toReplay_cancelsRunningCapture() = runBlocking {
        val vm = vm()
        vm.start(policy(minSamples = 10))
        push(fix())
        providerFlow.value = ProviderChoice.REPLAY
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        push(fix())
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    @Test fun providerSwitch_beforeFirstAcceptedFix_keepsCapture_noMessage() = runBlocking {
        // e.g. the dialog started an external capture while the receiver was still connecting: the
        // Internal -> External flip on connect must not stop it (nothing has been averaged yet).
        providerFlow.value = ProviderChoice.INTERNAL
        val vm = vm()
        vm.start(policy(minSamples = 10))
        providerFlow.value = ProviderChoice.EXTERNAL_TCP
        assertNull(vm.interruption.value)
        push(fix())
        val st = vm.state.value
        assertTrue("still capturing: $st", st is ObservationSession.State.Capturing)
        assertEquals(1, (st as ObservationSession.State.Capturing).samples)
    }

    @Test fun providerSwitch_afterCompletion_keepsResult_andItCanBeSaved() = runBlocking {
        val vm = vm()
        complete(vm)
        providerFlow.value = ProviderChoice.INTERNAL
        assertTrue(vm.state.value is ObservationSession.State.Complete)
        assertNull(vm.interruption.value)
        vm.save("Pt", null, 0, "ic_pin")
        assertEquals(1, repo.store.size)
    }

    @Test fun cancel_withoutSession_isHarmless() {
        val vm = vm()
        vm.cancel()
        vm.cancel()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    @Test fun cancel_afterCompletion_returnsToIdle_andNothingCanBeSaved() = runBlocking {
        val vm = vm()
        complete(vm)
        vm.cancel()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
        vm.save("Pt", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
    }

    @Test fun timedOutCapture_completesWithoutRequirements_andCancelAfterwardsIsIdle() = runBlocking {
        val vm = vm()
        vm.start(policy(maxDur = 0, minSamples = 5))
        push(fix())
        val s = vm.state.value as ObservationSession.State.Complete
        assertFalse(s.requirementsMet)
        vm.save("Pt", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
        vm.cancel()
        assertEquals(ObservationSession.State.Idle, vm.state.value)
    }

    @Test fun onCleared_stopsForwarding() = runBlocking {
        val store = ViewModelStore()
        val target = vm()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = target as T
        }
        val vm = ViewModelProvider(store, factory)[CaptureViewModel::class.java]
        vm.start(policy(minSamples = 10))
        push(fix())
        val before = vm.state.value
        store.clear()
        push(fix())
        assertEquals("no state is forwarded after onCleared", before, vm.state.value)
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test fun save_external_storesProvenanceAndProfileOffset() = runBlocking {
        val vm = vm()
        complete(vm)
        vm.save("Corner", "n", 3, "ic_pin")
        val c = repo.store.single()
        assertEquals("external_gnss", c.captureMethod)
        assertEquals("Emlid Reach RS2+", c.sourceDevice)
        assertEquals(98.066, c.altitude, 1e-9)
        assertNull(vm.saveError.value)
    }

    @Test fun save_external_unreadableProfile_usesGenericDeviceAndDefaultProfile() = runBlocking {
        val vm = vm(settings(profile = flow { throw java.io.IOException("profile") }))
        complete(vm)
        // Phase 4 stabilization WP A: an unreadable profile is an unconfirmed reference, saved only after acknowledgment.
        vm.save("P", null, 0, "ic_pin")
        assertTrue("not saved without acknowledgment", repo.store.isEmpty())
        vm.acknowledgeUnconfirmedReference()
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(app.surrealar.domain.model.CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue, c.captureMethod)
        assertEquals("External GNSS", c.sourceDevice)
        val expected = 100.0 - app.surrealar.settings.model.AntennaSetup.resolve(
            GnssReceiverSettings(poleHeightM = 1.8, internalAntennaHeightM = 0.5),
            ExternalReceiverProfile.DEFAULT
        ).captureOffsetM
        assertEquals(expected, c.altitude, 1e-9)
    }

    private fun nad83Setting() = flowOf(app.surrealar.settings.model.CoordinateSystemSettings(
        externalReceiverDatum = app.surrealar.settings.model.ReceiverDatum.NAD83_2011))

    @Test fun save_external_recordsReceiverFrameFromSetting_andFrameAndEpoch() = runBlocking {
        // The feed applies the same datum as the setting (as in the app), so the fixes are NAD83-normalised.
        feedDatumFlow.value = app.surrealar.settings.model.ReceiverDatum.NAD83_2011
        val vm = vm(settings(coordinateSystem = nad83Setting()))
        complete(vm)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals(app.surrealar.domain.model.FrameTokens.NAD83_2011, c.receiverFrame)
        assertEquals(app.surrealar.domain.model.FrameTokens.WGS84_AS_ITRF2014, c.sourceFrame)
        assertEquals(app.surrealar.domain.model.coordinateEpochOf(c.timestamp), c.coordinateEpoch!!, 0.0)
        assertNull("the test fixes carry no MSL height", c.orthometricHeightSource)
        assertNull(c.orthometricModel)
        // Values unchanged by provenance: the NAD83→WGS84-normalised fix height minus the RS2+ offset.
        val normalisedAlt = io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer.nad83ToWgs84(fix()).altEllipsoidalM!!
        assertEquals(normalisedAlt - 1.934, c.altitude, 1e-6)
    }

    @Test fun save_external_unreadableDatumSetting_leavesReceiverFrameUnknown() = runBlocking {
        val vm = vm(settings(coordinateSystem = flow { throw java.io.IOException("datastore") }))
        complete(vm)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertNull(c.receiverFrame)
        assertEquals(app.surrealar.domain.model.FrameTokens.WGS84_AS_ITRF2014, c.sourceFrame)
    }

    @Test fun save_internal_hasNoReceiverFrame_evenWithNad83Setting() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL, coordinateSystem = nad83Setting()))
        complete(vm, internal = true)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertNull(c.receiverFrame)
        assertEquals(app.surrealar.domain.model.FrameTokens.WGS84_AS_ITRF2014, c.sourceFrame)
    }

    @Test fun save_internal_unreadableReceiver_isBlockedWithError() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL, receiver = flow { throw java.io.IOException("x") }))
        complete(vm, internal = true)
        vm.save("P", null, 0, "ic_pin")
        assertTrue(repo.store.isEmpty())
        assertEquals(CaptureViewModel.ANTENNA_OFFSET_ERROR, vm.saveError.value)
    }

    @Test fun save_internal_storesInternalProvenance() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.INTERNAL))
        complete(vm, internal = true)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals("internal_gps", c.captureMethod)
        assertEquals("Internal GPS", c.sourceDevice)
        assertEquals(99.5, c.altitude, 1e-9)
    }

    @Test fun save_simulator_storesAveragedWithoutOffset() = runBlocking {
        val vm = vm(settings(source = LocationSourceType.SIMULATOR))
        complete(vm)
        vm.save("P", null, 0, "ic_pin")
        val c = repo.store.single()
        assertEquals("averaged", c.captureMethod)
        assertNull(c.sourceDevice)
        assertEquals(100.0, c.altitude, 1e-9)
    }

    @Test fun save_snapshotSourceUnreadable_readsTheSourceAgainAtSave() = runBlocking {
        var reads = 0
        val source = flow {
            if (reads++ == 0) throw java.io.IOException("first read fails")
            emit(LocationSourceType.INTERNAL)
        }
        val vm = vm(settings(sourceFlow = source))
        complete(vm, internal = true)
        assertNull(vm.settingsAtStart()!!.locationSource)
        vm.save("P", null, 0, "ic_pin")
        assertEquals("internal_gps", repo.store.single().captureMethod)
    }

    @Test fun save_twice_secondSaveStoresAnotherPoint() = runBlocking {
        val vm = vm()
        complete(vm)
        vm.save("A", null, 0, "ic_pin")
        vm.save("B", null, 0, "ic_pin")
        // The completed state stays until the UI cancels/starts again; each save persists a row.
        assertEquals(listOf("A", "B"), repo.store.map { it.name })
    }

    @Test fun settingsAtStart_withoutCapture_readsCurrentSettings() = runBlocking {
        val vm = vm()
        val snap = vm.settingsAtStart()
        assertNotNull(snap)
        assertEquals(LocationSourceType.EXTERNAL, snap!!.locationSource)
        assertEquals("Reach", snap.externalTcpName)
        assertEquals("192.168.42.1", snap.externalTcpHost)
        assertEquals("Emlid Reach RS2+", snap.externalProfileLabel)
        assertEquals(1.8, snap.receiver!!.poleHeightM, 0.0)
        assertNull("coordinate system flow is not stubbed: read fails -> null", snap.coordinateSystemSettings)
    }
}
