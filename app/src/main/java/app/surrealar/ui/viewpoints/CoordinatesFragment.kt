/**
 * Fragment that displays and manages the list of coordinate points.
 *
 * This is the main screen of the app where users can:
 * - View all saved coordinate points in a list
 * - Add new coordinate points using the selected source (Internal GPS or External RS2+)
 * - Edit existing points
 * - Delete points with undo functionality
 */

package app.surrealar.ui.viewpoints

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.domain.model.Coordinate
import app.surrealar.databinding.FragmentCoordinatesBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.graphics.Rect
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.ModelRepository
import app.surrealar.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CoordinatesFragment : Fragment() {

    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    companion object {
        private const val PREF_LAST_OPENED_ID = "last_coord_opened_id"
    }

    private var detailInitialized = false

    // Track whether we've already added the vertical divider decoration
    private var dividerAdded = false

    // Tablet: whether the left list pane is currently visible
    private var listPaneVisible = true

    // View binding (valid between onCreateView and onDestroyView)
    private var _binding: FragmentCoordinatesBinding? = null
    private val binding get() = _binding!!

    // RecyclerView adapter
    private lateinit var adapter: SimpleCoordinatesAdapter

    // UI display preferences (show coords/elevation)
    private var prefs: SharedPreferences? = null

    // ViewModel (survives configuration changes; Hilt provides the factory)
    private val viewModel: CoordinatesViewModel by viewModels()

    // Modern permission request for fine location (internal source only)
    private val requestFineLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showAddCoordinateDialog(viewModel)
        } else {
            Snackbar.make(requireView(), "Location permission denied", Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.fabAddCoordinate)
                .show()
        }
    }

    // --- Fragment lifecycle ---

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCoordinatesBinding.inflate(inflater, container, false)
        val root = binding.root

        // Adapter (handles selection & detail navigation)
        adapter = SimpleCoordinatesAdapter(
            onClick = { coordinate ->
                try {
                    Log.d("CoordinatesFragment", "Item clicked: ${coordinate.id}")
                    currentSelectionId = coordinate.id
                    saveLastOpenedId(coordinate.id)
                    adapter.setSelectedId(coordinate.id)
                    val pos = adapter.positionOf(coordinate.id)
                    if (pos >= 0) binding.pointsRecyclerView.smoothScrollToPosition(pos)

                    val hostActivity = activity
                    Log.d("CoordinatesFragment", "Host activity: $hostActivity")

                    if (hostActivity is CoordinatesActivity) {
                        hostActivity.showDetail(coordinate.id)
                    } else {
                        // Embedded two-pane inside this fragment (MainActivity host)
                        if (binding.root.findViewById<View>(R.id.coord_detail_container) != null) {
                            showEmbeddedDetail(coordinate.id)
                        } else {
                            // Fallback: navigate if container not present (should not happen normally)
                            val args = Bundle().apply { putString("arg_id", coordinate.id) }
                            findNavController().navigate(R.id.nav_coordinate_detail, args)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoordinatesFragment", "Click handler error: ${e.message}", e)
                }
            },
            onDelete = { coordinate ->
                // Determine replacement selection if deleting the currently selected item
                if (coordinate.id == currentSelectionId) {
                    val pos = adapter.positionOf(coordinate.id)
                    val replacement = adapter.idAt(pos + 1) ?: adapter.idAt(pos - 1)
                    pendingDeleteSelectionReplacement = replacement
                }
                confirmDeleteWithSelection(coordinate, viewModel)
            },
            onEdit = { coordinate ->
                showEditCoordinateDialog(coordinate, viewModel)
            }
        )

        // Recycler
        val spanCount = resources.getInteger(R.integer.coord_span_count)
        val recycler = binding.pointsRecyclerView
        if (spanCount <= 1) {
            recycler.layoutManager = LinearLayoutManager(requireContext())
            // Add divider once
            if (!dividerAdded) {
                val decoration = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
                ContextCompat.getDrawable(requireContext(), R.drawable.coordinate_list_divider)?.let { decoration.setDrawable(it) }
                recycler.addItemDecoration(decoration)
                dividerAdded = true
            }
        } else {
            recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
        }
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setPadding(0, recycler.paddingTop, 0, recycler.paddingBottom)
        recycler.clipToPadding = false

        // Removed previous horizontal padding logic so items fill full width
        // If future multi-column grid needed, add conditional spacing only when spanCount > 1
        if (spanCount > 1 && recycler.itemDecorationCount == 0) {
            val itemSpace = resources.getDimensionPixelSize(R.dimen.coord_item_spacing)
            recycler.addItemDecoration(GridSpacingDecoration(spanCount, itemSpace))
        }

        // Observe DB
        viewModel.allCoordinates.observe(viewLifecycleOwner) { coordinates ->
            try {
                Log.d(
                    "CoordinatesFragment",
                    "Loaded ${coordinates.size} coordinates: ${coordinates.joinToString { it.id }}"
                )
                adapter.submit(coordinates)
                binding.emptyCoordinatesText.visibility =
                    if (coordinates.isEmpty()) View.VISIBLE else View.GONE

                if (coordinates.isEmpty()) {
                    currentSelectionId = null
                    adapter.setSelectedId(null)
                    clearLastOpenedId()
                    pendingDeleteSelectionReplacement = null
                    return@observe
                }

                // If we have a pending selection from a deletion, prefer it
                pendingDeleteSelectionReplacement?.let { desired ->
                    if (coordinates.any { it.id == desired }) {
                        currentSelectionId = desired
                        saveLastOpenedId(desired)
                    }
                    pendingDeleteSelectionReplacement = null
                }

                if (currentSelectionId == null || coordinates.none { it.id == currentSelectionId }) {
                    val stored = loadLastOpenedId()
                    val target = if (stored != null && coordinates.any { it.id == stored }) stored else coordinates.first().id
                    currentSelectionId = target
                    saveLastOpenedId(target)
                }
                adapter.setSelectedId(currentSelectionId)

                // Ensure selected item visible (initial load)
                currentSelectionId?.let { idSel ->
                    val pos = adapter.positionOf(idSel)
                    if (pos >= 0) binding.pointsRecyclerView.post { binding.pointsRecyclerView.scrollToPosition(pos) }
                }

                // Always update the detail pane with the current selection
                currentSelectionId?.let { id ->
                    if (activity is CoordinatesActivity) {
                        (activity as? CoordinatesActivity)?.showDetail(id)
                    } else if (binding.root.findViewById<View>(R.id.coord_detail_container) != null) {
                        if (!detailInitialized) {
                            binding.root.findViewById<View>(R.id.coord_detail_container)?.postDelayed({
                                if (isAdded) showEmbeddedDetail(id)
                            }, 120)
                            detailInitialized = true
                        } else {
                            showEmbeddedDetail(id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CoordinatesFragment", "allCoordinates observer failed", e)
            }
        }

        // FAB: add new coordinate (one-shot capture from selected source)
        binding.fabAddCoordinate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val source = runCatching { settingsRepository.locationSource.first() }
                        .getOrDefault(LocationSourceType.INTERNAL)
                    val needsFine = source == LocationSourceType.INTERNAL
                    if (needsFine) {
                        val granted = ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) showAddCoordinateDialog(viewModel)
                        else requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        showAddCoordinateDialog(viewModel)
                    }
                } catch (e: Exception) {
                    Log.e("CoordinatesFragment", "FAB click handler failed", e)
                }
            }
        }

        // Tablet two-pane: wire collapsible list pane controls.
        // The "show list" entry point when collapsed lives in the detail header
        // (CoordinateDetailFragment), so it never floats over the title.
        val btnCollapse = root.findViewById<View>(R.id.btn_collapse_list)
        if (btnCollapse != null && root.findViewById<View>(R.id.list_pane_container) != null) {
            applyListPaneVisibility()
            btnCollapse.setOnClickListener {
                listPaneVisible = false
                applyListPaneVisibility()
            }
        }

        // Preferences for UI-only display toggles (not app settings)
        prefs = requireContext().getSharedPreferences("CoordinatesFragmentPrefs", Context.MODE_PRIVATE)

        // Observe models so the list can show model thumbnails and names
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                modelRepository.getAllModels().collect { models ->
                    val thumbMap = models.associate { it.id to it.thumbnailFilePath }
                    val nameMap  = models.associate { it.id to it.name }
                    adapter.setModelNameMap(nameMap)
                    adapter.setThumbnailMap(thumbMap)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("CoordinatesFragment", "Error collecting model thumbnails", e)
            }
        }

        return root
    }

    // --- Dialogs / Actions ---

    /** Show dialog to add a coordinate (captures one fix and saves on Save) */
    private fun showAddCoordinateDialog(viewModel: CoordinatesViewModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetch the current model list so the icon spinner includes DB models
            val models = try {
                modelRepository.getAllModels().first()
            } catch (_: Exception) {
                emptyList()
            }

            try {
                val highAcc = runCatching { settingsRepository.gnssReceiverSettings.first().highAccuracy }.getOrDefault(true)
                val dialog = AddCoordinateDialogFragment(
                    highAccuracy = highAcc,
                    dbModels = models
                ) { coordinate ->
                    viewModel.addCoordinate(coordinate)
                    // haptic confirm
                    _binding?.root?.post {
                        val view = _binding?.root
                        if (view != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            } else {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    }
                }
                dialog.show(parentFragmentManager, "AddCoordinateDialog")
            } catch (_: Exception) {
                // no-op
            }
        }
    }

    /** Show dialog to edit an existing coordinate */
    private fun showEditCoordinateDialog(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val models = try {
                modelRepository.getAllModels().first()
            } catch (_: Exception) {
                emptyList()
            }
            val dialog = EditCoordinateDialogFragment(coordinate, models) { updated ->
                viewModel.updateCoordinate(updated)
            }
            dialog.show(parentFragmentManager, "EditCoordinateDialog")
        }
    }

    /** Confirm deletion with undo via Snackbar */
    private fun confirmDeleteWithSelection(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        try {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_coordinate))
                .setMessage("Delete \"${coordinate.name}\"?")
                .setPositiveButton(getString(R.string.delete_coordinate)) { _, _ ->
                    try {
                        viewModel.deleteCoordinate(coordinate.id)
                        Snackbar.make(binding.root, "Deleted ${coordinate.name}", Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.fabAddCoordinate)
                            .setAction("UNDO") {
                                try {
                                    viewModel.addCoordinate(coordinate)
                                    currentSelectionId = coordinate.id
                                    saveLastOpenedId(coordinate.id)
                                    adapter.setSelectedId(coordinate.id)
                                } catch (e: Exception) {
                                    Log.e("CoordinatesFragment", "UNDO failed", e)
                                }
                            }
                            .show()
                    } catch (e: Exception) {
                        Log.e("CoordinatesFragment", "Delete confirm action failed", e)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Log.e("CoordinatesFragment", "confirmDeleteWithSelection failed", e)
        }
    }

    // --- More lifecycle ---

    override fun onStart() {
        super.onStart()
        // no pref listener registration needed
    }

    override fun onStop() {
        super.onStop()
        // no pref listener unregistration needed
    }

    override fun onResume() {
        super.onResume()
        try {
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Log.e("CoordinatesFragment", "onResume adapter refresh failed", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefs = null
        _binding = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSelectionId = savedInstanceState?.getString("coord_selected_id")
        listPaneVisible = savedInstanceState?.getBoolean("list_pane_visible", true) ?: true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentSelectionId?.let { outState.putString("coord_selected_id", it) }
        outState.putBoolean("list_pane_visible", listPaneVisible)
    }

    // Grid spacing decoration
    private class GridSpacingDecoration(
        private val spanCount: Int,
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % spanCount
            val total = spacing * (spanCount - 1)
            val per = if (spanCount > 1) total.toFloat() / spanCount else 0f
            // Distribute left/right spacing so total between items remains uniform
            val left = (column * (per / (spanCount - 1))).toInt()
            val right = (per - left).toInt()
            outRect.left = if (column == 0) 0 else left
            outRect.right = if (column == spanCount - 1) 0 else right
            outRect.top = spacing / 2
            outRect.bottom = spacing / 2
        }
    }

    // Current selection ID (for detail view navigation)
    private var currentSelectionId: String? = null

    // Track a desired selection after a deletion
    private var pendingDeleteSelectionReplacement: String? = null

    private fun loadLastOpenedId(): String? = prefs?.getString(PREF_LAST_OPENED_ID, null)
    private fun saveLastOpenedId(id: String) { prefs?.edit()?.putString(PREF_LAST_OPENED_ID, id)?.apply() }
    private fun clearLastOpenedId() { prefs?.edit()?.remove(PREF_LAST_OPENED_ID)?.apply() }

    private fun showEmbeddedDetail(id: String) {
        try {
            val existing = childFragmentManager.findFragmentById(R.id.coord_detail_container) as? CoordinateDetailFragment
            if (existing != null) {
                existing.updateId(id)
                applyShowListControlToDetail()
            } else {
                childFragmentManager.beginTransaction()
                    .replace(R.id.coord_detail_container, CoordinateDetailFragment.newInstance(id))
                    .commit()
                // New fragment pulls its initial state via onDetailViewReady() once its view exists.
            }
        } catch (e: Exception) {
            Log.e("CoordinatesFragment", "showEmbeddedDetail failed: ${e.message}")
        }
    }

    /** Apply the current collapse state to the list pane and the detail header's show-list control. */
    private fun applyListPaneVisibility() {
        val root = _binding?.root ?: return
        val vis = if (listPaneVisible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.list_pane_container)?.visibility = vis
        root.findViewById<View>(R.id.list_pane_divider)?.visibility = vis
        applyShowListControlToDetail()
    }

    /** Push the show-list button state into the embedded detail fragment (if present). */
    private fun applyShowListControlToDetail() {
        val detail = childFragmentManager.findFragmentById(R.id.coord_detail_container)
            as? CoordinateDetailFragment ?: return
        bindDetailShowList(detail)
    }

    /** Called by the embedded detail fragment when its view is ready, to receive its initial state. */
    fun onDetailViewReady(detail: CoordinateDetailFragment) {
        bindDetailShowList(detail)
    }

    private fun bindDetailShowList(detail: CoordinateDetailFragment) {
        detail.setShowListControl(visible = !listPaneVisible) {
            listPaneVisible = true
            applyListPaneVisibility()
        }
    }
}
