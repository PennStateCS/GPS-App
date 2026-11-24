package app.surrealar.ui.settings.categories

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.surrealar.R
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Receiver category lifecycle: loads, status and the connect attempt against category switches and
 * fragment view recreation. The connect probe is the controller's test seam (a deferred the test
 * completes), so "in flight" is deterministic; everything runs on the paused Robolectric main looper.
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
        Thread.currentThread().uncaughtExceptionHandler = previousHandler
        assertEquals("uncaught exceptions on the main thread", emptyList<String>(), uncaught.map { it.toString() })
    }

    private val capabilities = MutableStateFlow(ReceiverCapabilities.NONE)
    private val deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
    private val currentFix = MutableStateFlow<Fix?>(null)
    private val rawNmea = MutableSharedFlow<Unit>()
    private val coordinator: GnssSourceCoordinator = Mockito.mock(GnssSourceCoordinator::class.java)

    /** A port nothing listens on (the diagnostic probe the Connect button also starts fails fast). */
    private val deadPort: Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** The fake connect probe: counts calls, waits for [probeResult], counts returns/cancellations. */
    private val probeResult = CompletableDeferred<Pair<ReceiverSettingsController.TcpTestResult, Boolean>>()
    private var probeCalls = 0
    private var probeEnded = 0
    private val probe: suspend (String, Int) -> Pair<ReceiverSettingsController.TcpTestResult, Boolean> = { _, _ ->
        probeCalls++
        try { probeResult.await() } finally { probeEnded++ }
    }
    private val receiverFound = ReceiverSettingsController.TcpTestResult.RECEIVING_NMEA to true

    private fun settings(storedHost: Flow<String?> = flowOf(null), storedPort: Int? = null): SettingsRepository =
        Mockito.mock(SettingsRepository::class.java).also {
            Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.DEFAULT))
            Mockito.`when`(it.externalTcpHost).thenReturn(storedHost)
            Mockito.`when`(it.externalTcpPort).thenReturn(flowOf(storedPort))
            Mockito.`when`(it.externalTcpName).thenReturn(flowOf(null))
            // Always EXTERNAL: only the controller's own attempt guards can stop an activation.
            Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
            Mockito.`when`(it.externalConnType).thenReturn(flowOf(ExternalConnectionType.TCP))
            Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings()))
            Mockito.`when`(it.mockLocationEnabled).thenReturn(flowOf(false))
        }

    private fun switchboard(): FixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also {
        Mockito.`when`(it.currentFix).thenReturn(currentFix)
        Mockito.`when`(it.sky).thenReturn(MutableStateFlow(SkySnapshot()))
        Mockito.`when`(it.rawNmea).thenReturn(rawNmea)
    }

    private fun devices(): ReceiverDeviceManager = Mockito.mock(ReceiverDeviceManager::class.java).also {
        Mockito.`when`(it.profileCapabilities).thenReturn(capabilities)
        Mockito.`when`(it.deviceInfo).thenReturn(deviceInfo)
        Mockito.`when`(it.battery).thenReturn(MutableStateFlow(null))
        Mockito.`when`(it.corrections).thenReturn(MutableStateFlow(null))
    }

    private val host = ControllerHost()
    private fun controller(repo: SettingsRepository = settings()) = ReceiverSettingsController(
        host.fragment, repo, switchboard(), coordinator, devices(), mainScope(), refreshCategoriesForSource = {},
        connectProbeOverride = probe)

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

    private fun connect(v: Visit) {
        v.view.findViewById<EditText>(R.id.edit_host).setText("127.0.0.1")
        v.view.findViewById<EditText>(R.id.edit_port).setText(deadPort.toString())
        v.view.findViewById<Button>(R.id.btn_connect).performClick()
        idle()
    }

    private fun activations() = Mockito.mockingDetails(coordinator).invocations
        .count { it.method.name == "activateExternalTcpProvider" }

    private fun statusText(v: View) = v.findViewById<TextView>(R.id.text_device_status).text.toString()

    private fun assertSingleCollectors() {
        assertEquals("capabilities collectors", 1, capabilities.subscriptionCount.value)
        assertEquals("device info collectors", 1, deviceInfo.subscriptionCount.value)
        assertEquals("fix collectors", 1, currentFix.subscriptionCount.value)
        assertEquals("raw NMEA collectors", 1, rawNmea.subscriptionCount.value)
    }

    @Test fun `leaving Receiver while its stored settings load is pending leaves the old view alone and starts nothing`() {
        val gate = CompletableDeferred<String?>()
        val c = controller(settings(storedHost = flow { emit(gate.await()) }, storedPort = deadPort))
        val first = visit(c)
        leave(c, first)

        gate.complete("127.0.0.1")
        idle()

        assertEquals("old view not filled after it was left", "", first.view.findViewById<EditText>(R.id.edit_host).text.toString())
        assertEquals("the abandoned load did not auto-reconnect", 0, probeCalls)
        assertEquals(0, activations())
    }

    @Test fun `destroying the fragment view while the stored settings load is pending does not crash or connect`() {
        val gate = CompletableDeferred<String?>()
        val c = controller(settings(storedHost = flow { emit(gate.await()) }, storedPort = deadPort))
        val first = visit(c)
        leave(c, first)
        host.activity.supportFragmentManager.beginTransaction().detach(host.fragment).commitNow()

        // Before: the load ran on the fragment's lifecycleScope and its auto-reconnect touched
        // fragment.viewLifecycleOwner with no fragment view -> IllegalStateException on the main thread.
        gate.complete("127.0.0.1")
        idle()

        assertEquals(0, probeCalls)
        assertEquals(0, activations())
    }

    @Test fun `returning to Receiver shows the stored state with a single set of collectors and one auto-reconnect`() {
        val c = controller(settings(storedHost = flowOf("127.0.0.1"), storedPort = deadPort))
        leave(c, visit(c))
        val again = visit(c)

        assertEquals("127.0.0.1", again.view.findViewById<EditText>(R.id.edit_host).text.toString())
        assertEquals(deadPort.toString(), again.view.findViewById<EditText>(R.id.edit_port).text.toString())
        assertEquals(R.id.radio_es2_tcp, again.view.findViewById<RadioGroup>(R.id.radio_location_source).checkedRadioButtonId)
        assertSingleCollectors()
        assertEquals("auto-reconnect runs once per controller", 1, probeCalls)
    }

    @Test fun `disconnecting while the probe is pending never activates External`() {
        val c = controller()
        val v = visit(c)
        connect(v)
        assertEquals(1, probeCalls)

        v.view.findViewById<Button>(R.id.btn_disconnect_device).performClick()
        Mockito.verify(coordinator, Mockito.atLeastOnce()).activateInternalProvider("settings")
        probeResult.complete(receiverFound)
        idle()

        assertEquals("the attempt ended", 1, probeEnded)
        Mockito.verify(coordinator, Mockito.never()).activateExternalTcpProvider(anyString(), anyInt(), anyString())
    }

    @Test fun `disconnect switches the radio without running the External branch`() {
        val repo = settings()
        val c = controller(repo)
        val v = visit(c)
        connect(v)
        val externalWritesBefore = externalSourceWrites(repo)

        // Disconnect calls RadioGroup.check(radio_internal), which also reports the External button it
        // unchecks; that callback used to persist source EXTERNAL right before Internal undid it.
        v.view.findViewById<Button>(R.id.btn_disconnect_device).performClick()
        idle()

        assertEquals("no EXTERNAL source written by Disconnect", externalWritesBefore, externalSourceWrites(repo))
        assertEquals(R.id.radio_internal, v.view.findViewById<RadioGroup>(R.id.radio_location_source).checkedRadioButtonId)
        assertEquals("no new attempt", 1, probeCalls)
    }

    private fun externalSourceWrites(repo: SettingsRepository) = Mockito.mockingDetails(repo).invocations
        .count { it.method.name == "setLocationSource" && it.arguments.firstOrNull() == LocationSourceType.EXTERNAL }

    @Test fun `selecting device GNSS while an External attempt is in progress keeps Internal active`() {
        val c = controller()
        val v = visit(c)
        val radio = v.view.findViewById<RadioGroup>(R.id.radio_location_source)
        assertEquals(R.id.radio_es2_tcp, radio.checkedRadioButtonId)       // stored source = External
        connect(v)
        assertEquals(1, probeCalls)

        v.view.findViewById<RadioButton>(R.id.radio_internal).performClick()     // the user's tap
        probeResult.complete(receiverFound)
        idle()

        assertEquals("no other attempt started", 1, probeCalls)
        assertEquals(1, probeEnded)
        Mockito.verify(coordinator, Mockito.atLeastOnce()).activateInternalProvider("settings")
        Mockito.verify(coordinator, Mockito.never()).activateExternalTcpProvider(anyString(), anyInt(), anyString())
        assertEquals(R.id.radio_internal, radio.checkedRadioButtonId)
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
        leave(c, first)
        val shownStatus = statusText(first.view)

        probeResult.complete(receiverFound)
        idle()

        assertEquals("activated once", 1, activations())
        Mockito.verify(coordinator).activateExternalTcpProvider("127.0.0.1", deadPort, "settings-connect #1")
        assertEquals("old view untouched", shownStatus, statusText(first.view))
    }

    @Test fun `recreating the Settings view while a connection is pending drops the old attempt`() {
        val c = controller()
        val first = visit(c)
        connect(first)
        assertEquals(1, probeCalls)
        val shownStatus = statusText(first.view)

        // Fragment view destroyed (e.g. navigating away and back) and the Receiver category rebuilt.
        leave(c, first)
        host.activity.supportFragmentManager.beginTransaction().detach(host.fragment).commitNow()
        host.activity.supportFragmentManager.beginTransaction().attach(host.fragment).commitNow()
        val second = visit(c)
        probeResult.complete(receiverFound)
        idle()

        assertEquals("the old attempt was cancelled with its view", 1, probeEnded)
        assertEquals("no duplicate connection", 1, probeCalls)
        assertEquals("the superseded attempt changed no provider", 0, activations())
        assertEquals("old view untouched", shownStatus, statusText(first.view))
        assertNotEquals("Receiver connected", statusText(second.view))
        assertTrue(second.view.isAttachedToWindow || second.view.parent != null)
    }
}
