package app.surrealar.ui.toolbar.report

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
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.ui.home.RecentCountWindow
import app.surrealar.ui.settings.receiver.ReceiverPickerSheet
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The GNSS Status report: a Material 3 bottom sheet opened from the global GNSS toolbar (tap anywhere on it, or the
 * chevron) and from the AR GNSS chip. Dismissed by swipe-down, Back or a tap outside; the screen underneath is
 * unchanged.
 *
 * Live updates: it collects existing app-wide flows only while visible (STARTED) — the toolbar's own latest frame
 * (fix, converted position, interpretation), receiver selection / live source, receiver battery / info /
 * corrections, tilt status and NMEA diagnostics. No new GNSS subscription and no polling. Row views are kept and
 * their texts updated in place (keyed by section + label), so scroll position, the expanded Advanced section and
 * accessibility focus survive; views are rebuilt only when rows appear or disappear.
 */
class GnssStatusSheet : BottomSheetDialogFragment() {

    private lateinit var deps: GnssStatusDeps
    private var rows: LinearLayout? = null
    private var last: GnssStatusReport? = null
    private var builtShape: List<String>? = null
    private var builtTwoColumns = false
    private val rowViews = mutableMapOf<String, View>()
    private val headerViews = mutableMapOf<String, TextView>()
    private val expanded = mutableSetOf<String>()
    private val parseErrorWindow = RecentCountWindow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deps = GnssStatusDeps.from(requireContext(), activity)
        savedInstanceState?.getStringArray(KEY_EXPANDED)?.let { expanded += it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(KEY_EXPANDED, expanded.toTypedArray())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_gnss_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.gnss_report_rows)
        view.findViewById<View>(R.id.btn_gnss_report_copy).setOnClickListener { copyStatus() }
        view.findViewById<View>(R.id.btn_gnss_report_coords).setOnClickListener { deps.toggleCoordinates() }
        view.findViewById<View>(R.id.btn_gnss_report_receiver).setOnClickListener {
            deps.openReceiverSettings()
            dismissAllowingStateLoss()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reports().collect { render(it) }
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
            val widthDp = if (twoColumns()) WIDE_MAX_WIDTH_DP else ReceiverPickerSheet.MAX_WIDTH_DP
            maxWidth = (widthDp * resources.displayMetrics.density).toInt()
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun <T> safe(name: String, f: () -> Flow<T>, default: T): Flow<T> =
        (runCatching { f() }.getOrElse { kotlinx.coroutines.flow.flowOf(default) })
            .catch { e -> DiagnosticsLogger.w(DiagTags.GNSS, "GNSS status report could not read $name", e); emit(default) }
            .onStart { emit(default) }

    private fun reports(): Flow<GnssStatusReport> {
        val d = deps
        val device = combine(
            safe("battery", { d.battery }, null),
            safe("deviceInfo", { d.deviceInfo }, null),
            safe("corrections", { d.corrections }, null),
        ) { b, info, c -> Triple(b, info, c) }
        val status = combine(
            safe("tilt", { d.tilt }, TiltStatus.NONE),
            safe("diagnostics", { d.diagnostics }, DiagnosticData()),
            safe("receiver", { d.receiverSettings }, null as ExternalReceiverSettings?),
        ) { t, diag, r -> Triple(t, diag, r) }
        return combine(d.frames, d.selection, d.liveSource, device, status) { frame, sel, live, dev, st ->
            val recent = parseErrorWindow.update(android.os.SystemClock.elapsedRealtime(), st.second.totalParseErrors)
            GnssReportInputs(frame, sel, live, st.third, dev.first, dev.second, dev.third, st.first, st.second, recent)
        }.conflate().map { GnssStatusReportPresenter.build(it) }
            .flowOn(d.computeDispatcher)   // build off the main thread (review L6)
            .distinctUntilChanged()
    }

    private fun render(r: GnssStatusReport) {
        val container = rows ?: return
        val v = view ?: return
        last = r
        val ctx = container.context
        v.findViewById<TextView>(R.id.gnss_report_headline).apply {
            setIfChanged(r.headline)
            setTextColor(levelColor(this, r.level, androidx.appcompat.R.attr.colorPrimary))
        }
        v.findViewById<TextView>(R.id.gnss_report_warnings).apply {
            val text = r.warnings.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Also: ")
            setIfChanged(text.orEmpty())
            isVisible = text != null
        }
        v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_gnss_report_coords).apply {
            isVisible = r.coordToggleAvailable
            val label = ctx.getString(if (r.showingLatLon) R.string.gnss_report_show_grid else R.string.gnss_report_show_latlon)
            setIfChanged(label)
            contentDescription = ctx.getString(R.string.gnss_report_coord_toggle_cd,
                if (r.showingLatLon) "latitude and longitude" else "grid coordinates")
        }
        val shape = shapeOf(r)
        if (shape == builtShape && twoColumns() == builtTwoColumns && container.childCount > 0) updateInPlace(r)
        else rebuild(container, r)
    }

    private fun shapeOf(r: GnssStatusReport): List<String> = r.sections.flatMap { s ->
        val open = !s.collapsible || s.id in expanded
        listOf("#${s.id}:$open") + if (open) keyed(s).map { it.first } else emptyList()
    }

    private fun keyed(s: ReportSection): List<Pair<String, ReportRow>> {
        val seen = mutableMapOf<String, Int>()
        return s.rows.map { row ->
            val base = "${s.id}/${row.label}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            (if (n == 1) base else "$base#$n") to row
        }
    }

    private fun updateInPlace(r: GnssStatusReport) {
        for (s in r.sections) {
            if (s.collapsible && s.id !in expanded) continue
            keyed(s).forEach { (k, row) -> rowViews[k]?.let { bindRow(it, row) } }
        }
    }

    private fun twoColumns(): Boolean = resources.configuration.screenWidthDp >= ReceiverPickerSheet.EXPANDED_FROM_DP

    private fun rebuild(container: LinearLayout, r: GnssStatusReport, focusSection: String? = null) {
        val ctx = container.context
        val scroll = view as? androidx.core.widget.NestedScrollView
        val scrollY = scroll?.scrollY ?: 0
        builtShape = shapeOf(r)
        builtTwoColumns = twoColumns()
        rowViews.clear()
        headerViews.clear()
        container.removeAllViews()
        if (!builtTwoColumns) buildSections(container, r.sections)
        else {
            // Tablets: two balanced columns of whole sections (same split as Receiver details).
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val cols = List(2) {
                LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    .also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            }
            val split = app.surrealar.ui.settings.receiver.ReceiverDetailsColumns.split(r.sections.map { s ->
                1 + if (!s.collapsible || s.id in expanded) s.rows.size else 0
            })
            cols.forEachIndexed { c, col -> buildSections(col, r.sections.filterIndexed { i, _ -> split[i] == c }) }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll?.post {
            if (rows == null) return@post
            scroll.scrollTo(0, scrollY)
            focusSection?.let { headerViews[it] }?.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun buildSections(container: LinearLayout, sections: List<ReportSection>) {
        val ctx = container.context
        val inflater = LayoutInflater.from(ctx)
        for (s in sections) {
            val open = !s.collapsible || s.id in expanded
            (inflater.inflate(R.layout.item_receiver_picker_header, container, false) as TextView).also {
                if (s.collapsible) {
                    it.text = "${s.title} ${if (open) "▾" else "▸"}"
                    it.minHeight = (48 * ctx.resources.displayMetrics.density).toInt()
                    it.contentDescription = ctx.getString(
                        if (open) R.string.gnss_report_section_collapse_cd else R.string.gnss_report_section_expand_cd, s.title)
                    it.setOnClickListener {
                        if (!expanded.remove(s.id)) expanded += s.id
                        val c = rows; val r = last
                        if (c != null && r != null) rebuild(c, r, focusSection = s.id)
                    }
                    headerViews[s.id] = it
                } else it.text = s.title
                container.addView(it)
            }
            if (!open) continue
            for ((key, row) in keyed(s)) {
                val v = inflater.inflate(R.layout.item_receiver_detail_row, container, false)
                bindRow(v, row)
                rowViews[key] = v
                container.addView(v)
            }
        }
    }

    /** Sets a row's texts, touching only those that changed (no needless relayout or accessibility events). */
    private fun bindRow(v: View, r: ReportRow) {
        v.findViewById<TextView>(R.id.text_detail_label).setIfChanged(r.label)
        val value = v.findViewById<TextView>(R.id.text_detail_value)
        // Caution / warning rows also carry a ⚠ mark, so the meaning never depends on colour alone.
        value.setIfChanged(if (r.level == ReportLevel.CAUTION || r.level == ReportLevel.WARNING) "⚠ ${r.value}" else r.value)
        value.setTextColor(levelColor(value, r.level, com.google.android.material.R.attr.colorOnSurface))
        v.findViewById<TextView>(R.id.text_detail_note).apply { setIfChanged(r.note.orEmpty()); isVisible = !r.note.isNullOrEmpty() }
        v.findViewById<TextView>(R.id.text_detail_origin).isVisible = false
        val cd = listOfNotNull(r.label, (if (r.level == ReportLevel.WARNING) "warning, " else if (r.level == ReportLevel.CAUTION) "caution, " else "") + r.value, r.note)
            .joinToString(", ")
        if (v.contentDescription?.toString() != cd) v.contentDescription = cd
    }

    private fun TextView.setIfChanged(s: CharSequence) { if (text?.toString() != s.toString()) text = s }

    /** Semantic status roles from the theme (docs/design-system.md): ≥ 4.5:1 on the sheet surface, light and dark. */
    private fun levelColor(v: View, level: ReportLevel, defaultAttr: Int): Int = when (level) {
        ReportLevel.WARNING -> MaterialColors.getColor(v, R.attr.colorStatusError)
        ReportLevel.CAUTION -> MaterialColors.getColor(v, R.attr.colorStatusWarning)
        ReportLevel.GOOD -> MaterialColors.getColor(v, R.attr.colorStatusSuccess)
        ReportLevel.NORMAL -> MaterialColors.getColor(v, defaultAttr)
    }

    private fun copyStatus() {
        val ctx = context ?: return
        val r = last ?: return
        val ts = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val text = GnssStatusReportPresenter.snapshotText(r, ts)
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("GNSS status", text))
        Toast.makeText(ctx, R.string.gnss_report_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "GnssStatusSheet"
        private const val KEY_EXPANDED = "gnss_report_expanded"
        /** Maximum sheet width with two columns (tablets). */
        const val WIDE_MAX_WIDTH_DP = 1200
    }
}
