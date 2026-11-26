package app.surrealar.ui.toolbar.report

import android.app.Application
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.surrealar.R
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.receiver.selection.LiveSourceState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.ui.settings.categories.ControllerHost
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import app.surrealar.ui.settings.receiver.idleMain
import app.surrealar.ui.toolbar.ToolbarFrame
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake [GnssStatusDeps] backed by mutable flows. */
internal class FakeGnssStatusDeps(
    override val frames: MutableStateFlow<ToolbarFrame?> = MutableStateFlow(GnssReportFixtures.frame()),
    override val selection: MutableStateFlow<ReceiverSelectionState> = MutableStateFlow(GnssReportFixtures.selection),
    override val liveSource: MutableStateFlow<LiveSourceState> = MutableStateFlow(GnssReportFixtures.live(ReceiverUiFixtures.fix())),
    override val receiverSettings: MutableStateFlow<ExternalReceiverSettings> = MutableStateFlow(GnssReportFixtures.receiver),
    override val battery: MutableStateFlow<ReceiverBattery?> = MutableStateFlow(ReceiverBattery(82, true)),
    override val deviceInfo: MutableStateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null),
    override val corrections: MutableStateFlow<ReceiverCorrections?> = MutableStateFlow(ReceiverCorrections(true, "NTRIP", "0042")),
    override val tilt: MutableStateFlow<TiltStatus> = MutableStateFlow(TiltStatus.NONE),
    override val diagnostics: MutableStateFlow<DiagnosticData> = MutableStateFlow(DiagnosticData(linesPerSecond = 10.0, totalLinesProcessed = 100)),
) : GnssStatusDeps {
    // The report is built on Default in the app; here on the main looper so updates are observable after idleMain().
    override val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Main
    var toggles = 0
    var receiverOpens = 0
    override fun toggleCoordinates() { toggles++ }
    override fun openReceiverSettings() { receiverOpens++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class GnssStatusSheetTest {

    @After fun clear() { GnssStatusDeps.override = null }

    private fun open(deps: FakeGnssStatusDeps): Pair<ControllerHost, GnssStatusSheet> {
        GnssStatusDeps.override = deps
        val host = ControllerHost()
        val sheet = GnssStatusSheet()
        sheet.show(host.activity.supportFragmentManager, GnssStatusSheet.TAG)
        host.activity.supportFragmentManager.executePendingTransactions()
        idleMain()
        return host to sheet
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> if (v.visibility == View.VISIBLE) listOf(v.text.toString()) else emptyList()
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun `shows the report, live values update in place, advanced is collapsed`() {
        val deps = FakeGnssStatusDeps()
        val (_, sheet) = open(deps)
        val root = sheet.requireView()
        assertEquals("High accuracy", root.findViewById<TextView>(R.id.gnss_report_headline).text.toString())
        val all = texts(root)
        assertTrue(all.toString(), all.contains("Receiver & connection"))
        assertTrue(all.contains("1.0 s"))
        assertTrue("advanced header shown", all.any { it.startsWith("Advanced diagnostics") })
        assertFalse("advanced rows collapsed", all.contains("NMEA stream"))

        // Same shape, new value: the row view is reused (in-place update)
        val rows = root.findViewById<LinearLayout>(R.id.gnss_report_rows)
        val ageRow = findRow(rows, "Correction age")
        assertNotNull(ageRow)
        deps.frames.value = GnssReportFixtures.frame(ReceiverUiFixtures.fix(diffAge = 3.4))
        idleMain()
        val after = findRow(rows, "Correction age")
        assertSame(ageRow, after)
        assertEquals("3.4 s", after!!.findViewById<TextView>(R.id.text_detail_value).text.toString())
    }

    @Test fun `warning headline, coordinate toggle and receiver settings`() {
        val deps = FakeGnssStatusDeps(frames = MutableStateFlow(GnssReportFixtures.frame(ReceiverUiFixtures.fix(diffAge = 20.0), corr = CorrectionHealth.STALE)))
        val (_, sheet) = open(deps)
        val root = sheet.requireView()
        assertEquals("Corrections stale", root.findViewById<TextView>(R.id.gnss_report_headline).text.toString())
        val toggle = root.findViewById<View>(R.id.btn_gnss_report_coords)
        assertEquals(View.VISIBLE, toggle.visibility)
        assertEquals("Show lat/lon in toolbar", (toggle as TextView).text.toString())
        toggle.performClick()
        assertEquals(1, deps.toggles)
        // a11y: caution / warning rows say so in words
        val health = findRow(root.findViewById(R.id.gnss_report_rows), "Health")!!
        assertTrue(health.contentDescription.toString(), health.contentDescription.toString().contains("warning, "))
        root.findViewById<View>(R.id.btn_gnss_report_receiver).performClick()
        idleMain()
        assertEquals(1, deps.receiverOpens)
    }

    @Test fun `expanding advanced shows diagnostics`() {
        val (_, sheet) = open(FakeGnssStatusDeps())
        val root = sheet.requireView()
        val rows = root.findViewById<LinearLayout>(R.id.gnss_report_rows)
        val header = allViews(rows).filterIsInstance<TextView>().first { it.text.startsWith("Advanced diagnostics") }
        header.performClick()
        idleMain()
        assertTrue(texts(root).contains("10 lines/s"))
    }

    private fun allViews(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()

    /** The row root (item_receiver_detail_row) whose label reads [label]. */
    private fun findRow(container: View, label: String): View? = allViews(container)
        .firstOrNull { it is TextView && it.id == R.id.text_detail_label && it.text.toString() == label }
        ?.parent?.parent as? View
}
