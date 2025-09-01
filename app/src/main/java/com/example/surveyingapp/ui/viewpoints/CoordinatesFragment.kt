/**
 * Fragment that displays and manages the list of coordinate points.
 *
 * This is the main screen of the app where users can:
 * - View all saved coordinate points in a list
 * - Add new coordinate points using the selected source (Internal GPS or External RS2+)
 * - Edit existing points
 * - Delete points with undo functionality
 */

package com.example.surveyingapp.ui.viewpoints

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
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.databinding.FragmentCoordinatesBinding
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.snackbar.Snackbar
import com.example.surveyingapp.SurveyingApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.graphics.Rect
import com.example.surveyingapp.domain.model.LocationSourceType

class CoordinatesFragment : Fragment() {

    companion object {
        private const val PREF_LAST_OPENED_ID = "last_coord_opened_id"
    }

    private var detailInitialized = false

    // Track whether we've already added the vertical divider decoration
    private var dividerAdded = false

    // View binding (valid between onCreateView and onDestroyView)
    private var _binding: FragmentCoordinatesBinding? = null
    private val binding get() = _binding!!

    // RecyclerView adapter
    private lateinit var adapter: SimpleCoordinatesAdapter

    // UI display preferences (show coords/elevation)
    private var prefs: SharedPreferences? = null

    // ViewModel (survives configuration changes)
    private val viewModel: CoordinatesViewModel by viewModels {
        AndroidViewModelFactory(requireActivity().application)
    }

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
                        // Defer first detail load slightly to let drawer animation finish smoothly
                        binding.root.findViewById<View>(R.id.coord_detail_container)?.postDelayed({
                            if (isAdded) showEmbeddedDetail(id)
                        }, 120)
                        detailInitialized = true
                    } else {
                        showEmbeddedDetail(id)
                    }
                }
            }
        }

        // FAB: add new coordinate (one-shot capture from selected source)
        binding.fabAddCoordinate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val source = runCatching { SurveyingApp.settingsRepo.locationSource.first() }
                    .getOrDefault(LocationSourceType.INTERNAL)
                val needsFine = source == LocationSourceType.INTERNAL
                if (needsFine) {
                    val granted = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) showAddCoordinateDialog(viewModel) else requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    showAddCoordinateDialog(viewModel)
                }
            }
        }

        // Preferences for UI display toggles
        prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        return root
    }

    // --- Dialogs / Actions ---

    /** Show dialog to add a coordinate (captures one fix and saves on Save) */
    private fun showAddCoordinateDialog(viewModel: CoordinatesViewModel) {
        try {
            val highAcc = prefs?.getBoolean(SettingsFragment.PREF_HIGH_ACCURACY, true) ?: true
            val dialog = AddCoordinateDialogFragment(highAcc) { coordinate ->
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

    /** Show dialog to edit an existing coordinate */
    private fun showEditCoordinateDialog(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        val dialog = EditCoordinateDialogFragment(coordinate) { updated ->
            viewModel.updateCoordinate(updated)
        }
        dialog.show(parentFragmentManager, "EditCoordinateDialog")
    }

    /** Confirm deletion with undo via Snackbar */
    private fun confirmDeleteWithSelection(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_coordinate))
            .setMessage("Delete \"${coordinate.name}\"?")
            .setPositiveButton(getString(R.string.delete_coordinate)) { _, _ ->
                // Perform deletion
                viewModel.deleteCoordinate(coordinate.id)
                // If user UNDOs, restore selection to this coordinate
                Snackbar.make(binding.root, "Deleted ${coordinate.name}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabAddCoordinate)
                    .setAction("UNDO") {
                        viewModel.addCoordinate(coordinate)
                        currentSelectionId = coordinate.id
                        saveLastOpenedId(coordinate.id)
                        adapter.setSelectedId(coordinate.id)
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefs = null
        _binding = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSelectionId = savedInstanceState?.getString("coord_selected_id")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentSelectionId?.let { outState.putString("coord_selected_id", it) }
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
            } else {
                childFragmentManager.beginTransaction()
                    .replace(R.id.coord_detail_container, CoordinateDetailFragment.newInstance(id))
                    .commit()
            }
        } catch (e: Exception) {
            Log.e("CoordinatesFragment", "showEmbeddedDetail failed: ${e.message}")
        }
    }
}
