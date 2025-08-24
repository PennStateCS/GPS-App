package com.example.surveyingapp.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.surveyingapp.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferences: SharedPreferences

    companion object {
        const val PREFS_NAME = "SurveyingAppPrefs"
        const val PREF_SHOW_COORDINATES = "show_coordinates"
        const val PREF_SHOW_ELEVATION = "show_elevation"
        const val PREF_HIGH_ACCURACY = "high_accuracy"
        const val PREF_AUTO_SAVE = "auto_save"
        const val PREF_DARK_MODE = "dark_mode"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load saved preferences
        binding.switchHighAccuracy.isChecked = preferences.getBoolean(PREF_HIGH_ACCURACY, true)
        binding.switchAutoSave.isChecked = preferences.getBoolean(PREF_AUTO_SAVE, true)
        binding.switchShowCoordinates.isChecked = preferences.getBoolean(PREF_SHOW_COORDINATES, false)
        binding.switchShowElevation.isChecked = preferences.getBoolean(PREF_SHOW_ELEVATION, false)
        binding.switchDarkMode.isChecked = preferences.getBoolean(PREF_DARK_MODE, false)

        // Set up switch listeners with preference saving
        binding.switchHighAccuracy.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_HIGH_ACCURACY, isChecked).apply()
            Toast.makeText(requireContext(), "High accuracy: $isChecked", Toast.LENGTH_SHORT).show()
        }

        binding.switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_AUTO_SAVE, isChecked).apply()
            Toast.makeText(requireContext(), "Auto-save: $isChecked", Toast.LENGTH_SHORT).show()
        }

        binding.switchShowCoordinates.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_SHOW_COORDINATES, isChecked).apply()
            Toast.makeText(requireContext(), "Show coordinates: $isChecked", Toast.LENGTH_SHORT).show()
        }

        binding.switchShowElevation.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_SHOW_ELEVATION, isChecked).apply()
            Toast.makeText(requireContext(), "Show elevation: $isChecked", Toast.LENGTH_SHORT).show()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREF_DARK_MODE, isChecked).apply()
            Toast.makeText(requireContext(), "Dark mode: $isChecked", Toast.LENGTH_SHORT).show()
        }

        // Set up button listeners
        binding.btnExportData.setOnClickListener {
            // TODO: Implement data export
            Toast.makeText(requireContext(), "Export functionality coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnImportData.setOnClickListener {
            // TODO: Implement data import
            Toast.makeText(requireContext(), "Import functionality coming soon", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
