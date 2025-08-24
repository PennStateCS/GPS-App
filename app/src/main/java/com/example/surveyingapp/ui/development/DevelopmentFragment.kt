package com.example.surveyingapp.ui.development

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.databinding.FragmentDevelopmentBinding
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel

class DevelopmentFragment : Fragment() {

    private var _binding: FragmentDevelopmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevelopmentBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Update permission status display
        updatePermissionStatus()

        // Set up the refresh permissions button
        binding.btnRefreshPermissions.setOnClickListener {
            updatePermissionStatus()
        }

        // Set up the request permissions button
        binding.btnRequestPermissions.setOnClickListener {
            requestLocationPermissions()
        }

        // Set up the fake points button
        binding.btnFakePoints.setOnClickListener {
            // Get the CoordinatesViewModel to access the insertFakePoints method
            val coordinatesViewModel = ViewModelProvider(
                this,
                ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
            ).get(CoordinatesViewModel::class.java)

            coordinatesViewModel.insertFakePoints()
        }

        // Set up the clear all coordinates button with confirmation dialog
        binding.btnClearAllPoints.setOnClickListener {
            // Show confirmation dialog since this is a destructive action
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear All Coordinates")
                .setMessage("Are you sure you want to delete all coordinates? This action cannot be undone.")
                .setPositiveButton("Yes, Clear All") { _, _ ->
                    val coordinatesViewModel = ViewModelProvider(
                        this,
                        ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
                    ).get(CoordinatesViewModel::class.java)

                    coordinatesViewModel.deleteAllPoints()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        return root
    }

    private fun updatePermissionStatus() {
        try {
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val statusText = buildString {
                appendLine("Location Permissions:")
                appendLine("• Fine Location: ${if (fineLocationGranted) "✓ GRANTED" else "✗ DENIED"}")
                appendLine("• Coarse Location: ${if (coarseLocationGranted) "✓ GRANTED" else "✗ DENIED"}")
                appendLine()
                appendLine("Summary:")
                when {
                    fineLocationGranted || coarseLocationGranted -> {
                        appendLine("✓ Location access available")
                        appendLine("Add Point dialog should work properly")
                    }
                    else -> {
                        appendLine("✗ No location permissions granted")
                        appendLine("Add Point dialog will request permission first")
                    }
                }
            }

            binding.textPermissionStatus.text = statusText
        } catch (e: Exception) {
            binding.textPermissionStatus.text = "Error checking permissions: ${e.message}"
        }
    }

    private fun requestLocationPermissions() {
        // Request fine and coarse location permissions
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            // Update the permission status display after the user responds
            updatePermissionStatus()

            // Show a helpful message about the result
            val fineLocationGranted = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted = grantResults.getOrNull(1) == PackageManager.PERMISSION_GRANTED

            when {
                fineLocationGranted || coarseLocationGranted -> {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Location permissions granted! Add Point dialog should work now.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Location permissions denied. Add Point dialog will ask for permission each time.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update permission status when returning to this fragment
        // (in case permissions were granted/denied while away)
        if (_binding != null) {
            updatePermissionStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
}
