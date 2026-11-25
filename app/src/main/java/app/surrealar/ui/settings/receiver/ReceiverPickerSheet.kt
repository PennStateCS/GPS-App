package app.surrealar.ui.settings.receiver

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.receiver.selection.ConnectRequest
import app.surrealar.receiver.selection.ConnectStart
import app.surrealar.receiver.selection.NetworkScanState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

/**
 * "Change receiver" (Phase 5): Tablet GNSS, receivers on this network (Emlid scan), previously connected receivers and
 * "Connect manually…". Every action goes through the app-scoped [app.surrealar.receiver.selection.ReceiverConnectionController]
 * (test before switch), so opening or dismissing the sheet never disconnects or cancels anything. A running capture
 * is confirmed before switching. Half-expanded on compact widths, expanded and at most 640 dp wide from 840 dp.
 */
class ReceiverPickerSheet : BottomSheetDialogFragment() {

    private lateinit var deps: ReceiverUiDeps
    private var rows: LinearLayout? = null
    private var autoScanDone = false
    private var lastItems: List<PickerItem>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deps = ReceiverUiDeps.from(requireContext())
        autoScanDone = savedInstanceState?.getBoolean(KEY_AUTO_SCANNED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_SCANNED, autoScanDone)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_receiver_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.picker_rows)
        val connection = deps.connection
        // Scan on open only for an Emlid profile: a Generic NMEA user never triggers the Emlid sweep implicitly.
        if (!autoScanDone) {
            autoScanDone = true
            val s = connection.state.value
            if (ReceiverPickerRows.autoScan(s) && s.scan !is NetworkScanState.Scanning) connection.scanNetwork()
        }
        render(connection.state.value)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connection.state.collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        rows = null
        lastItems = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val density = resources.displayMetrics.density
        BottomSheetBehavior.from(sheet).apply {
            maxWidth = (MAX_WIDTH_DP * density).toInt()
            if (resources.configuration.screenWidthDp >= EXPANDED_FROM_DP) {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                isFitToContents = false
                halfExpandedRatio = 0.6f
                state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
    }

    // ───────────────────────────── rendering ─────────────────────────────

    private fun render(s: ReceiverSelectionState) {
        val container = rows ?: return
        val items = ReceiverPickerRows.build(s)
        // Rebuild only when something shown changed (keeps TalkBack focus on unrelated state updates).
        if (items == lastItems && container.childCount > 0) return
        lastItems = items
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        for (item in items) {
            container.addView(when (item) {
                is PickerItem.Header -> header(inflater, container, item)
                is PickerItem.Tablet -> row(inflater, container, getString(R.string.receiver_source_tablet),
                    getString(R.string.picker_tablet_subtitle), item.status, R.drawable.ic_satellite_24) { onTablet() }
                is PickerItem.Scan -> scanRow(inflater, container, item)
                is PickerItem.Scanned -> row(inflater, container, item.receiver.label,
                    "${item.receiver.host}:${item.receiver.port} · ${item.receiver.description}", item.status,
                    R.drawable.ic_satellite_24) {
                    connect(ConnectRequest(item.receiver.host, item.receiver.port,
                        // The discovered name (hostname), not the IP fallback, as the saved name.
                        name = item.receiver.label.takeIf { it != item.receiver.host }.orEmpty()))
                }
                is PickerItem.Recent -> recentRow(inflater, container, item)
                PickerItem.RecentEmpty -> (inflater.inflate(R.layout.item_receiver_picker_header, container, false) as TextView).apply {
                    setText(R.string.picker_recent_none)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    isFocusable = true
                }
                PickerItem.Manual -> row(inflater, container, getString(R.string.picker_manual),
                    getString(R.string.picker_manual_subtitle), null, R.drawable.ic_edit_24) { openManual() }
            })
        }
    }

    private fun header(inflater: LayoutInflater, parent: ViewGroup, h: PickerItem.Header): View =
        (inflater.inflate(R.layout.item_receiver_picker_header, parent, false) as TextView).apply {
            setText(when (h.section) {
                PickerItem.Section.TABLET -> R.string.picker_section_tablet
                PickerItem.Section.NETWORK -> R.string.picker_section_network
                PickerItem.Section.RECENT -> R.string.picker_section_recent
            })
        }

    private fun row(
        inflater: LayoutInflater, parent: ViewGroup, title: String, subtitle: String?, status: PickerRowStatus?,
        icon: Int, onClick: () -> Unit,
    ): View {
        val v = inflater.inflate(R.layout.item_receiver_picker_row, parent, false)
        v.findViewById<ImageView>(R.id.image_picker_row).setImageResource(icon)
        v.findViewById<TextView>(R.id.text_picker_row_title).text = title
        v.findViewById<TextView>(R.id.text_picker_row_subtitle).apply { text = subtitle; isVisible = !subtitle.isNullOrEmpty() }
        val st = v.findViewById<TextView>(R.id.text_picker_row_status)
        val statusText = status?.let { statusText(it) }
        st.isVisible = statusText != null
        st.text = statusText
        v.contentDescription = listOfNotNull(title, subtitle, statusText).joinToString(", ")
        v.setOnClickListener { onClick() }
        return v
    }

    private fun recentRow(inflater: LayoutInflater, parent: ViewGroup, item: PickerItem.Recent): View {
        val r = item.receiver
        val title = r.name ?: r.host
        val model = r.model?.let { getString(R.string.picker_recent_model, it) }
            ?: item.profile?.let { getString(R.string.picker_recent_profile, it.label) }
        val last = if (r.lastConnectedMs > 0) getString(R.string.picker_last_connected,
            DateUtils.getRelativeTimeSpanString(r.lastConnectedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)) else null
        val subtitle = listOfNotNull("${r.host}:${r.port}", model, last).joinToString("\n")
        val v = row(inflater, parent, title, subtitle, item.status, R.drawable.ic_satellite_24) {
            // Reconnect with the profile it was connected with (null: keep the stored profile).
            connect(ConnectRequest(r.host, r.port, item.profile, r.name.orEmpty()))
        }
        v.findViewById<MaterialButton>(R.id.btn_picker_row_forget).apply {
            isVisible = true
            contentDescription = getString(R.string.picker_forget_cd, title)
            setOnClickListener {
                val connection = deps.connection
                lifecycleScope.launch {
                    try { connection.forgetRecent(r) } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        DiagnosticsLogger.w(DiagTags.RECEIVER, "Forget recent receiver failed", e)
                    }
                }
            }
        }
        return v
    }

    private fun scanRow(inflater: LayoutInflater, parent: ViewGroup, item: PickerItem.Scan): View {
        val v = inflater.inflate(R.layout.item_receiver_picker_scan, parent, false)
        val progress = v.findViewById<CircularProgressIndicator>(R.id.progress_picker_scan)
        val text = v.findViewById<TextView>(R.id.text_picker_scan)
        val button = v.findViewById<MaterialButton>(R.id.btn_picker_scan)
        val scanning = item.scan is NetworkScanState.Scanning
        progress.isVisible = scanning
        button.isEnabled = !scanning
        button.setText(if (item.scan is NetworkScanState.Done) R.string.picker_rescan else R.string.picker_scan)
        text.text = when (val sc = item.scan) {
            NetworkScanState.Idle -> getString(R.string.picker_scan_hint)
            NetworkScanState.Scanning -> getString(R.string.picker_scanning)
            is NetworkScanState.Failed -> sc.message
            is NetworkScanState.Done -> {
                val ago = getString(R.string.picker_last_scanned,
                    DateUtils.getRelativeTimeSpanString(sc.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                if (sc.results.isEmpty()) "${getString(R.string.picker_scan_none)}\n$ago" else ago
            }
        }
        button.setOnClickListener { deps.connection.scanNetwork(emlidScan = true) }
        return v
    }

    private fun statusText(s: PickerRowStatus): String = when (s) {
        PickerRowStatus.Active -> getString(R.string.picker_status_active)
        PickerRowStatus.Testing -> getString(R.string.picker_status_testing)
        PickerRowStatus.NotFoundInScan -> getString(R.string.picker_status_not_found)
        is PickerRowStatus.Failed -> getString(R.string.picker_status_failed, ReceiverUi.shortReason(requireContext(), s.error))
    }

    // ───────────────────────────── actions ─────────────────────────────

    private fun onTablet() {
        val connection = deps.connection
        val s = connection.state.value
        if (s.selectedSource == LocationSourceType.INTERNAL && !s.busy) return
        val go = {
            connection.selectInternal("picker")
            ReceiverUi.ensureLocationService(requireContext().applicationContext)
        }
        if (s.captureActive && s.selectedSource == LocationSourceType.EXTERNAL) {
            ReceiverUi.confirmCaptureStop(this, R.string.receiver_switch_tablet_confirm_message) { go() }
        } else go()
    }

    private fun connect(request: ConnectRequest) {
        startConnect(this, deps, request)
    }

    private fun openManual() {
        ManualReceiverDialog().show(parentFragmentManager, ManualReceiverDialog.TAG)
    }

    companion object {
        const val TAG = "ReceiverPickerSheet"
        private const val KEY_AUTO_SCANNED = "auto_scanned"
        const val MAX_WIDTH_DP = 640
        const val EXPANDED_FROM_DP = 840

        /**
         * Starts a test-before-switch connect and handles the synchronous answer: a running capture is confirmed
         * first ("Switching receivers stops the current capture"), then retried with confirmCaptureStop. Returns
         * the first answer.
         */
        internal fun startConnect(
            fragment: androidx.fragment.app.Fragment, deps: ReceiverUiDeps, request: ConnectRequest,
            onStarted: () -> Unit = {},
        ): ConnectStart {
            val connection = deps.connection
            val start = connection.connect(request)
            when (start) {
                ConnectStart.NEEDS_CAPTURE_CONFIRMATION -> ReceiverUi.confirmCaptureStop(fragment) {
                    connection.connect(request, confirmCaptureStop = true)
                    fragment.context?.let { ReceiverUi.ensureLocationService(it.applicationContext) }
                    onStarted()
                }
                ConnectStart.STARTED -> {
                    ReceiverUi.ensureLocationService(fragment.requireContext().applicationContext)
                    onStarted()
                }
                ConnectStart.DUPLICATE_IGNORED, ConnectStart.ALREADY_ACTIVE -> onStarted()
                ConnectStart.INVALID, ConnectStart.NOTHING_SAVED -> Unit
            }
            return start
        }
    }
}
