package app.surrealar.ui.settings.categories

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import app.surrealar.R
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.ReceiverProbe
import app.surrealar.receiver.selection.TcpProbeResult
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.RecentReceiver
import app.surrealar.ui.settings.receiver.ReceiverConfirmDialog
import app.surrealar.ui.settings.receiver.ReceiverDetailsSheet
import app.surrealar.ui.settings.receiver.ReceiverPickerSheet
import app.surrealar.ui.settings.receiver.ReceiverUiDeps
import app.surrealar.ui.settings.receiver.rowTitled
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Receiver category lifecycle (Phase 5 UI): loads, the receiver card and the connect attempt against category
 * switches and fragment view recreation. Ported from the pre-Phase 5 screen (inline host/port, source radio and
 * Disconnect button, all removed): the same guarantees are checked through the new UI — the card, the picker sheet's
 * "Tablet GNSS" row and the details sheet's "Disconnect receiver". The connect probe is a deferred the test completes,
 * so "in flight" is deterministic; everything runs on the paused Robolectric main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReceiverSettingsLifecycleTest {

    /** Exceptions thrown out of main-thread coroutines (on a device: an app crash). */
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun catchUncaught() {
        previousHandler = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After fun noCrashes() {
        ReceiverUiDeps.override = null
        Thread.currentThread().uncaughtExceptionHandler = previousHandler
        assertEquals("uncaught exceptions on the main thread", emptyList<String>(), uncaught.map { it.toString() })
    }

    private val capabilities = MutableStateFlow(ReceiverCapabilities.NONE)
    private val deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
    private val currentFix = MutableStateFlow<Fix?>(null)
    private val coordinator: GnssSourceCoordinator = Mockito.mock(GnssSourceCoordinator::class.java)
    /** What the coordinator made active (the mock records it, as SourceSettings.activeProvider would). */
    private val provider = MutableStateFlow(ProviderChoice.INTERNAL)
    private val link = MutableStateFlow(ConnectionState.DISCONNECTED)

    init {
        Mockito.doAnswer { provider.value = ProviderChoice.EXTERNAL_TCP; link.value = ConnectionState.CONNECTED; null }
            .`when`(coordinator).activateExternalTcpProvider(anyString(), anyInt(), anyString())
        Mockito.doAnswer { provider.value = ProviderChoice.INTERNAL; link.value = ConnectionState.DISCONNECTED; null }
            .`when`(coordinator).activateInternalProvider(anyString())
    }

    private val deadPort = 9101

    /** The fake connect probe: counts calls, waits for [probeResult], counts returns/cancellations. */
    private val probeResult = CompletableDeferred<TcpProbeResult>()
    private var probeCalls = 0
    private var probeEnded = 0
    private val probe = object : ReceiverProbe {
        override suspend fun tcp(host: String, port: Int): TcpProbeResult {
            probeCalls++
            try { return probeResult.await() } finally { probeEnded++ }
        }
        override suspend fun http(host: String): Boolean = true
    }
    private val receiverFound = TcpProbeResult.RECEIVING_NMEA

    private fun settings(
        storedHost: Flow<String?> = flowOf(null), storedPort: Int? = null,
        source: Flow<LocationSourceType> = flowOf(LocationSourceType.EXTERNAL), mock: Flow<Boolean> = flowOf(false),
    ): SettingsRepository =
        Mockito.mock(SettingsRepository::class.java).also {
            Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.DEFAULT))
            Mockito.`when`(it.externalTcpHost).thenReturn(storedHost)
            Mockito.`when`(it.externalTcpPort).thenReturn(flowOf(storedPort))
            Mockito.`when`(it.externalTcpName).thenReturn(flowOf(null))
            // Always EXTERNAL: only the controller's own attempt guards can stop an activation.
            Mockito.`when`(it.locationSource).thenReturn(source)
            Mockito.`when`(it.externalConnType).thenReturn(flowOf(ExternalConnectionType.TCP))
            Mockito.`when`(it.externalRecentReceivers).thenReturn(flowOf(listOf(RecentReceiver("127.0.0.1", deadPort, null, "Rover"))))
            Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings()))
            Mockito.`when`(it.mockLocationEnabled).thenReturn(mock)
        }

    private fun switchboard(): FixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also {
        Mockito.`when`(it.currentFix).thenReturn(currentFix)
        Mockito.`when`(it.sky).thenReturn(MutableStateFlow(SkySnapshot()))
    }

    private fun devices(): ReceiverDeviceManager = Mockito.mock(ReceiverDeviceManager::class.java).also {
        Mockito.`when`(it.profileCapabilities).thenReturn(capabilities)
        Mockito.`when`(it.deviceInfo).thenReturn(deviceInfo)
        Mockito.`when`(it.battery).thenReturn(MutableStateFlow(null))
        Mockito.`when`(it.corrections).thenReturn(MutableStateFlow(null))
    }

    private val host = ControllerHost()
    private fun controller(repo: SettingsRepository = settings()): ReceiverSettingsController {
        val core = ReceiverConnectionController(repo, coordinator, mainScope(), probe, activeProvider = provider, externalLink = link)
        ReceiverUiDeps.override = object : ReceiverUiDeps {
            override val connection = core
            override val settings = repo
            override val fixSwitchboard = switchboard()
            override val devices = devices()
            override val nmeaLinesPerSecond: Flow<Double> = flowOf(0.0)
        }
        return ReceiverSettingsController(host.fragment, repo, switchboard(), coordinator, devices(), mainScope(),
            refreshCategoriesForSource = {}, receiverConnection = core)
    }

    private class Visit(val view: View, val scope: CoroutineScope)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Opens the Receiver category: a fresh category scope, like BaseTwoPaneFragment.showCategory. */
    private fun visit(c: ReceiverSettingsController): Visit {
        val scope = mainScope()
        return Visit(host.show(c, scope), scope).also { idle() }
    }

    /** Leaves it the way SettingsFragment does: scope cancelled, view removed, controller.onDestroyView. */
    private fun leave(c: ReceiverSettingsController, v: Visit) {
        v.scope.cancel()
        host.fragment.container.removeView(v.view)
        c.onDestroyView()
        idle()
    }

    /** "Change receiver" → the recent receiver row (the old screen's host/port + Connect). */
    private fun connect(v: Visit) {
        v.view.findViewById<View>(R.id.btn_change_receiver).performClick()
        idle()
        val sheet = host.fragment.childFragmentManager.findFragmentByTag(ReceiverPickerSheet.TAG) as ReceiverPickerSheet
        rowTitled(sheet.requireView(), "Rover").performClick()
        idle()
    }

    private fun picker(): ReceiverPickerSheet {
        host.fragment.childFragmentManager.executePendingTransactions()
        return host.fragment.childFragmentManager.findFragmentByTag(ReceiverPickerSheet.TAG) as ReceiverPickerSheet
    }

    private fun activations() = Mockito.mockingDetails(coordinator).invocations
        .count { it.method.name == "activateExternalTcpProvider" }

    private fun stateChip(v: View) = v.findViewById<TextView>(R.id.chip_receiver_state).text.toString()

    private fun assertSingleCollectors() {
        assertEquals("capabilities collectors", 1, capabilities.subscriptionCount.value)
        assertEquals("fix collectors", 1, currentFix.subscriptionCount.value)
    }

    @Test fun `leaving Receiver while its stored settings load is pending leaves the old view alone and starts nothing`() {
        val gate = CompletableDeferred<String?>()
        val c = controller(settings(storedHost = flow { emit(gate.await()) }, storedPort = deadPort))
        val first = visit(c)
        leave(c, first)

        gate.complete("127.0.0.1")
        idle()

        assertEquals("the abandoned load did not auto-reconnect", 0, probeCalls)
        assertEquals(0, activations())
    }

    @Test fun `destroying the fragment view while the stored settings load is pending does not crash or connect`() {
        val gate = CompletableDeferred<String?>()
        val c = controller(settings(storedHost = flow { emit(gate.await()) }, storedPort = deadPort))
        val first = visit(c)
        leave(c, first)
        host.activity.supportFragmentManager.beginTransaction().detach(host.fragment).commitNow()

        gate.complete("127.0.0.1")
        idle()

        assertEquals(0, probeCalls)
        assertEquals(0, activations())
    }

    @Test fun `returning to Receiver shows the stored receiver with a single set of collectors and one auto-reconnect`() {
        val c = controller(settings(storedHost = flowOf("127.0.0.1"), storedPort = deadPort))
        leave(c, visit(c))
        val again = visit(c)

        assertEquals("127.0.0.1:$deadPort", again.view.findViewById<TextView>(R.id.text_receiver_name).text.toString())
        assertEquals("External receiver", again.view.findViewById<TextView>(R.id.text_receiver_source).text.toString())
        assertEquals("Testing…", stateChip(again.view))
        assertSingleCollectors()
        assertEquals("auto-reconnect runs once per controller", 1, probeCalls)
    }

    @Test fun `disconnecting from the details while the probe is pending never activates External`() {
        val c = controller()
        val v = visit(c)
        connect(v)
        assertEquals(1, probeCalls)

        v.view.findViewById<View>(R.id.btn_receiver_details).performClick()
        idle()
        host.fragment.childFragmentManager.executePendingTransactions()
        val details = host.fragment.childFragmentManager.findFragmentByTag(ReceiverDetailsSheet.TAG) as ReceiverDetailsSheet
        details.requireView().findViewById<View>(R.id.btn_details_disconnect).performClick()
        // H2: Disconnect is confirmed (it removes the saved receiver); nothing happens before that.
        Mockito.verify(coordinator, Mockito.never()).activateInternalProvider("receiver-details")
        confirm(details)
        Mockito.verify(coordinator, Mockito.atLeastOnce()).activateInternalProvider("receiver-details")
        probeResult.complete(receiverFound)
        idle()

        assertEquals("the attempt ended", 1, probeEnded)
        Mockito.verify(coordinator, Mockito.never()).activateExternalTcpProvider(anyString(), anyInt(), anyString())
    }

    @Test fun `disconnect writes no EXTERNAL source and starts no new attempt`() {
        val repo = settings()
        val c = controller(repo)
        val v = visit(c)
        connect(v)
        val externalWritesBefore = externalSourceWrites(repo)

        rowTitled(picker().requireView(), "Tablet GNSS").performClick()
        idle()

        assertEquals("no EXTERNAL source written", externalWritesBefore, externalSourceWrites(repo))
        assertEquals("no new attempt", 1, probeCalls)
    }

    private fun externalSourceWrites(repo: SettingsRepository) = Mockito.mockingDetails(repo).invocations
        .count {
            (it.method.name == "setLocationSource" && it.arguments.firstOrNull() == LocationSourceType.EXTERNAL) ||
                // Phase 5 review fix M5: a switch saves the source inside the one-transaction receiver commit.
                (it.method.name == "saveExternalTcpReceiver" && LocationSourceType.EXTERNAL in it.arguments)
        }

    @Test fun `selecting tablet GNSS while an External attempt is in progress keeps Internal active`() {
        val c = controller()
        val v = visit(c)
        connect(v)
        assertEquals(1, probeCalls)

        rowTitled(picker().requireView(), "Tablet GNSS").performClick()     // the user's tap
        probeResult.complete(receiverFound)
        idle()

        assertEquals("no other attempt started", 1, probeCalls)
        assertEquals(1, probeEnded)
        Mockito.verify(coordinator, Mockito.atLeastOnce()).activateInternalProvider("picker")
        Mockito.verify(coordinator, Mockito.never()).activateExternalTcpProvider(anyString(), anyInt(), anyString())
        assertEquals(ProviderChoice.INTERNAL, provider.value)
    }

    @Test fun `rapid category switching leaves one set of collectors and no extra connections`() {
        val c = controller(settings(storedHost = flowOf("127.0.0.1"), storedPort = deadPort))
        repeat(15) { leave(c, visit(c)) }
        assertEquals("nothing collects while Receiver is hidden", 0, capabilities.subscriptionCount.value)
        assertEquals(0, currentFix.subscriptionCount.value)

        visit(c)

        assertSingleCollectors()
        assertEquals("one auto-reconnect, no duplicate attempts", 1, probeCalls)
    }

    @Test fun `the connect attempt survives a category switch and updates only the current view`() {
        val c = controller()
        val first = visit(c)
        connect(first)
        picker().dismiss()
        idle()
        leave(c, first)
        val shownState = stateChip(first.view)

        probeResult.complete(receiverFound)
        idle()

        assertEquals("activated once", 1, activations())
        Mockito.verify(coordinator).activateExternalTcpProvider("127.0.0.1", deadPort, "settings-connect #1")
        assertEquals("old view untouched", shownState, stateChip(first.view))
    }

    /**
     * Connect attempts are app-scoped (ReceiverConnectionController), so recreating the Settings view does not
     * cancel a pending attempt. It still runs exactly once (no duplicate connection), activates once when the
     * receiver streams NMEA, never touches the old view, and its outcome shows on the CURRENT view's card.
     */
    @Test fun `recreating the Settings view while a connection is pending keeps the one attempt and shows it on the new view`() {
        val c = controller()
        val first = visit(c)
        connect(first)
        assertEquals(1, probeCalls)
        assertEquals("Testing…", stateChip(first.view))
        picker().dismiss()
        idle()

        leave(c, first)
        host.activity.supportFragmentManager.beginTransaction().detach(host.fragment).commitNow()
        host.activity.supportFragmentManager.beginTransaction().attach(host.fragment).commitNow()
        val second = visit(c)
        assertEquals("the new view shows the running test", "Testing…", stateChip(second.view))
        probeResult.complete(receiverFound)
        idle()

        assertEquals("the attempt finished", 1, probeEnded)
        assertEquals("no duplicate connection", 1, probeCalls)
        assertEquals("activated once", 1, activations())
        Mockito.verify(coordinator).activateExternalTcpProvider("127.0.0.1", deadPort, "settings-connect #1")
        assertEquals("old view untouched", "Testing…", stateChip(first.view))
        assertEquals("the new view shows the outcome", "Connected", stateChip(second.view))
        assertTrue(second.view.parent != null)
    }

    /** Presses the positive button of the receiver confirmation shown by [owner]. */
    private fun confirm(owner: androidx.fragment.app.Fragment) {
        val d = owner.childFragmentManager.findFragmentByTag(ReceiverConfirmDialog.TAG) as ReceiverConfirmDialog
        (d.dialog as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun mockWrites(repo: SettingsRepository) = Mockito.mockingDetails(repo).invocations
        .filter { it.method.name == "setMockLocationEnabled" }.map { it.arguments[0] }

    @Test fun `M5 a double tap opens one picker and one details sheet`() {
        val v = visit(controller())
        repeat(2) { v.view.findViewById<View>(R.id.btn_change_receiver).performClick() }
        idle()
        assertEquals(1, host.fragment.childFragmentManager.fragments.count { it is ReceiverPickerSheet })
        picker().dismiss()
        idle()
        repeat(2) { v.view.findViewById<View>(R.id.btn_receiver_details).performClick() }
        idle()
        assertEquals(1, host.fragment.childFragmentManager.fragments.count { it is ReceiverDetailsSheet })
    }

    @Test fun `L10 Retry starts the location service like the automatic reconnect`() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        probeResult.complete(TcpProbeResult.CONNECT_FAILED)
        val v = visit(controller(settings(storedHost = flowOf("127.0.0.1"), storedPort = deadPort)))
        assertEquals("the automatic reconnect ran", 1, probeCalls)
        val retry = v.view.findViewById<View>(R.id.btn_retry_receiver)
        assertEquals(View.VISIBLE, retry.visibility)
        while (shadowOf(application).nextStartedService != null) Unit   // the automatic reconnect's start

        retry.performClick()
        idle()

        assertEquals("Retry tested the saved receiver again", 2, probeCalls)
        val started = shadowOf(application).nextStartedService
        assertEquals(app.surrealar.service.LocationService::class.java.name, started?.component?.className)
    }

    @Test fun `L11 the mock switch never saves from the selection state's not-yet-loaded defaults`() {
        val gate = CompletableDeferred<LocationSourceType>()
        val repo = settings(source = flow { emit(gate.await()) }, mock = flowOf(true))
        val v = visit(controller(repo))
        val sw = v.view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_publish_mock_location)
        assertEquals("nothing saved while the source is loading", emptyList<Any>(), mockWrites(repo))

        gate.complete(LocationSourceType.EXTERNAL)
        idle()

        assertEquals("the stored setting is shown", true, sw.isChecked)
        assertEquals(true, sw.isEnabled)
        assertEquals("no write from a programmatic update", emptyList<Any>(), mockWrites(repo))
        sw.performClick()
        idle()
        assertEquals("a real user change saves", listOf<Any>(false), mockWrites(repo))
    }

    @Test fun `L11 a loaded tablet source with mock publishing on saves it off, as before`() {
        val repo = settings(source = flowOf(LocationSourceType.INTERNAL), mock = flowOf(true))
        val v = visit(controller(repo))
        val sw = v.view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_publish_mock_location)
        assertEquals(false, sw.isEnabled)
        assertEquals(listOf<Any>(false), mockWrites(repo))
    }
}
