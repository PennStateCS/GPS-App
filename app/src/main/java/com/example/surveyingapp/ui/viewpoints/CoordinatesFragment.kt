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
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.databinding.FragmentCoordinatesBinding
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.snackbar.Snackbar
import com.example.surveyingapp.SurveyingApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.graphics.Rect

class CoordinatesFragment : Fragment() {

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

    /** Preferences change listener to refresh list formatting */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_SHOW_COORDINATES || key == SettingsFragment.PREF_SHOW_ELEVATION) {
            if (isAdded && _binding != null && ::adapter.isInitialized) {
                try {
                    binding.pointsRecyclerView.post {
                        if (isAdded && _binding != null) {
                            try {
                                adapter.notifyDataSetChanged()
                                Log.d("CoordinatesFragment", "Preferences changed ($key) -> list refreshed")
                            } catch (inner: Exception) {
                                Log.e("CoordinatesFragment", "notifyDataSetChanged failed: ${inner.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoordinatesFragment", "Pref listener outer failure: ${e.message}")
                }
            } else {
                Log.w("CoordinatesFragment", "Pref change received while fragment not fully active")
            }
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
                    val fragmentTwoPane = root.findViewById<View?>(R.id.detail_container) != null
                    if (fragmentTwoPane && isAdded) {
                        currentSelectionId = coordinate.id
                        // Temporarily remove setSelectedId call to isolate freezing
                        // adapter.setSelectedId(coordinate.id)
                        if (!childFragmentManager.isStateSaved) {
                            childFragmentManager.beginTransaction()
                                .replace(R.id.detail_container, CoordinateDetailFragment.newInstance(coordinate.id))
                                .commitAllowingStateLoss()
                        }
                    } else {
                        val host = activity
                        if (host is CoordinatesActivity) {
                            host.showDetail(coordinate.id)
                        } else {
                            val ctx = requireContext()
                            val intent = Intent(ctx, CoordinateDetailActivity::class.java).apply {
                                putExtra(CoordinateDetailActivity.EXTRA_ID, coordinate.id)
                            }
                            startActivity(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoordinatesFragment", "Click handler error: ${e.message}")
                }
            }
        )

        // Recycler
        val spanCount = resources.getInteger(R.integer.coord_span_count)
        val recycler = binding.pointsRecyclerView
        if (spanCount <= 1) {
            recycler.layoutManager = LinearLayoutManager(requireContext())
        } else {
            recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
        }
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        val fragmentTwoPane = root.findViewById<View?>(R.id.detail_container) != null
        if (!fragmentTwoPane) {
            val horizPad = resources.getDimensionPixelSize(R.dimen.coord_horizontal_padding)
            val itemSpace = resources.getDimensionPixelSize(R.dimen.coord_item_spacing)
            recycler.setPadding(horizPad, recycler.paddingTop, horizPad, recycler.paddingBottom)
            recycler.clipToPadding = false
            if (spanCount > 1 && recycler.itemDecorationCount == 0) {
                recycler.addItemDecoration(GridSpacingDecoration(spanCount, itemSpace))
            }
        } else {
            // Two-pane: compact vertical list with thin dividers
            if (recycler.itemDecorationCount == 0) {
                val decor = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
                ContextCompat.getDrawable(requireContext(), R.drawable.coordinate_list_divider)?.let { decor.setDrawable(it) }
                recycler.addItemDecoration(decor)
            }
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

            // Completely disable two-pane auto-selection logic for now to prevent freezing
        }

        // FAB: add new coordinate (one-shot capture from selected source)
        binding.fabAddCoordinate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val source = runCatching { SurveyingApp.settingsRepo.locationSource.first() }
                    .getOrDefault("internal")
                val needsFine = source.equals("internal", ignoreCase = true)
                if (needsFine) {
                    val granted = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        showAddCoordinateDialog(viewModel)
                    } else {
                        requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                } else {
                    // External source (RS2+) selected: no FINE permission needed here.
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
                    _binding?.root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
    private fun confirmDelete(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Coordinate")
            .setMessage("Delete \"${coordinate.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteCoordinate(coordinate.id)
                Snackbar.make(binding.root, "Deleted ${coordinate.name}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabAddCoordinate)
                    .setAction("UNDO") { viewModel.addCoordinate(coordinate) }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- More lifecycle ---

    override fun onStart() {
        super.onStart()
        prefs?.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStop() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStop()
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
}
