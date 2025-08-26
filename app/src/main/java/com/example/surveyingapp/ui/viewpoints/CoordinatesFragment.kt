/**
 * Fragment that displays and manages the list of coordinate points.
 *
 * This is the main screen of the app where users can:
 * - View all saved coordinate points in a list
 * - Add new coordinate points using GPS
 * - Edit existing points
 * - Delete points with undo functionality
 *
 * Key Android concepts demonstrated:
 * - Fragment lifecycle management
 * - View binding for safe view access
 * - RecyclerView for efficient list display
 * - LiveData observation for automatic UI updates
 * - SharedPreferences for user settings
 */
// This file was renamed from ViewPointsFragment.kt
// See CoordinatesFragment implementation above.

package com.example.surveyingapp.ui.viewpoints

import android.content.SharedPreferences
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.data.Point
import com.example.surveyingapp.databinding.FragmentCoordinatesBinding
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.snackbar.Snackbar

class CoordinatesFragment : Fragment() {
    // View binding - safer than findViewById, null when view is destroyed
    private var _binding: FragmentCoordinatesBinding? = null
    private val binding get() = _binding!!

    // RecyclerView adapter for displaying the coordinate list
    private lateinit var adapter: SimpleCoordinatesAdapter

    // SharedPreferences for listening to user setting changes
    private var prefs: SharedPreferences? = null

    /**
     * Listener that responds to changes in user preferences.
     * When display settings change (like showing coordinates or elevation),
     * the list automatically refreshes to reflect the new settings.
     */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_SHOW_COORDINATES || key == SettingsFragment.PREF_SHOW_ELEVATION) {
            // Check if fragment and views are still valid before updating
            if (isAdded && _binding != null && ::adapter.isInitialized) {
                try {
                    // Post to recycler to ensure we aren't mid-layout
                    // This prevents crashes during RecyclerView layout operations
                    binding.pointsRecyclerView.post {
                        if (isAdded && _binding != null) {
                            try {
                                adapter.notifyDataSetChanged()
                                Log.d("CoordinatesFragment","Preferences changed ($key) -> list refreshed")
                            } catch (inner: Exception) {
                                Log.e("CoordinatesFragment","notifyDataSetChanged failed: ${inner.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoordinatesFragment","Pref listener outer failure: ${e.message}")
                }
            } else {
                Log.w("CoordinatesFragment","Pref change received while fragment not fully active")
            }
        }
    }

    /**
     * Called when the fragment's view is being created.
     * This is where we inflate the layout and set up the UI components.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using view binding
        _binding = FragmentCoordinatesBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Create the ViewModel that manages our data
        // ViewModelProvider ensures the same instance survives configuration changes
        val viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application)).get(CoordinatesViewModel::class.java)

        // Set up the RecyclerView adapter with callback functions for user actions
        adapter = SimpleCoordinatesAdapter(
            onEdit = { coordinate -> showEditCoordinateDialog(coordinate, viewModel) },    // When user taps edit
            onDelete = { coordinate -> confirmDelete(coordinate, viewModel) }              // When user taps delete
        )

        // Configure the RecyclerView
        binding.pointsRecyclerView.layoutManager = LinearLayoutManager(requireContext())  // Vertical list layout
        binding.pointsRecyclerView.adapter = adapter

        // Observe changes to the coordinate data
        // LiveData automatically updates the UI when database data changes
        viewModel.allCoordinates.observe(viewLifecycleOwner) { coordinates ->
            Log.d("CoordinatesFragment", "Loaded ${coordinates.size} coordinates: ${coordinates.joinToString { it.id }}")
            adapter.submit(coordinates)  // Update the list display
            // Show/hide empty state message
            binding.emptyCoordinatesText.visibility = if (coordinates.isEmpty()) View.VISIBLE else View.GONE
        }

        // Set up the floating action button to add new coordinates
        binding.fabAddCoordinate.setOnClickListener {
            // Check for location permission before opening the add dialog
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Request permission if not granted
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            } else {
                // Permission already granted, show the add dialog
                showAddCoordinateDialog(viewModel)
            }
        }

        // Set up SharedPreferences for listening to setting changes
        prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        return root
    }

    /**
     * Shows the dialog for adding a new coordinate point.
     * Uses the device's GPS to get the current location.
     */
    private fun showAddCoordinateDialog(viewModel: CoordinatesViewModel) {
        try {
            val dialog = AddCoordinateDialogFragment { coordinate ->
                viewModel.addCoordinate(coordinate)
                // Provide haptic feedback to confirm the action
                _binding?.root?.post {
                    _binding?.root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
            dialog.show(parentFragmentManager, "AddCoordinateDialog")
        } catch (_: Exception) {}
    }

    /**
     * Shows the dialog for editing an existing coordinate point.
     */
    private fun showEditCoordinateDialog(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        val dialog = EditCoordinateDialogFragment(coordinate) { updated ->
            viewModel.updateCoordinate(updated)
        }
        dialog.show(parentFragmentManager, "EditCoordinateDialog")
    }

    /**
     * Shows a confirmation dialog before deleting a coordinate.
     * Includes undo functionality using a Snackbar.
     */
    private fun confirmDelete(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Coordinate")
            .setMessage("Delete \"${coordinate.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                // Perform delete
                viewModel.deleteCoordinate(coordinate.id)
                // Offer undo via Snackbar - good UX practice
                Snackbar.make(binding.root, "Deleted ${coordinate.name}", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") {
                        // Reinsert the same coordinate (id preserved so it is restored)
                        viewModel.addCoordinate(coordinate)
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Handles the result of permission requests.
     * Called when user responds to permission dialog.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application)).get(CoordinatesViewModel::class.java)
            showAddCoordinateDialog(viewModel)
        }
    }

    // Fragment lifecycle methods
    override fun onStart() {
        super.onStart()
        // Register preference listener when fragment becomes visible
        prefs?.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStop() {
        // Unregister preference listener when fragment is no longer visible
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list when returning to this fragment
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up references to avoid memory leaks
        prefs = null
        _binding = null
    }
}
