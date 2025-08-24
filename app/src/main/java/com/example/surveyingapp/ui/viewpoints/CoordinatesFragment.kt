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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Point
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
            onEdit = { point -> showEditPointDialog(point, viewModel) },
            onDelete = { point -> confirmDelete(point, viewModel) }
        )
        binding.pointsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.pointsRecyclerView.adapter = adapter

        viewModel.allPoints.observe(viewLifecycleOwner) { points ->
            Log.d("CoordinatesFragment", "Loaded ${points.size} coordinates: ${points.joinToString { it.id }}")
            adapter.submit(points)
            binding.emptyCoordinatesText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabAddCoordinate.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            } else {
                showAddPointDialog(viewModel)
            }
        }

        // Defer prefs listener registration to onStart for lifecycle safety
        prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        return root
    }

    private fun showAddPointDialog(viewModel: CoordinatesViewModel) {
        try {
            val dialog = AddPointDialogFragment { point ->
                viewModel.addPoint(point)
            }
            dialog.show(parentFragmentManager, "AddPointDialog")
        } catch (e: Exception) {}
    }

    private fun showEditPointDialog(point: Point, viewModel: CoordinatesViewModel) {
        val dialog = EditCoordinateDialogFragment(point) { updated ->
            viewModel.updatePoint(updated)
        }
        dialog.show(parentFragmentManager, "EditCoordinateDialog")
    }

    private fun confirmDelete(point: Point, viewModel: CoordinatesViewModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Coordinate")
            .setMessage("Delete \"${point.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                // Perform delete
                viewModel.deletePoint(point.id)
                // Offer undo via Snackbar
                Snackbar.make(binding.root, "Deleted ${point.name}", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") {
                        // Reinsert the same point (id preserved so it is restored)
                        viewModel.addPoint(point)
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
            showAddPointDialog(viewModel)
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
