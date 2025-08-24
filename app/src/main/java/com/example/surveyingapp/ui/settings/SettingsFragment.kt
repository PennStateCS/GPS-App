package com.example.surveyingapp.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.data.CoordinateRepository
import com.example.surveyingapp.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: CoordinateRepository

    private var pendingImportUri: Uri? = null
    private var currentImportJob: Job? = null
    private var importTotal: Int = 0
    private var importProcessed: Int = 0

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch { performExport(uri) }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch { prepareImportWithConfirmation(uri) }
        }
    }

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
        repository = CoordinateRepository(AppDatabase.getDatabase(requireContext()).coordinateDao())

        // Load saved preferences
        binding.switchHighAccuracy.isChecked = preferences.getBoolean(PREF_HIGH_ACCURACY, true)
        binding.switchAutoSave.isChecked = preferences.getBoolean(PREF_AUTO_SAVE, true)
        binding.switchShowCoordinates.isChecked = preferences.getBoolean(PREF_SHOW_COORDINATES, false)
        binding.switchShowElevation.isChecked = preferences.getBoolean(PREF_SHOW_ELEVATION, false)
        binding.switchDarkMode.isChecked = preferences.getBoolean(PREF_DARK_MODE, false)

        // Switch listeners
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

        // Data buttons
        binding.btnExportData.setOnClickListener { startExportFlow() }
        binding.btnImportData.setOnClickListener { startImportFlow() }
        binding.btnCancelImport.setOnClickListener {
            val job = currentImportJob
            if (job?.isActive == true) {
                job.cancel()
                showImportProgress(false, 0, "Canceled")
                Toast.makeText(requireContext(), "Import canceled ($importProcessed / $importTotal parsed)", Toast.LENGTH_LONG).show()
            }
        }

        return root
    }

    private fun startExportFlow() {
        exportLauncher.launch("survey_points_${System.currentTimeMillis()}.json")
    }

    private fun startImportFlow() {
        // Prevent launching if an import is already running
        if (currentImportJob?.isActive == true) {
            Toast.makeText(requireContext(), "Import already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        // Show confirmation dialog before opening system file picker
        AlertDialog.Builder(requireContext())
            .setTitle("Import Points")
            .setMessage("Select a JSON file containing points to import. You can cancel after choosing merge or replace. Proceed?")
            .setPositiveButton("Select File") { _, _ ->
                importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun performExport(uri: Uri) {
        runCatching {
            val points = withContext(Dispatchers.IO) { repository.getAllPointsList() }
            val jsonArray = JSONArray()
            points.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("latitude", p.latitude)
                    put("longitude", p.longitude)
                    put("altitude", p.altitude)
                    put("timestamp", p.timestamp)
                    put("icon", p.icon)
                    put("color", p.color)
                }
                jsonArray.put(obj)
            }
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri, "w")?.use { os ->
                    os.write(jsonArray.toString(2).toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                } ?: error("Unable to open output stream")
            }
        }.onSuccess {
            Toast.makeText(requireContext(), "Exported points to file", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun prepareImportWithConfirmation(uri: Uri) {
        // Check existing points count
        val existingCount = withContext(Dispatchers.IO) { repository.getAllPointsList().size }
        if (existingCount == 0) {
            // No existing points, just import (merge semantics identical)
            performImport(uri, replace = false)
            return
        }
        pendingImportUri = uri
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Import Points")
            .setMessage("Existing points: $existingCount. How would you like to apply imported data?\n\nMerge: Add/update without clearing.\nReplace: Clear all existing first.")
            .setPositiveButton("Merge") { _, _ ->
                pendingImportUri?.let { launchImport(it, replace = false) }
            }
            .setNeutralButton("Replace") { _, _ ->
                pendingImportUri?.let { launchImport(it, replace = true) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchImport(uri: Uri, replace: Boolean) {
        currentImportJob = lifecycleScope.launch { performImport(uri, replace) }
    }

    private suspend fun performImport(uri: Uri, replace: Boolean) {
        showImportProgress(true, 0, "Starting import...")
        importProcessed = 0
        importTotal = 0
        runCatching {
            val jsonText = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
                } ?: error("Unable to open input stream")
            }
            val arr = JSONArray(jsonText)
            importTotal = arr.length().coerceAtLeast(1)
            val list = mutableListOf<Coordinate>()
            // Threshold for showing detailed progress (e.g., more than 50 points)
            val detailed = importTotal > 50
            for (i in 0 until arr.length()) {
                if (!coroutineContext.isActive) throw CancellationException("Import canceled")
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = obj.optString("name", id)
                val latitude = obj.optDouble("latitude")
                val longitude = obj.optDouble("longitude")
                val altitude = obj.optDouble("altitude", 0.0)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val icon = obj.optString("icon", "ic_menu_camera")
                val color = obj.optInt("color", 0xFF64B5F6.toInt())
                list.add(Coordinate(id, name, latitude, longitude, altitude, timestamp, icon, color))
                importProcessed = i + 1
                if (detailed && i % 10 == 0) {
                    val pct = ((i + 1) * 100 / importTotal).coerceAtMost(85)
                    showImportProgress(true, pct, "Parsing $importProcessed/$importTotal...")
                }
            }
            showImportProgress(true, 90, "Writing to database...")
            withContext(Dispatchers.IO) {
                if (replace) repository.deleteAll()
                repository.insertAll(list)
            }
            list.size to replace
        }.onSuccess { (count, replaced) ->
            currentImportJob = null
            showImportProgress(false, 100, "Completed")
            Toast.makeText(requireContext(), "Imported $count points (${if (replaced) "replaced" else "merged"})", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            currentImportJob = null
            if (e is CancellationException) {
                // UI already handled in cancel click; ensure progress hidden
                showImportProgress(false, 0, "Canceled")
            } else {
                showImportProgress(false, 0, "Error")
                Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showImportProgress(visible: Boolean, percent: Int, status: String) {
        if (_binding == null) return
        binding.importProgressContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.progressImport.progress = percent.coerceIn(0, 100)
            binding.textImportProgress.text = status
        }
    }

    override fun onDestroyView() {
        currentImportJob?.cancel()
        currentImportJob = null
        super.onDestroyView()
        _binding = null
    }
}
