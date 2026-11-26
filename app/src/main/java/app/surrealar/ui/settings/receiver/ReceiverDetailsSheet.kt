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
 * address; "Copy address" copies it only on the user's explicit tap. The receiver-reported vendor details (uptime,
 * temperature, power, storage) are a collapsed section. "Disconnect receiver" is always confirmed: it removes the
 * saved address/port and, during a capture, stops it.
 *
 * Live updates (NMEA rate, correction age, battery…) change row VALUES in place: the row views are kept, keyed by
 * section + label ([DetailsRowKeys]), so scroll position, expanded sections and accessibility focus survive. The views
 * are rebuilt only when the layout itself changes (rows added/removed, a section expanded, one/two columns).
 */
class ReceiverDetailsSheet : BottomSheetDialogFragment() {

    private lateinit var deps: ReceiverUiDeps
    private var rows: LinearLayout? = null
    private var lastSections: List<DetailSection> = emptyList()
    /** Layout the current views were built for; null = nothing built yet. */
    private var builtShape: List<DetailsRowKeys.SectionShape>? = null
    private var builtTwoColumns = false
    /** Row views by key (see [DetailsRowKeys]), for in-place value updates. */
    private val rowViews = mutableMapOf<String, View>()
    /** Collapsible section headers by title, to restore accessibility focus after a toggle. */
    private val headerViews = mutableMapOf<Int, TextView>()
    /** Titles of the collapsible sections the user expanded (kept across rotation). */
    private val expanded = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deps = ReceiverUiDeps.from(requireContext())
        savedInstanceState?.getIntArray(KEY_EXPANDED)?.let { expanded += it.toList() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(KEY_EXPANDED, expanded.toIntArray())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_receiver_details, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.details_rows)
        view.findViewById<View>(R.id.btn_details_copy_summary).setOnClickListener { copySummary() }
        view.findViewById<View>(R.id.btn_details_copy_address).setOnClickListener { copyAddress() }
        view.findViewById<View>(R.id.btn_details_diagnostics).setOnClickListener { openDiagnostics() }
        view.findViewById<View>(R.id.btn_details_disconnect).setOnClickListener { confirmDisconnect() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                inputs().collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        rows = null
        builtShape = null
        rowViews.clear()
        headerViews.clear()
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            // Tablets: a wide sheet with two columns, so the details fit with little or no scrolling.
            val widthDp = if (twoColumns()) WIDE_MAX_WIDTH_DP else ReceiverPickerSheet.MAX_WIDTH_DP
            maxWidth = (widthDp * resources.displayMetrics.density).toInt()
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
        val shape = DetailsRowKeys.shape(sections, expanded)
        if (shape == builtShape && twoColumns() == builtTwoColumns && container.childCount > 0) {
            updateInPlace(container.context, sections)
        } else rebuild(container, sections)
    }

    /** Same layout: only the values changed. Each row view is updated where it is (no add/remove, no focus loss). */
    private fun updateInPlace(ctx: android.content.Context, sections: List<DetailSection>) {
        for (sec in sections) {
            if (sec.collapsible && sec.title !in expanded) continue
            DetailsRowKeys.keyed(sec).forEach { (key, row) -> rowViews[key]?.let { bindRow(ctx, it, row) } }
        }
    }

    /** Two columns from [ReceiverPickerSheet.EXPANDED_FROM_DP] (tablets); one column on phones. */
    private fun twoColumns(): Boolean = resources.configuration.screenWidthDp >= ReceiverPickerSheet.EXPANDED_FROM_DP

    private fun rebuild(container: LinearLayout, sections: List<DetailSection>, focusTitle: Int? = null) {
        val ctx = container.context
        // Keep the reader where it was: the scroll offset survives the rebuild, and the toggled header gets focus back.
        val scroll = view as? androidx.core.widget.NestedScrollView
        val scrollY = scroll?.scrollY ?: 0
        builtShape = DetailsRowKeys.shape(sections, expanded)
        builtTwoColumns = twoColumns()
        rowViews.clear()
        headerViews.clear()
        container.removeAllViews()
        restoreAfterRebuild(scroll, scrollY, focusTitle)
        if (!builtTwoColumns) { buildSections(container, sections); return }
        // Two balanced columns: whole sections, in order, each placed in the shorter column so far.
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cols = List(2) {
            LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                .also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        val split = ReceiverDetailsColumns.split(sections.map { sec ->
            1 + if (!sec.collapsible || sec.title in expanded) sec.rows.size else 0
        })
        cols.forEachIndexed { c, col -> buildSections(col, sections.filterIndexed { i, _ -> split[i] == c }) }
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildSections(container: LinearLayout, sections: List<DetailSection>) {
        val ctx = container.context
        val inflater = LayoutInflater.from(ctx)
        for (sec in sections) {
            val open = !sec.collapsible || sec.title in expanded
            (inflater.inflate(R.layout.item_receiver_picker_header, container, false) as TextView).also {
                val title = ctx.getString(sec.title)
                if (sec.collapsible) {
                    it.text = "$title ${if (open) "\u25BE" else "\u25B8"}"
                    it.minHeight = (48 * ctx.resources.displayMetrics.density).toInt()
                    it.contentDescription = ctx.getString(
                        if (open) R.string.details_section_collapse_cd else R.string.details_section_expand_cd, title)
                    it.setOnClickListener {
                        if (!expanded.remove(sec.title)) expanded += sec.title
                        rows?.let { c -> rebuild(c, lastSections, focusTitle = sec.title) }
                    }
                    headerViews[sec.title] = it
                } else it.text = title
                container.addView(it)
            }
            if (!open) continue
            for ((key, r) in DetailsRowKeys.keyed(sec)) {
                val v = inflater.inflate(R.layout.item_receiver_detail_row, container, false)
                bindRow(ctx, v, r)
                rowViews[key] = v
                container.addView(v)
            }
        }
    }

    /** Sets a row's texts, touching only those that changed (no needless relayout or accessibility events). */
    private fun bindRow(ctx: android.content.Context, v: View, r: DetailRow) {
        val label = r.labelOf(ctx)
        val origin = r.origin?.let { ReceiverDetailsPresenter.originText(ctx, it) }
        fun TextView.set(value: CharSequence?, visible: Boolean = true) {
            if (text?.toString() != value?.toString()) text = value
            if (isVisible != visible) isVisible = visible
        }
        v.findViewById<TextView>(R.id.text_detail_label).set(label)
        v.findViewById<TextView>(R.id.text_detail_value).set(r.value)
        v.findViewById<TextView>(R.id.text_detail_note).set(r.note, !r.note.isNullOrEmpty())
        v.findViewById<TextView>(R.id.text_detail_origin).set(origin, origin != null)
        val cd = listOfNotNull(label, r.value, origin, r.note).joinToString(", ")
        if (v.contentDescription?.toString() != cd) v.contentDescription = cd
    }

    private fun restoreAfterRebuild(scroll: androidx.core.widget.NestedScrollView?, scrollY: Int, focusTitle: Int?) {
        scroll?.post {
            if (rows == null) return@post
            scroll.scrollTo(0, scrollY)
            focusTitle?.let { headerViews[it] }?.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    /** H2: always confirmed (the saved address/port are removed); during a capture, says it stops the capture. */
    private fun confirmDisconnect() {
        val capture = deps.connection.state.value.captureActive
        ReceiverConfirmDialog.show(this, ReceiverConfirmDialog.Action.DISCONNECT,
            message = if (capture) R.string.details_disconnect_confirm_capture_message else R.string.details_disconnect_confirm_message,
            positive = R.string.details_disconnect_confirm,
            title = if (capture) R.string.receiver_capture_confirm_title else R.string.details_disconnect_confirm_title)
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
        private const val KEY_EXPANDED = "expanded_sections"
        /** Maximum sheet width with two columns (tablets). */
        const val WIDE_MAX_WIDTH_DP = 1200
    }
}

/**
 * Stable keys for the details rows (pure). A row is keyed by its section title and its label (resource id, or the
 * receiver-named label for vendor details), with a counter for repeated labels, so the same row keeps its view while
 * its value changes. [shape] is the layout the views depend on: when it is unchanged, values are updated in place.
 */
internal object DetailsRowKeys {
    data class SectionShape(val title: Int, val open: Boolean, val rowKeys: List<String>)

    fun keyed(sec: DetailSection): List<Pair<String, DetailRow>> {
        val seen = mutableMapOf<String, Int>()
        return sec.rows.map { r ->
            val base = "${sec.title}/" + (if (r.label != 0) "r${r.label}" else "t${r.labelText.orEmpty()}")
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            (if (n == 1) base else "$base#$n") to r
        }
    }

    fun shape(sections: List<DetailSection>, expanded: Set<Int>): List<SectionShape> = sections.map { sec ->
        val open = !sec.collapsible || sec.title in expanded
        SectionShape(sec.title, open, if (open) keyed(sec).map { it.first } else emptyList())
    }
}

/** Pure column assignment for the two-column details layout (tablets). */
internal object ReceiverDetailsColumns {
    /**
     * Column (0 or 1) for each section, given its height in rows ([weights], header included). Sections stay whole
     * and in order within each column; each goes to the column that is shorter so far (ties: the left one).
     */
    fun split(weights: List<Int>): List<Int> {
        val h = intArrayOf(0, 0)
        return weights.map { w -> (if (h[1] < h[0]) 1 else 0).also { h[it] += w } }
    }
}
