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
import com.example.surveyingapp.databinding.FragmentCoordinatesBinding
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.snackbar.Snackbar

class CoordinatesFragment : Fragment() {
    private var _binding: FragmentCoordinatesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SimpleCoordinatesAdapter
    private var prefs: SharedPreferences? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_SHOW_COORDINATES || key == SettingsFragment.PREF_SHOW_ELEVATION) {
            if (isAdded && _binding != null && ::adapter.isInitialized) {
                try {
                    // Post to recycler to ensure we aren't mid-layout
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCoordinatesBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application)).get(CoordinatesViewModel::class.java)
        adapter = SimpleCoordinatesAdapter(
            onEdit = { coordinate -> showEditCoordinateDialog(coordinate, viewModel) },
            onDelete = { coordinate -> confirmDelete(coordinate, viewModel) }
        )
        binding.pointsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.pointsRecyclerView.adapter = adapter

        viewModel.allCoordinates.observe(viewLifecycleOwner) { coordinates ->
            Log.d("CoordinatesFragment", "Loaded ${coordinates.size} coordinates: ${coordinates.joinToString { it.id }}")
            adapter.submit(coordinates)
            binding.emptyCoordinatesText.visibility = if (coordinates.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabAddCoordinate.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            } else {
                showAddCoordinateDialog(viewModel)
            }
        }

        // Defer prefs listener registration to onStart for lifecycle safety
        prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        return root
    }

    private fun showAddCoordinateDialog(viewModel: CoordinatesViewModel) {
        try {
            val dialog = AddCoordinateDialogFragment { coordinate ->
                viewModel.addCoordinate(coordinate)
                // Provide haptic feedback to confirm capture
                _binding?.root?.post {
                    _binding?.root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
            dialog.show(parentFragmentManager, "AddCoordinateDialog")
        } catch (_: Exception) {}
    }

    private fun showEditCoordinateDialog(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        val dialog = EditCoordinateDialogFragment(coordinate) { updated ->
            viewModel.updateCoordinate(updated)
        }
        dialog.show(parentFragmentManager, "EditCoordinateDialog")
    }

    private fun confirmDelete(coordinate: Coordinate, viewModel: CoordinatesViewModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Coordinate")
            .setMessage("Delete \"${coordinate.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                // Perform delete
                viewModel.deleteCoordinate(coordinate.id)
                // Offer undo via Snackbar
                Snackbar.make(binding.root, "Deleted ${coordinate.name}", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") {
                        // Reinsert the same point (id preserved so it is restored)
                        viewModel.addCoordinate(coordinate)
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application)).get(CoordinatesViewModel::class.java)
            showAddCoordinateDialog(viewModel)
        }
    }

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
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // prefs listener already unregistered in onStop
        prefs = null
        _binding = null
    }
}
