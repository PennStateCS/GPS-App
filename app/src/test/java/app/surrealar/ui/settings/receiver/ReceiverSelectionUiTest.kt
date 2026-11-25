package app.surrealar.ui.settings.receiver

import android.app.Application
import android.content.ClipboardManager
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.ExternalReconnectable
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectRequest
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.TcpProbeResult
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.RecentReceiver
import app.surrealar.ui.settings.categories.ControllerHost
import app.surrealar.ui.settings.categories.mainScope
import com.google.android.material.textfield.TextInputLayout
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Phase 5 receiver UI on Robolectric: the card's states, the picker (sections, never disconnects on dismiss, capture
 * confirmation, tablet switch guard), the manual dialog (validation, custom port kept, Test does not persist) and the
 * details' origin labels. A real selection core over a real GnssSourceCoordinator; probes and settings are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReceiverSelectionUiTest {

    private val settings = UiFakeSettings()
    private val fake get() = settings.core
    private val provider = MutableStateFlow(ProviderChoice.INTERNAL)
    private val sourceSettings = SourceSettings(provider, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val reconnects = mutableListOf<String>()
    private val coordinator = GnssSourceCoordinator(sourceSettings, settings,
        object : ExternalReconnectable { override fun reconnectNow(reason: String) { reconnects += reason } })
    private val link = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val capture = MutableStateFlow(false)
    private val probe = QueuedProbe()
    private val deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
    private lateinit var connection: ReceiverConnectionController
    private lateinit var deps: TestUiDeps
    private lateinit var host: ControllerHost

    @Before fun setUp() {
        connection = ReceiverConnectionController(settings, coordinator, mainScope(), probe,
            activeProvider = sourceSettings.activeProvider, externalLink = link, deviceInfo = deviceInfo,
            captureActive = capture, clockMs = { 1_000L })
        deps = TestUiDeps(connection, settings, deviceInfo = deviceInfo)
        ReceiverUiDeps.override = deps
        host = ControllerHost()
        idleMain()
    }

    @After fun tearDown() { ReceiverUiDeps.override = null }

    private fun workingExternal(profile: ExternalReceiverProfile = ExternalReceiverProfile.REACH_RS2_PLUS) {
        fake.source.value = LocationSourceType.EXTERNAL
        fake.connType.value = ExternalConnectionType.TCP
        fake.host.value = "192.168.42.1"
        fake.port.value = 9001
        fake.profile.value = profile
        fake.origin.value = ProfileOrigin.USER
        coordinator.activateExternalTcpProvider("192.168.42.1", 9001, "test setup")
        link.value = ConnectionState.CONNECTED
        idleMain()
        fake.writes.clear()
    }

    private fun showPicker(): ReceiverPickerSheet = ReceiverPickerSheet().also {
        it.show(host.fragment.childFragmentManager, ReceiverPickerSheet.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
    }

    private fun showManual(): ManualReceiverDialog = ManualReceiverDialog().also {
        it.show(host.fragment.childFragmentManager, ManualReceiverDialog.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
    }

    // ── card ──────────────────────────────────────────────────────────────────

    private fun card(): ReceiverCardBinder {
        val ctx = ContextThemeWrapper(host.activity, R.style.Theme_SurveyingApp)
        val v = LayoutInflater.from(ctx).inflate(R.layout.view_receiver_card, FrameLayout(ctx), false)
        return ReceiverCardBinder(v)
    }

    @Test fun `card renders tablet, testing, connected-verified, unverified, failed and mismatch states`() {
        val c = card()
        c.bind(ReceiverCardPresenter.present(ReceiverUiFixtures.internal, null, null, null,
            io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.NONE))
        assertEquals("Tablet GNSS", c.source.text.toString())
        assertEquals("Active", c.stateChip.text.toString())
        assertEquals(View.GONE, c.modelGroup.visibility)
        assertEquals("Connection state: Active", c.stateChip.contentDescription)

        val testing = ReceiverUiFixtures.connected().copy(attempt = ConnectionAttempt.Testing(1, ReceiverUiFixtures.TARGET, null))
        c.bind(ReceiverCardPresenter.present(testing, null, null, null, io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.ALL))
        assertEquals("Testing…", c.stateChip.text.toString())

        val verified = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO, ProfileOrigin.RECEIVER, ReceiverUiFixtures.rs4ProIdentity())
        c.bind(ReceiverCardPresenter.present(verified, ReceiverUiFixtures.fix(), ReceiverUiFixtures.sky(18, 24),
            io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery(80, false), io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.ALL))
        assertEquals("External receiver", c.source.text.toString())
        assertEquals("Connected", c.stateChip.text.toString())
        assertEquals("Emlid Reach RS4 Pro", c.model.text.toString())
        assertEquals("Reported by receiver", c.modelOrigin.text.toString())
        assertEquals("RTK Fix", c.fixChip.text.toString())
        assertEquals("18/24 sats", c.satsChip.text.toString())
        assertEquals("Battery 80%", c.batteryChip.text.toString())
        assertEquals("192.168.42.1:9001", c.address.text.toString())

        c.bind(ReceiverCardPresenter.present(ReceiverUiFixtures.connected(origin = ProfileOrigin.USER), null, null, null,
            io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.ALL))
        assertEquals("Emlid Reach RS2+", c.model.text.toString())
        assertEquals("Set by you", c.modelOrigin.text.toString())
        assertEquals("No position yet", c.fixChip.text.toString())
        assertEquals(View.GONE, c.batteryChip.visibility)

        val failed = ReceiverUiFixtures.connected().copy(activeProvider = ProviderChoice.INTERNAL,
            attempt = ConnectionAttempt.Failed(2, ReceiverUiFixtures.TARGET, app.surrealar.receiver.selection.ConnectError.NoData(9001)))
        c.bind(ReceiverCardPresenter.present(failed, null, null, null, io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.ALL))
        assertEquals("Failed: no data on port 9001", c.stateChip.text.toString())
        assertEquals(View.VISIBLE, c.retry.visibility)
        assertEquals(View.VISIBLE, c.failure.visibility)

        val mm = app.surrealar.receiver.selection.ProfileMismatch("192.168.42.1", "Reach RS4 Pro", ExternalReceiverProfile.REACH_RS4_PRO,
            ExternalReceiverProfile.REACH_RS2_PLUS, app.surrealar.receiver.selection.MismatchReason.PROFILE_SET_BY_USER)
        c.bind(ReceiverCardPresenter.present(ReceiverUiFixtures.connected().copy(mismatch = mm), null, null, null,
            io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities.ALL))
        assertEquals(View.VISIBLE, c.mismatchGroup.visibility)
        assertEquals("Receiver reports Reach RS4 Pro; profile is Emlid Reach RS2+.", c.mismatchText.text.toString())
        assertEquals("Use RS4 Pro", c.mismatchUse.text.toString())
    }

    // ── picker ────────────────────────────────────────────────────────────────

    @Test fun `picker lists its sections and dismissing it never cancels the attempt or disconnects`() {
        workingExternal()
        fake.recent.value = listOf(RecentReceiver("192.168.42.7", 9001, "reach_rs4", "Rover", "Reach RS4", lastConnectedMs = 500L))
        idleMain()
        val pending = probe.next()
        connection.connect(ConnectRequest("192.168.42.7", 9001))
        idleMain()
        val sheet = showPicker()
        val shown = texts(sheet.requireView())
        listOf("Choose receiver", "Tablet GNSS", "On this network", "Previously connected", "Connect manually…", "Rover",
            "Testing…", "Reach RS4 (reported by receiver)").forEach { want ->
            assertTrue("\"$want\" in $shown", shown.any { it == want || it.lines().contains(want) })
        }
        // The Emlid profile scans on open (no discovery in this test, so the scan reports "not available").
        assertTrue(connection.state.value.scan is app.surrealar.receiver.selection.NetworkScanState.Failed)

        sheet.dismiss()
        idleMain()
        assertTrue("attempt still running after dismiss", connection.state.value.attempt is ConnectionAttempt.Testing)
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertFalse("nothing switched to device GNSS", fake.writes.contains("source=INTERNAL"))

        pending.complete(TcpProbeResult.RECEIVING_NMEA)
        idleMain()
        assertTrue(connection.state.value.attempt is ConnectionAttempt.Succeeded)
        assertEquals("192.168.42.7", fake.host.value)
    }

    @Test fun `a Generic NMEA profile does not scan on open`() {
        workingExternal(ExternalReceiverProfile.GENERIC_NMEA_TCP)
        showPicker()
        assertEquals(app.surrealar.receiver.selection.NetworkScanState.Idle, connection.state.value.scan)
    }

    @Test fun `switching during a capture asks first and connects only after Switch`() {
        workingExternal()
        capture.value = true
        fake.recent.value = listOf(RecentReceiver("192.168.42.7", 9001, "reach_rs2_plus", "Rover"))
        idleMain()
        val sheet = showPicker()
        rowTitled(sheet.requireView(), "Rover").performClick()
        idleMain()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(texts(dialog.window!!.decorView).contains("Switching receivers stops the current capture. Switch?"))
        assertEquals("nothing started before confirming", ConnectionAttempt.Idle, connection.state.value.attempt)
        assertEquals(0, probe.tcpCalls)

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertEquals(1, probe.tcpCalls)
        assertEquals("192.168.42.7", fake.host.value)
    }

    @Test fun `choosing tablet GNSS while a test is pending keeps device GNSS active`() {
        workingExternal()
        fake.recent.value = listOf(RecentReceiver("192.168.42.7", 9001, null, "Rover"))
        idleMain()
        val sheet = showPicker()
        val pending = probe.next()
        rowTitled(sheet.requireView(), "Rover").performClick()
        idleMain()
        assertTrue(connection.state.value.busy)
        rowTitled(sheet.requireView(), "Tablet GNSS").performClick()
        idleMain()
        pending.complete(TcpProbeResult.RECEIVING_NMEA)
        idleMain()
        assertEquals(ProviderChoice.INTERNAL, provider.value)
        assertEquals(LocationSourceType.INTERNAL, fake.source.value)
        assertFalse("the superseded attempt saved nothing", fake.writes.any { it.startsWith("tcp=192.168.42.7") })
    }

    // ── manual dialog ─────────────────────────────────────────────────────────

    private fun ManualReceiverDialog.view(id: Int): View = (dialog as AlertDialog).findViewById<View>(id)!!

    @Test fun `manual dialog validates host and port and starts nothing when invalid`() {
        val d = showManual()
        (d.view(R.id.edit_manual_host) as EditText).setText("192.168.42")
        (d.view(R.id.edit_manual_port) as EditText).setText("70000")
        (d.dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertNotNull((d.view(R.id.input_manual_host) as TextInputLayout).error)
        assertNotNull((d.view(R.id.input_manual_port) as TextInputLayout).error)
        assertEquals(0, probe.tcpCalls)
        assertTrue(d.dialog!!.isShowing)
    }

    @Test fun `manual dialog keeps a custom port when the type changes and applies the default otherwise`() {
        val d = showManual()
        val port = d.view(R.id.edit_manual_port) as EditText
        val type = d.view(R.id.dropdown_manual_type) as AutoCompleteTextView
        assertEquals("prefilled from the profile default", "9001", port.text.toString())
        type.onItemClickListener.onItemClick(null, null, ManualReceiverInput.types.indexOf(ExternalReceiverProfile.GENERIC_NMEA_TCP), 0)
        assertEquals("a default port follows the type", "9000", port.text.toString())
        assertEquals("Generic NMEA: the app only reads NMEA from this port and sends no other requests.",
            (d.view(R.id.text_manual_hint) as TextView).text.toString())
        port.setText("9100")
        type.onItemClickListener.onItemClick(null, null, ManualReceiverInput.types.indexOf(ExternalReceiverProfile.REACH_RS4), 0)
        assertEquals("a custom port is kept", "9100", port.text.toString())
    }

    @Test fun `Test connection shows the result inline and saves or switches nothing`() {
        workingExternal()
        val d = showManual()
        (d.view(R.id.edit_manual_host) as EditText).setText("192.168.42.7")
        val type = d.view(R.id.dropdown_manual_type) as AutoCompleteTextView
        type.onItemClickListener.onItemClick(null, null, ManualReceiverInput.types.indexOf(ExternalReceiverProfile.GENERIC_NMEA_TCP), 0)
        (d.dialog as AlertDialog).getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idleMain()
        assertEquals(1, probe.tcpCalls)
        assertEquals("Generic NMEA: no Emlid request", 0, probe.httpCalls)
        assertTrue((d.view(R.id.text_manual_result) as TextView).text.startsWith("NMEA received on port 9000"))
        assertEquals(emptyList<String>(), fake.writes.toList())
        assertEquals("192.168.42.1", fake.host.value)
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertEquals(ConnectionAttempt.Idle, connection.state.value.attempt)
        assertTrue("the dialog stays open", d.dialog!!.isShowing)

        (d.dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        assertEquals(listOf("connType=TCP", "tcp=192.168.42.7:9000", "profile=generic_nmea_tcp/user", "source=EXTERNAL"), fake.writes.toList())
        assertNull("dismissed after Save & connect", host.fragment.childFragmentManager.findFragmentByTag(ManualReceiverDialog.TAG)?.takeIf { it.isAdded })
    }

    // ── details ───────────────────────────────────────────────────────────────

    @Test fun `details tag each value with its origin and the summary never includes the address`() {
        workingExternal(ExternalReceiverProfile.REACH_RS4_PRO)
        deps.deviceInfo.value = ReceiverDeviceInfo(host = "192.168.42.1", name = "Base", model = "Reach RS4 Pro", firmware = "34.7", serial = "SN123")
        idleMain()
        val sheet = ReceiverDetailsSheet().also {
            it.show(host.fragment.childFragmentManager, ReceiverDetailsSheet.TAG)
            host.fragment.childFragmentManager.executePendingTransactions()
            idleMain()
        }
        val rows = sheet.requireView().findViewById<android.widget.LinearLayout>(R.id.details_rows)
        fun row(label: String): List<String> {
            for (i in 0 until rows.childCount) {
                val t = texts(rows.getChildAt(i))
                if (t.firstOrNull() == label) return t
            }
            error("no row $label in ${texts(rows)}")
        }
        assertEquals(listOf("Model", "Reach RS4 Pro", "Reported by receiver"), row("Model"))
        assertEquals(listOf("Firmware", "34.7", "Reported by receiver"), row("Firmware"))
        assertEquals(listOf("Pole height", "${app.surrealar.ui.settings.AntennaHeightInput.format(2.0)} m", "Default"), row("Pole height"))
        assertEquals("Set by you", row("Receiver profile").last())
        assertTrue(row("Compensated NMEA output (tilt)").contains(
            "Firmware alone does not show whether tilt compensation or compensated NMEA output is turned on. Set it to match Emlid Flow."))
        assertEquals("Unknown", row("Compensated NMEA output (tilt)")[1])
        assertEquals("Unknown", row("Compensated NMEA output (tilt)").last())
        assertEquals("Unknown", row("Receiver orthometric height model").last())
        assertEquals("5.0 lines/s", row("NMEA rate")[1])

        sheet.requireView().findViewById<View>(R.id.btn_details_copy_summary).performClick()
        val clip = host.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip.contains("Model: Reach RS4 Pro [Reported by receiver]"))
        assertFalse("no address in the summary", clip.contains("192.168.42.1"))
        assertFalse("no receiver name in the summary", clip.contains(": Base"))
    }
}
