package app.surrealar.ui.openinar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Compact, non-blocking model-visibility selector opened from the AR toolbar. Shares the parent
 * [OpenInARFragment]'s [OpenInARViewModel] (so toggles apply to the live AR view). Keeps the camera
 * visible (reduced scrim) and supports collapsed / half-expanded / expanded states.
 *
 * All visibility state is AR-only and persisted via the ViewModel → [ArVisibilitySettingsRepository];
 * this sheet never rebuilds anchors — it only flips the per-model selection the render gate reads.
 */
@AndroidEntryPoint
class ArModelsBottomSheet : BottomSheetDialogFragment() {

    // Share the parent fragment's ViewModel instance.
    private val viewModel: OpenInARViewModel by viewModels({ requireParentFragment() })

    private lateinit var adapter: ArModelAdapter
    private var updatingChips = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.sheet_ar_models, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.listArModels)
        val countText = view.findViewById<android.widget.TextView>(R.id.txtArModelsCount)
        val warningText = view.findViewById<android.widget.TextView>(R.id.txtArModelsWarning)
        val rangeLabel = view.findViewById<android.widget.TextView>(R.id.txtArRangeLabel)
        val modeGroup = view.findViewById<ChipGroup>(R.id.chipGroupArMode)
        val rangeGroup = view.findViewById<ChipGroup>(R.id.chipGroupArRange)

        viewModel.logArVisibilityState("sheet_open")

        adapter = ArModelAdapter { row -> viewModel.setArModelSelected(row.coordId, !row.selected) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        view.findViewById<View>(R.id.btnShowAllNearby).setOnClickListener { viewModel.showAllNearbyArModels() }
        view.findViewById<View>(R.id.btnHideAll).setOnClickListener { viewModel.hideAllArModels() }
        view.findViewById<View>(R.id.btnResetToMap).setOnClickListener { viewModel.resetArVisibilityToMapSelection() }

        // Mode chips.
        val modes = ArVisibilityMode.values()
        modes.forEach { mode ->
            modeGroup.addView(Chip(requireContext()).apply {
                text = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                isCheckable = true
                tag = mode
            })
        }
        modeGroup.setOnCheckedStateChangeListener { group, ids ->
            if (updatingChips) return@setOnCheckedStateChangeListener
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) }
            (chip?.tag as? ArVisibilityMode)?.let { viewModel.setArVisibilityMode(it) }
        }

        // Range chips from the existing distance-filter steps.
        OpenInARViewModel.DISTANCE_FILTER_STEPS.forEachIndexed { index, meters ->
            rangeGroup.addView(Chip(requireContext()).apply {
                text = meters?.let { "${it.roundToInt()} m" } ?: "All"
                isCheckable = true
                tag = index
            })
        }
        rangeGroup.setOnCheckedStateChangeListener { group, ids ->
            if (updatingChips) return@setOnCheckedStateChangeListener
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) }
            (chip?.tag as? Int)?.let { viewModel.setDistanceFilterIndex(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.arModelRows.collect { adapter.submitList(it) } }
                launch {
                    viewModel.arModelsSummary.collect { s ->
                        countText.text = "${s.modeRangeText} — ${s.shown} shown · ${s.nearby} nearby · ${s.total} total"
                        rangeLabel.text = if (s.mode == ArVisibilityMode.ALL) "Range (ignored in All mode)" else "Range"
                        val warn = s.mode == ArVisibilityMode.ALL && s.total >= ALL_MODE_WARNING_THRESHOLD
                        warningText.visibility = if (warn) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.arVisibilityMode.collect { mode -> selectChip(modeGroup) { (it.tag as? ArVisibilityMode) == mode } }
                }
                launch {
                    viewModel.distanceFilterIndex.collect { idx -> selectChip(rangeGroup) { (it.tag as? Int) == idx } }
                }
            }
        }
    }

    /** Check the chip matching [predicate] without re-triggering the change listener. */
    private inline fun selectChip(group: ChipGroup, predicate: (Chip) -> Boolean) {
        updatingChips = true
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            chip.isChecked = predicate(chip)
        }
        updatingChips = false
    }

    override fun onStart() {
        super.onStart()
        // Keep the camera view visible behind the sheet (reduced scrim) and enable half/expanded states.
        (dialog as? BottomSheetDialog)?.let { d ->
            d.window?.setDimAmount(0.12f)
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
            BottomSheetBehavior.from(sheet).apply {
                isFitToContents = false
                halfExpandedRatio = 0.55f
                skipCollapsed = false
                peekHeight = (resources.displayMetrics.density * 260).toInt()
                state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────────────────────────

    private class ArModelAdapter(
        val onToggle: (ArModelRow) -> Unit,
    ) : ListAdapter<ArModelRow, ArModelAdapter.VH>(DIFF) {

        class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val check: android.widget.CheckBox = root.findViewById(R.id.checkArVisible)
            val name: android.widget.TextView = root.findViewById(R.id.txtArModelName)
            val sub: android.widget.TextView = root.findViewById(R.id.txtArModelSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_ar_model_row, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            holder.check.isChecked = row.selected
            holder.name.text = row.name
            holder.sub.text = subtitle(row)
            holder.root.setOnClickListener { onToggle(row) }
        }

        private fun subtitle(row: ArModelRow): String {
            val dist = row.distanceM?.let { "${it.roundToInt()} m" }
            return when {
                dist == null -> row.status
                row.inRange  -> "$dist · ${row.status}"
                else         -> "${row.status} · $dist"
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<ArModelRow>() {
                override fun areItemsTheSame(a: ArModelRow, b: ArModelRow) = a.coordId == b.coordId
                override fun areContentsTheSame(a: ArModelRow, b: ArModelRow) = a == b
            }
        }
    }

    companion object {
        const val TAG = "ArModelsBottomSheet"
        /** Above this many total models, All mode shows a non-blocking performance hint. Tune freely. */
        const val ALL_MODE_WARNING_THRESHOLD = 25
        fun show(parent: Fragment) {
            if (parent.childFragmentManager.findFragmentByTag(TAG) == null) {
                ArModelsBottomSheet().show(parent.childFragmentManager, TAG)
            }
        }
    }
}
