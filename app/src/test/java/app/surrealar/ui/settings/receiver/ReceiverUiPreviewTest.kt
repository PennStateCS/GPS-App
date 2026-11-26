package app.surrealar.ui.settings.receiver

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.ExternalReconnectable
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.MismatchReason
import app.surrealar.receiver.selection.ProfileMismatch
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.RecentReceiver
import app.surrealar.ui.settings.categories.ControllerHost
import app.surrealar.ui.settings.categories.mainScope
import com.google.android.material.color.MaterialColors
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Layout previews (not assertions): renders the receiver card states, the picker sheet, the manual dialog and the
 * receiver details in light and dark with native graphics, as PNGs under app/build/previews/. Skips (assumption
 * failure) when native rendering is not available on this machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ReceiverUiPreviewTest {

    private val outDir = File("build/previews").apply { mkdirs() }

    @After fun clear() { ReceiverUiDeps.override = null }

    private fun write(view: View, name: String, widthDp: Int) {
        val d = view.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = view.measuredHeight.coerceIn(1, (2400 * d).toInt())
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(runCatching { MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface) }.getOrDefault(Color.WHITE))
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("preview: ${File(outDir, "$name.png").absolutePath}")
    }

    private fun renderAll(theme: String) {
        try {
            renderAllUnchecked(theme)
        } catch (e: Throwable) {
            Assume.assumeNoException("native rendering unavailable", e)
        }
    }

    private fun renderAllUnchecked(theme: String) {
        val settings = UiFakeSettings()
        val fake = settings.core
        val provider = MutableStateFlow(ProviderChoice.INTERNAL)
        val ss = SourceSettings(provider, MutableStateFlow(emptyList()), MutableStateFlow(null))
        val coordinator = GnssSourceCoordinator(ss, settings, object : ExternalReconnectable { override fun reconnectNow(reason: String) {} })
        val link = MutableStateFlow(ConnectionState.CONNECTED)
        val deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
        val connection = ReceiverConnectionController(settings, coordinator, mainScope(), QueuedProbe(),
            activeProvider = ss.activeProvider, externalLink = link, deviceInfo = deviceInfo)
        val deps = TestUiDeps(connection, settings, deviceInfo = deviceInfo, battery = MutableStateFlow(ReceiverBattery(82, false)),
            fix = MutableStateFlow(ReceiverUiFixtures.fix()), sky = MutableStateFlow(ReceiverUiFixtures.sky(18, 24)))
        ReceiverUiDeps.override = deps
        fake.source.value = LocationSourceType.EXTERNAL
        fake.connType.value = ExternalConnectionType.TCP
        fake.host.value = "192.168.42.1"
        fake.port.value = 9001
        fake.profile.value = ExternalReceiverProfile.REACH_RS4_PRO
        fake.origin.value = ProfileOrigin.RECEIVER
        fake.recent.value = listOf(
            RecentReceiver("192.168.42.1", 9001, "reach_rs4_pro", "Base RS4 Pro", "Reach RS4 Pro", "SN123", "34.7", System.currentTimeMillis() - 3_600_000),
            RecentReceiver("192.168.42.7", 9001, "reach_rs2_plus", "Rover", null, lastConnectedMs = System.currentTimeMillis() - 86_400_000),
        )
        coordinator.activateExternalTcpProvider("192.168.42.1", 9001, "preview")
        deviceInfo.value = ReceiverDeviceInfo(host = "192.168.42.1", name = "Base RS4 Pro", model = "Reach RS4 Pro", firmware = "34.7", serial = "SN123")
        idleMain()

        val host = ControllerHost()
        val ctx = ContextThemeWrapper(host.activity, R.style.Theme_SurveyingApp)

        // Card states.
        val caps = ReceiverCapabilities.ALL
        val verified = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO, ProfileOrigin.RECEIVER, ReceiverUiFixtures.rs4ProIdentity())
        val mm = ProfileMismatch("192.168.42.1", "Reach RS4 Pro", ExternalReceiverProfile.REACH_RS4_PRO,
            ExternalReceiverProfile.REACH_RS2_PLUS, MismatchReason.PROFILE_SET_BY_USER)
        val states = mapOf(
            "tablet" to ReceiverCardPresenter.present(ReceiverUiFixtures.internal,
                ReceiverUiFixtures.fix(io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL, io.github.pennstatecs.geokit.gnss.model.RtkStatus.SINGLE),
                ReceiverUiFixtures.sky(9, 22), null, ReceiverCapabilities.NONE),
            "testing" to ReceiverCardPresenter.present(verified.copy(
                attempt = ConnectionAttempt.Testing(1, ReceiverTarget("192.168.42.7", 9001), null)),
                ReceiverUiFixtures.fix(), ReceiverUiFixtures.sky(18, 24), ReceiverBattery(82, false), caps),
            "switch_failed" to ReceiverCardPresenter.present(verified.copy(
                attempt = ConnectionAttempt.Failed(3, ReceiverTarget("192.168.42.7", 9001), ConnectError.Unreachable(9001))),
                ReceiverUiFixtures.fix(), ReceiverUiFixtures.sky(18, 24), ReceiverBattery(82, false), caps),
            "connected" to ReceiverCardPresenter.present(verified, ReceiverUiFixtures.fix(), ReceiverUiFixtures.sky(18, 24), ReceiverBattery(82, false), caps),
            "failed" to ReceiverCardPresenter.present(ReceiverUiFixtures.connected().copy(activeProvider = ProviderChoice.INTERNAL,
                attempt = ConnectionAttempt.Failed(2, ReceiverUiFixtures.TARGET, ConnectError.NoData(9001))), null, null, null, caps),
            "selected" to ReceiverCardPresenter.present(ReceiverUiFixtures.connected().copy(activeProvider = ProviderChoice.INTERNAL,
                link = null), null, null, null, caps),
            "connected_no_position" to ReceiverCardPresenter.present(ReceiverUiFixtures.connected(origin = ProfileOrigin.USER), null, null, null, caps),
            "mismatch" to ReceiverCardPresenter.present(ReceiverUiFixtures.connected(origin = ProfileOrigin.USER).copy(mismatch = mm),
                ReceiverUiFixtures.fix(rtk = io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT), ReceiverUiFixtures.sky(16, 22), ReceiverBattery(15, true), caps),
        )
        for ((name, model) in states) {
            val frame = FrameLayout(ctx).apply { setPadding(32, 32, 32, 0) }
            val v = LayoutInflater.from(ctx).inflate(R.layout.view_receiver_card, frame, false)
            frame.addView(v)
            ReceiverCardBinder(v).bind(model)
            write(frame, "receiver_card_${name}_$theme", 411)
        }

        // Picker sheet.
        val picker = ReceiverPickerSheet()
        picker.show(host.fragment.childFragmentManager, ReceiverPickerSheet.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
        write(picker.requireView(), "receiver_picker_$theme", 411)
        picker.dismiss(); idleMain()

        // Manual dialog (whole dialog window).
        val manual = ManualReceiverDialog()
        manual.show(host.fragment.childFragmentManager, ManualReceiverDialog.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
        write((manual.dialog as AlertDialog).window!!.decorView, "receiver_manual_$theme", 560)
        manual.dismiss(); idleMain()

        // Details.
        val details = ReceiverDetailsSheet()
        details.show(host.fragment.childFragmentManager, ReceiverDetailsSheet.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
        write(details.requireView(), "receiver_details_$theme", 411)
        details.dismiss(); idleMain()

        // Tablet width (SM-X940 landscape content pane, ~640 dp sheet).
        val tabletPicker = ReceiverPickerSheet()
        tabletPicker.show(host.fragment.childFragmentManager, ReceiverPickerSheet.TAG)
        host.fragment.childFragmentManager.executePendingTransactions()
        idleMain()
        write(tabletPicker.requireView(), "receiver_picker_640dp_$theme", 640)
        tabletPicker.dismiss(); idleMain()

        renderSettingsPage(ctx, theme, states.getValue("connected"))
        renderHomeCards(ctx, theme)
    }

    /** Settings → Receiver page (RS4 Pro, external): Advanced collapsed and expanded, phone and tablet widths. */
    private fun renderSettingsPage(ctx: android.content.Context, theme: String, card: ReceiverCardModel) {
        for ((suffix, expanded) in listOf("collapsed" to false, "advanced" to true)) {
            for (width in listOf(411, 640)) {
                runCatching {
                    val page = LayoutInflater.from(ctx).inflate(R.layout.content_settings_location, FrameLayout(ctx), false)
                    ReceiverCardBinder(page.findViewById(R.id.receiver_card)).bind(card)
                    listOf(R.id.card_external_receiver_setup, R.id.group_receiver_tilt, R.id.group_advanced_external)
                        .forEach { page.findViewById<View>(it).visibility = View.VISIBLE }
                    page.findViewById<View>(R.id.group_receiver_advanced).visibility = if (expanded) View.VISIBLE else View.GONE
                    page.findViewById<View>(R.id.image_receiver_advanced_chevron).rotation = if (expanded) 270f else 90f
                    page.findViewById<android.widget.TextView>(R.id.text_receiver_tilt_status).text =
                        "Tilt: not reported. Positions: antenna (unconfirmed)."
                    page.findViewById<android.widget.EditText>(R.id.edit_antenna_height).setText("1.800")
                    page.findViewById<android.widget.EditText>(R.id.edit_camera_below_receiver).setText("0.250")
                    page.findViewById<android.widget.EditText>(R.id.edit_phase_center_offset).setText("0.095")
                    page.findViewById<android.widget.AutoCompleteTextView>(R.id.dropdown_compensated_output).setText("Unknown")
                    write(page, "receiver_settings_${suffix}_${width}dp_$theme", width)
                }.onFailure { println("preview skipped: receiver settings $suffix $width: $it") }
            }
        }
    }

    /**
     * Home Field Status card (the card from fragment_home, rendered on its own) for representative mapper outputs, at
     * phone width and the narrow tablet column (~360 dp). Mirrors HomeFragment.renderFieldStatus.
     */
    private fun renderHomeCards(ctx: android.content.Context, theme: String) {
        val M = app.surrealar.ui.home.HomeFieldStatusMapper
        fun inputs(rtk: io.github.pennstatecs.geokit.gnss.model.RtkStatus, h: Double?, live: Boolean = true,
                   link: app.surrealar.ui.home.HomeLiveSource.Link = app.surrealar.ui.home.HomeLiveSource.Link.CONNECTED,
                   ref: app.surrealar.ui.home.HomeFieldStatusMapper.PositionReference? = null, vInvalid: Boolean = false,
                   age: Double? = 1.0, errors: Long = 0) = app.surrealar.ui.home.HomeFieldStatusMapper.Inputs(
            isInternal = false, externalLabel = "RS4 Pro", receiverName = "Base RS4 Pro", connectionAddress = "192.168.42.1:9001",
            rtkStatus = rtk, hAccM = h, vAccM = if (vInvalid) 999.0 else 0.021, satsUsed = 18, satsVisible = 24, skyTotalUsed = 18,
            skyTotalVisible = 24, hdop = 0.7, pdop = 1.3, correctionAgeS = age, correctionStationId = "1116", nmeaLinesPerSecond = 12.0,
            nmeaParseErrors = errors, hasRecentFix = live, receiverModel = "Emlid Reach RS4 Pro", vAccInvalid = vInvalid,
            positionReference = ref,
        ).copy(link = link)
        val Fix = io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX
        val cases = mapOf(
            "high_accuracy" to M.map(inputs(Fix, 0.012, ref = app.surrealar.ui.home.HomeFieldStatusMapper.PositionReference.ANTENNA)),
            "warnings" to M.map(inputs(Fix, 0.012, ref = app.surrealar.ui.home.HomeFieldStatusMapper.PositionReference.UNCONFIRMED,
                vInvalid = true, age = 22.0, errors = 3)),
            "fix_unverified" to M.map(inputs(Fix, null)),
            "connecting" to M.map(inputs(Fix, null, live = false, link = app.surrealar.ui.home.HomeLiveSource.Link.CONNECTING)),
            "no_data" to M.map(inputs(Fix, null, live = false, link = app.surrealar.ui.home.HomeLiveSource.Link.UNAVAILABLE)),
        )
        for ((name, status) in cases) for (width in listOf(411, 360)) runCatching {
            val home = LayoutInflater.from(ctx).inflate(R.layout.fragment_home, FrameLayout(ctx), false)
            val headline = home.findViewById<android.widget.TextView>(R.id.text_field_status_headline)
            var card: View = headline
            while (card !is com.google.android.material.card.MaterialCardView) card = card.parent as View
            (card.parent as android.view.ViewGroup).removeView(card)
            val color = when (status.severity) {
                app.surrealar.ui.home.HomeFieldStatusMapper.Severity.GOOD -> ctx.getColor(R.color.app_success)
                app.surrealar.ui.home.HomeFieldStatusMapper.Severity.CAUTION -> ctx.getColor(R.color.app_warning)
                app.surrealar.ui.home.HomeFieldStatusMapper.Severity.WARNING -> ctx.getColor(R.color.app_error)
                app.surrealar.ui.home.HomeFieldStatusMapper.Severity.NEUTRAL -> MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            }
            headline.text = status.headline
            headline.setTextColor(color)
            card.findViewById<View>(R.id.field_status_dot).backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            card.findViewById<android.widget.TextView>(R.id.text_field_status_warnings).apply {
                text = "Also: " + status.additionalWarnings.joinToString(" · ")
                visibility = if (status.additionalWarnings.isEmpty()) View.GONE else View.VISIBLE
            }
            card.findViewById<android.widget.TextView>(R.id.text_field_status_detail_primary).text = status.primaryDetail
            card.findViewById<android.widget.TextView>(R.id.text_field_status_receiver).text = status.receiverDetail
            card.findViewById<android.widget.TextView>(R.id.btn_field_status_action).text = status.actionLabel
            val group = card.findViewById<com.google.android.material.chip.ChipGroup>(R.id.group_field_status_chips)
            for (chip in status.chips) {
                val v = LayoutInflater.from(ctx).inflate(R.layout.item_home_status_chip, group, false) as com.google.android.material.chip.Chip
                v.text = chip.label
                group.addView(v)
            }
            val frame = FrameLayout(ctx).apply { setPadding(24, 24, 24, 24); addView(card) }
            write(frame, "home_field_status_${name}_${width}dp_$theme", width)
        }.onFailure { println("preview skipped: home $name $width: $it") }
    }

    @Test fun `render light`() = renderAll("light")

    @Test fun `render dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        renderAll("dark")
    }
}
