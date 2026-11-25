package app.surrealar.ui.settings.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Receiver details (Phase 5), opened from the receiver card: model, serial, firmware, battery, RTK corrections,
 * coordinate-frame and height settings, antenna and tilt settings, troubleshooting. Each row says where its value
 * comes from (Reported by receiver / Set by you / Default / Unknown). Copy summary never includes the receiver's
 * address; "Copy address" copies it only on the user's explicit tap.
 */
class ReceiverDetailsSheet : BottomSheetDialogFragment() {

    private lateinit var deps: ReceiverUiDeps
    private var rows: LinearLayout? = null
    private var lastSections: List<DetailSection> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deps = ReceiverUiDeps.from(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_receiver_details, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.details_rows)
        view.findViewById<View>(R.id.btn_details_copy_summary).setOnClickListener { copySummary() }
        view.findViewById<View>(R.id.btn_details_copy_address).setOnClickListener { copyAddress() }
        view.findViewById<View>(R.id.btn_details_diagnostics).setOnClickListener { openDiagnostics() }
        view.findViewById<View>(R.id.btn_details_disconnect).setOnClickListener {
            deps.connection.disconnect("receiver-details")
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                inputs().collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        rows = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            maxWidth = (ReceiverPickerSheet.MAX_WIDTH_DP * resources.displayMetrics.density).toInt()
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun <T> safe(name: String, f: () -> Flow<T>?, default: T): Flow<T> =
        (runCatching { f() }.getOrNull() ?: flowOf(default))
            .catch { e -> DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver details could not read $name", e); emit(default) }
            .onStart { emit(default) }

    private fun inputs(): Flow<DetailsInputs> {
        val d = deps
        val dev = combine(
            safe("deviceInfo", { d.devices?.deviceInfo }, null),
            safe("battery", { d.devices?.battery }, null),
            safe("corrections", { d.devices?.corrections }, null),
            safe("capabilities", { d.devices?.profileCapabilities }, ReceiverCapabilities.NONE),
            safe("fix", { d.fixSwitchboard?.currentFix }, null),
        ) { info, bat, corr, caps, fix -> DevicePart(info, bat, corr, caps, fix) }
        val set = combine(
            safe("coordinates", { d.settings.coordinateSystemSettings }, CoordinateSystemSettings()),
            safe("receiverSettings", { d.settings.gnssReceiverSettings }, GnssReceiverSettings()),
            safe("compensated", { d.settings.compensatedNmeaOutput }, CompensatedOutputSetting.DEFAULT),
            safe("nmeaRate", { d.nmeaLinesPerSecond }, 0.0),
        ) { c, r, comp, rate -> SettingsPart(c, r, comp, rate) }
        return combine(d.connection.state, dev, set) { s, dv, st ->
            DetailsInputs(s, dv.info, dv.battery, dv.corrections, dv.caps, dv.fix, st.coords, st.receiver, st.compensated, st.rate)
        }
    }

    private data class DevicePart(
        val info: io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo?,
        val battery: io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery?,
        val corrections: io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections?,
        val caps: ReceiverCapabilities,
        val fix: io.github.pennstatecs.geokit.gnss.model.Fix?,
    )
    private data class SettingsPart(val coords: CoordinateSystemSettings, val receiver: GnssReceiverSettings,
                                    val compensated: CompensatedOutputSetting, val rate: Double)

    private fun render(i: DetailsInputs) {
        val container = rows ?: return
        val ctx = container.context
        val sections = ReceiverDetailsPresenter.sections(ctx, i)
        view?.findViewById<View>(R.id.btn_details_copy_address)?.isVisible = i.state.savedTarget != null &&
            i.state.selectedSource == LocationSourceType.EXTERNAL
        view?.findViewById<View>(R.id.btn_details_disconnect)?.isVisible = i.state.selectedSource == LocationSourceType.EXTERNAL
        if (sections == lastSections && container.childCount > 0) return
        lastSections = sections
        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for (sec in sections) {
            (inflater.inflate(R.layout.item_receiver_picker_header, container, false) as TextView).also {
                it.setText(sec.title)
                container.addView(it)
            }
            for (r in sec.rows) {
                val v = inflater.inflate(R.layout.item_receiver_detail_row, container, false)
                val label = ctx.getString(r.label)
                v.findViewById<TextView>(R.id.text_detail_label).text = label
                v.findViewById<TextView>(R.id.text_detail_value).text = r.value
                v.findViewById<TextView>(R.id.text_detail_note).apply { text = r.note; isVisible = !r.note.isNullOrEmpty() }
                val origin = r.origin?.let { ReceiverDetailsPresenter.originText(ctx, it) }
                v.findViewById<TextView>(R.id.text_detail_origin).apply { text = origin; isVisible = origin != null }
                v.contentDescription = listOfNotNull(label, r.value, origin, r.note).joinToString(", ")
                container.addView(v)
            }
        }
    }

    private fun copySummary() {
        val ctx = context ?: return
        val text = ReceiverDetailsPresenter.summary(ctx, lastSections)
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Receiver summary", text))
        Toast.makeText(ctx, R.string.details_copied, Toast.LENGTH_SHORT).show()
    }

    /** Copies the receiver's address: only when the user explicitly taps "Copy address". */
    private fun copyAddress() {
        val ctx = context ?: return
        val target = deps.connection.state.value.savedTarget ?: return
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Receiver address", target.toString()))
        Toast.makeText(ctx, R.string.details_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openDiagnostics() {
        val ok = runCatching {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.nav_development)
        }.onFailure { DiagnosticsLogger.w(DiagTags.NAV, "Receiver details: diagnostics page navigation failed", it) }.isSuccess
        if (ok) dismissAllowingStateLoss()
        else context?.let { Toast.makeText(it, R.string.details_diagnostics_unavailable, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val TAG = "ReceiverDetailsSheet"
    }
}
