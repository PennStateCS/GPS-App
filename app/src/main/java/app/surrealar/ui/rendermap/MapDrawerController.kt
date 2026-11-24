package app.surrealar.ui.rendermap

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.repository.ModelRepository
import kotlin.math.abs

/**
 * The left "map points" drawer: the coordinate list with its show/hide checkboxes and search filter,
 * the empty / no-match state, the counts, the overflow visibility menu (Show all / Hide all / Show
 * selected only) and the panel's collapse / expand / swipe interactions.
 *
 * Marker visibility lives in [markers]; this class only drives it and mirrors it in the list. The
 * search filter is display-only (never changes marker visibility).
 */
internal class MapDrawerController(
    private val context: () -> Context?,
    private val markers: CoordinateMarkerLayer,
    private val modelRepository: ModelRepository,
    private val coordinateSystemService: CoordinateSystemService,
    private val mapUiVm: MapUiStateViewModel,
    private val selectedId: () -> String?,
    private val onRowClick: (String) -> Unit,
    /** Marker visibility changed: point labels follow their markers. */
    private val onVisibilityChanged: () -> Unit,
    private val showSnackbar: (String) -> Unit,
) {
    // Panel
    private var leftPanel: View? = null
    private var collapseBtn: View? = null   // ImageButton in new layout, typed as View
    private var expandBtn: View? = null     // collapsed rail (LinearLayout), typed as View
    private var collapsedCount: TextView? = null   // point count badge on the collapsed rail
    private var leftPanelSubtitle: TextView? = null
    private var visibleCountText: TextView? = null
    private var pointSearchLayout: View? = null
    private var pointSearchEdit: android.widget.EditText? = null
    private var placeholder: View? = null
    private var emptyTitle: TextView? = null
    private var emptySubtitle: TextView? = null
    // Drawer list filtering (display-only: filters the list, never marker visibility).
    private var allToggleItems: List<CoordinateToggleItem> = emptyList()
    private var pointSearchQuery: String = ""
    private var panelHandle: View? = null   // GONE compat stub
    private var panelWidthPx: Int = 0
    private var panelCollapsed = false
    private var panelSwipeDownX: Float = 0f
    private var panelSwipeDownTime: Long = 0L

    // Coordinate list
    private var toggleRecycler: RecyclerView? = null
    private var toggleAdapter: CoordinateToggleAdapter? = null

    // Visibility menu button in panel header
    private var visibilityMenuBtn: ImageButton? = null

    private fun requireContext(): Context = checkNotNull(context()) { "Fragment not attached to a context." }
    private fun dpToPx(dp: Float): Int = requireContext().dpToPx(dp)

    /** Restores the session's collapsed state before the views are wired. */
    fun restoreState(collapsed: Boolean) { panelCollapsed = collapsed }

    /** onCreateView part: the list, its adapter, the empty state and the header. */
    fun bindListViews(root: View) {
        // Empty state now lives inside the left panel ("No saved points to show") rather than a
        // confusing centered "No render available" overlay on top of a perfectly good map.
        placeholder = root.findViewById(R.id.left_panel_empty)
        toggleRecycler = root.findViewById(R.id.coordinate_toggle_list)
        visibilityMenuBtn = root.findViewById(R.id.btn_visibility_menu)
        leftPanelSubtitle = root.findViewById(R.id.left_panel_subtitle)

        toggleAdapter = CoordinateToggleAdapter(
            modelRepository = modelRepository,
            onToggle = { id, checked ->
                markers.setVisible(id, checked)
                updateVisibleCount()
                onVisibilityChanged()   // hide/show this point's label with its marker
                if (!checked) showSnackbar("Coordinate hidden")
            },
            onRowClick = { id -> onRowClick(id) }
        )
        toggleRecycler?.layoutManager = LinearLayoutManager(requireContext())
        toggleRecycler?.adapter = toggleAdapter
        visibilityMenuBtn?.setOnClickListener { showVisibilityMenu(it) }
    }

    /** Panel state saved by an older version in the instance state (read only, never written). */
    fun restoreFromBundle(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            panelWidthPx = savedInstanceState.getInt("panelWidthPx", 0)
            panelCollapsed = savedInstanceState.getBoolean("panelCollapsed", false)
        }
    }

    /** onViewCreated part: the panel, counts, search and Show all / Hide all buttons. */
    fun bindPanelViews(view: View) {
        leftPanel = view.findViewById(R.id.left_panel)
        panelHandle = view.findViewById(R.id.panel_handle)  // GONE stub
        collapseBtn = view.findViewById(R.id.btn_collapse_panel)
        expandBtn = view.findViewById(R.id.btn_expand_panel)
        collapsedCount = view.findViewById(R.id.text_collapsed_count)
        visibleCountText = view.findViewById(R.id.text_visible_count)
        pointSearchLayout = view.findViewById(R.id.layout_point_search)
        pointSearchEdit = view.findViewById(R.id.edit_point_search)
        emptyTitle = view.findViewById(R.id.left_panel_placeholder)
        emptySubtitle = view.findViewById(R.id.left_panel_placeholder_sub)
        view.findViewById<View>(R.id.btn_show_all)?.setOnClickListener { showAllCoordinates() }
        view.findViewById<View>(R.id.btn_hide_all)?.setOnClickListener { hideAllCoordinates() }
        pointSearchEdit?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                pointSearchQuery = s?.toString()?.trim().orEmpty()
                applyDrawerFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    /** Lays out the panel in its saved state and wires collapse / expand / swipe. */
    fun initPanel() {
        if (panelWidthPx == 0) panelWidthPx = dpToPx(288f)
        applyPanelState()
        setupPanelInteractions()
    }

    /**
     * The view is going away: detach the adapter (cancels its pending icon loads and lets the
     * destroyed RecyclerView go) and drop every view reference. List/search state is kept.
     */
    fun onDestroyView() {
        try { toggleRecycler?.adapter = null } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        toggleAdapter = null
        leftPanel = null; collapseBtn = null; expandBtn = null; collapsedCount = null
        leftPanelSubtitle = null; visibleCountText = null; pointSearchLayout = null; pointSearchEdit = null
        placeholder = null; emptyTitle = null; emptySubtitle = null; panelHandle = null
        toggleRecycler = null; visibilityMenuBtn = null
    }

    // ── List content ───────────────────────────────────────────────────────────

    /** The database has no points: empty list + "No map points" state. */
    fun showNoPoints() {
        allToggleItems = emptyList()
        pointSearchLayout?.visibility = View.GONE
        showEmptyState(searching = false)
        toggleAdapter?.submit(emptyList())
        updateVisibleCount()
    }

    fun hidePlaceholder() { placeholder?.visibility = View.GONE }

    /** A drawer row for [p] with its current visibility. */
    fun toggleItem(p: Coordinate, visible: Boolean): CoordinateToggleItem = CoordinateToggleItem(
        id = p.id, name = p.name, checked = visible,
        icon = p.displayIconKey ?: "", modelId = p.linkedModelId, color = p.color,
        lat = p.latitude, lon = p.longitude,
        meta = CoordinateText.rowMeta(p, coordinateSystemService.heightText(p))
    )

    /** Shows freshly loaded rows (filtered by the search) and the counts. */
    fun showItems(toggleItems: List<CoordinateToggleItem>) {
        allToggleItems = toggleItems
        // Show search once the list gets long enough to warrant filtering.
        pointSearchLayout?.visibility = if (toggleItems.size > 8) View.VISIBLE else View.GONE
        applyDrawerFilter()
        updateVisibleCount()
    }

    fun setSelectedId(id: String?) { toggleAdapter?.setSelectedId(id) }

    // ── Coordinate visibility ──────────────────────────────────────────────────

    /** Overflow ("More") menu. Show all / Hide all are now explicit buttons; this keeps the
     *  contextual "Show selected only" and is the home for future point-display options. */
    private fun showVisibilityMenu(anchor: View) {
        if (markers.coordinates.isEmpty()) return
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Show all")
        popup.menu.add(0, 2, 1, "Hide all")
        selectedId()?.let { popup.menu.add(0, 3, 2, "Show selected only") }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAllCoordinates()
                2 -> hideAllCoordinates()
                3 -> showSelectedOnly()
            }
            true
        }
        popup.show()
    }

    private fun showSelectedOnly() {
        val selId = selectedId() ?: return
        try {
            markers.setAllVisible { id -> id == selId }
            refreshToggleList()
            updateVisibleCount()
            onVisibilityChanged()
        } catch (e: Exception) { logFail("showSelectedOnly", e) }
    }

    private fun showAllCoordinates() {
        try {
            markers.setAllVisible { true }
            refreshToggleList()
            updateVisibleCount()
            onVisibilityChanged()
        } catch (e: Exception) { logFail("showAllCoordinates", e) }
    }

    private fun hideAllCoordinates() {
        try {
            markers.setAllVisible { false }
            refreshToggleList()
            updateVisibleCount()
            onVisibilityChanged()
            showSnackbar("All coordinates hidden")
        } catch (e: Exception) { logFail("hideAllCoordinates", e) }
    }

    private fun refreshToggleList() {
        try {
            allToggleItems = markers.coordinates.values.map { c ->
                toggleItem(c, visible = markers.visibility[c.id] ?: true)
            }
            applyDrawerFilter()
        } catch (e: Exception) { logFail("refreshToggleList", e) }
    }

    /** Submits the drawer list filtered by the search query (display-only — never changes marker
     *  visibility) and manages the empty/no-match state. */
    private fun applyDrawerFilter() {
        val q = pointSearchQuery
        val filtered = if (q.isBlank()) allToggleItems
            else allToggleItems.filter { it.name.contains(q, ignoreCase = true) }
        toggleAdapter?.submit(filtered)
        when {
            allToggleItems.isEmpty() -> showEmptyState(searching = false)
            filtered.isEmpty() -> showEmptyState(searching = true)
            else -> placeholder?.visibility = View.GONE
        }
    }

    private fun showEmptyState(searching: Boolean) {
        placeholder?.visibility = View.VISIBLE
        if (searching) {
            emptyTitle?.text = "No matching points"
            emptySubtitle?.text = "Try a different search."
        } else {
            emptyTitle?.text = "No map points"
            emptySubtitle?.text = "Capture or import coordinates to show them here."
        }
    }

    private fun updateVisibleCount() {
        val visible = markers.visibility.visibleCount
        val total = markers.coordinates.size
        // Map-workspace style count, e.g. "12 shown / 18 total".
        leftPanelSubtitle?.text = if (total == 0) "No points yet" else "$visible shown / $total total"
        visibleCountText?.text = "Visible $visible / $total"
        collapsedCount?.text = total.toString()
    }

    // ── Panel interactions ─────────────────────────────────────────────────────

    private fun setupPanelInteractions() {
        collapseBtn?.setOnClickListener { collapsePanel() }
        expandBtn?.setOnClickListener { expandPanel() }

        // Swipe-to-collapse on the panel itself
        leftPanel?.setOnTouchListener { v, ev ->
            if (panelCollapsed) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelSwipeDownX = ev.rawX; panelSwipeDownTime = System.currentTimeMillis(); false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - panelSwipeDownX
                    val dt = (System.currentTimeMillis() - panelSwipeDownTime).coerceAtLeast(1)
                    val vel = dx / dt.toFloat()
                    if (dx < -dpToPx(100f) || vel < -0.7f) { collapsePanel(); true }
                    else { if (abs(dx) < dpToPx(8f)) v.performClick(); false }
                }
                else -> false
            }
        }
    }

    /** Distance to slide the panel fully off-screen-left: its width + start margin + shadow. */
    private fun panelSlideDistance(): Float {
        val panel = leftPanel
        val w = if (panel != null && panel.width > 0) panel.width else panelWidthPx
        return (w + dpToPx(20f)).toFloat()
    }

    private fun collapsePanel() {
        if (panelCollapsed) return
        val panel = leftPanel ?: return
        panelCollapsed = true
        mapUiVm.update { it.copy(isLeftPanelCollapsed = true) }
        panel.animate().cancel()
        panel.animate()
            .translationX(-panelSlideDistance())
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                panel.visibility = View.GONE
                expandBtn?.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate().alpha(1f).setDuration(150).start()
                }
            }
            .start()
    }

    private fun expandPanel() {
        if (!panelCollapsed) return
        val panel = leftPanel ?: return
        panelCollapsed = false
        mapUiVm.update { it.copy(isLeftPanelCollapsed = false) }
        expandBtn?.visibility = View.GONE
        panel.animate().cancel()
        // Pre-position off-screen, then slide in — content stays laid out at full width.
        panel.translationX = -panelSlideDistance()
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun applyPanelState() {
        val panel = leftPanel
        if (panelCollapsed) {
            panel?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        } else {
            panel?.translationX = 0f
            panel?.alpha = 1f
            panel?.visibility = View.VISIBLE
            expandBtn?.visibility = View.GONE
        }
    }
}
