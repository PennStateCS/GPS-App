package com.example.surveyingapp.ui.settings

// SettingsFragment handles user preferences (toggles) and coordinate import/export (JSON).
// Uses coroutines for I/O with cancellable long-running import and progress UI.

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.data.CoordinateRepository
import com.example.surveyingapp.data.settings.SettingsRepository
import com.example.surveyingapp.databinding.FragmentSettingsBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: SharedPreferences
    private lateinit var repository: CoordinateRepository
    private val settingsRepo: SettingsRepository by lazy { SurveyingApp.settingsRepo }

    // Active import coroutine job (for cancellation)
    private var coordinatesImportJob: Job? = null

    // Pending file chosen before merge/replace decision
    private var pendingImportUri: Uri? = null

    // Import progress counters
    private var importTotal = 0
    private var importProcessed = 0

    // Export launcher (create JSON document)
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) lifecycleScope.launch { exportCoordinates(uri) }
    }

    // Import launcher (open existing JSON file)
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch { prepareImportCoordinatesWithConfirmation(uri) }
    }

    // Views for new location source UI
    private val radioGroupSource: RadioGroup by lazy { binding.radioLocationSource }
    private val radioInternal get() = binding.radioInternal
    private val radioExternal get() = binding.radioExternal
    private val externalGroup get() = binding.groupExternalConfig
    private val spinnerConnType: Spinner by lazy { binding.spinnerConnType }
    private val btGroup get() = binding.groupBt
    private val tcpGroup get() = binding.groupTcp
    private val btPickBtn get() = binding.btnPickBt
    private val btSelectedText get() = binding.textBtSelected
    private val tcpHostEdit: EditText by lazy { binding.editTcpHost }
    private val tcpPortEdit: EditText by lazy { binding.editTcpPort }
    private val testBtn get() = binding.btnTestNmea
    private val saveBtn get() = binding.btnSaveExternal
    private val previewText get() = binding.textNmeaPreview

    companion object {
        const val PREFS_NAME = "SurveyingAppPrefs"
        const val PREF_SHOW_COORDINATES = "show_coordinates"
        const val PREF_SHOW_ELEVATION = "show_elevation"
        const val PREF_HIGH_ACCURACY = "high_accuracy"
        const val PREF_DARK_MODE = "dark_mode"
        const val PREF_DEV_TOOLS = "dev_tools"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root = binding.root

        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        repository = CoordinateRepository(AppDatabase.getDatabase(requireContext()).coordinateDao())

        // Initialize switches
        binding.switchHighAccuracy.isChecked = preferences.getBoolean(PREF_HIGH_ACCURACY, true)
        binding.switchShowCoordinates.isChecked = preferences.getBoolean(PREF_SHOW_COORDINATES, false)
        binding.switchShowElevation.isChecked = preferences.getBoolean(PREF_SHOW_ELEVATION, false)
        binding.switchDarkMode.isChecked = preferences.getBoolean(PREF_DARK_MODE, false)
        binding.switchDevTools.isChecked = preferences.getBoolean(PREF_DEV_TOOLS, false)

        // Listeners
        binding.switchHighAccuracy.setOnCheckedChangeListener { _, v ->
            preferences.edit { putBoolean(PREF_HIGH_ACCURACY, v) }
            Toast.makeText(requireContext(), "High accuracy: $v", Toast.LENGTH_SHORT).show()
        }
        binding.switchShowCoordinates.setOnCheckedChangeListener { _, v ->
            preferences.edit { putBoolean(PREF_SHOW_COORDINATES, v) }
            Toast.makeText(requireContext(), "Show coordinates: $v", Toast.LENGTH_SHORT).show()
        }
        binding.switchShowElevation.setOnCheckedChangeListener { _, v ->
            preferences.edit { putBoolean(PREF_SHOW_ELEVATION, v) }
            Toast.makeText(requireContext(), "Show elevation: $v", Toast.LENGTH_SHORT).show()
        }
        binding.switchDarkMode.setOnCheckedChangeListener { _, v ->
            preferences.edit { putBoolean(PREF_DARK_MODE, v) }
            Toast.makeText(requireContext(), "Dark mode: $v", Toast.LENGTH_SHORT).show()
        }
        binding.switchDevTools.setOnCheckedChangeListener { _, v ->
            preferences.edit { putBoolean(PREF_DEV_TOOLS, v) }
            Toast.makeText(requireContext(), "Developer tools: $v", Toast.LENGTH_SHORT).show()
        }

        // Buttons
        binding.btnExportCoordinates.setOnClickListener { startExportCoordinatesFlow() }
        binding.btnImportCoordinates.setOnClickListener { startImportCoordinatesFlow() }
        binding.btnCancelImport.setOnClickListener { cancelActiveImport() }

        setupLocationSourceUi()

        return root
    }

    private fun setupLocationSourceUi() {
        // Spinner entries
        spinnerConnType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("Bluetooth", "TCP"))
        lifecycleScope.launch {
            val source = settingsRepo.locationSource.first()
            val connType = settingsRepo.externalConnType.first()
            val btName = settingsRepo.externalBtName.first()
            val btAddr = settingsRepo.externalBtAddress.first()
            val tcpHost = settingsRepo.externalTcpHost.first()
            val tcpPort = settingsRepo.externalTcpPort.first()
            if (source == "external") radioExternal.isChecked else radioInternal.isChecked = true
            updateExternalVisibility(source == "external")
            if (connType == "tcp") spinnerConnType.setSelection(1) else spinnerConnType.setSelection(0)
            updateConnTypeVisibility(connType)
            if (!btName.isNullOrBlank()) btSelectedText.text = "$btName ($btAddr)" else btSelectedText.text = "None"
            tcpHostEdit.setText(tcpHost ?: "")
            tcpPortEdit.setText(tcpPort?.toString() ?: "")
        }
        radioGroupSource.setOnCheckedChangeListener { _, checkedId ->
            val external = checkedId == radioExternal.id
            updateExternalVisibility(external)
            lifecycleScope.launch { settingsRepo.setLocationSource(if (external) "external" else "internal") }
        }
        spinnerConnType.setOnItemSelectedListener(object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val ct = if (position == 1) "tcp" else "bt"
                updateConnTypeVisibility(ct)
                lifecycleScope.launch { settingsRepo.setExternalConnType(ct) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        })
        btPickBtn.setOnClickListener { showBluetoothPicker() }
        testBtn.setOnClickListener { lifecycleScope.launch { testNmeaConnection() } }
        saveBtn.setOnClickListener { lifecycleScope.launch { saveExternalConfig() } }
    }

    private fun updateExternalVisibility(show: Boolean) {
        externalGroup.visibility = if (show) View.VISIBLE else View.GONE
    }
    private fun updateConnTypeVisibility(type: String) {
        btGroup.visibility = if (type == "bt") View.VISIBLE else View.GONE
        tcpGroup.visibility = if (type == "tcp") View.VISIBLE else View.GONE
    }

    private fun showBluetoothPicker() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(requireContext(), "Bluetooth not available", Toast.LENGTH_SHORT).show(); return
        }
        val bonded = adapter.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            Toast.makeText(requireContext(), "No paired devices", Toast.LENGTH_SHORT).show(); return
        }
        val names = bonded.map { d -> "${d.name} (${d.address})" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select RS2+ Device")
            .setItems(names) { _, which ->
                val dev = bonded[which]
                btSelectedText.text = "${dev.name} (${dev.address})"
                lifecycleScope.launch { settingsRepo.setExternalBt(dev.address, dev.name ?: "RS2+") }
            }
            .show()
    }

    private suspend fun saveExternalConfig() {
        val connType = if (spinnerConnType.selectedItemPosition == 1) "tcp" else "bt"
        if (connType == "tcp") {
            val host = tcpHostEdit.text.toString().trim()
            val port = tcpPortEdit.text.toString().toIntOrNull()
            if (host.isBlank() || port == null) {
                Toast.makeText(requireContext(), "Enter host & port", Toast.LENGTH_SHORT).show(); return
            }
            settingsRepo.setExternalTcp(host, port)
        }
        Toast.makeText(requireContext(), "External config saved", Toast.LENGTH_SHORT).show()
    }

    private suspend fun testNmeaConnection() {
        previewText.text = "Testing..."
        val ct = if (spinnerConnType.selectedItemPosition == 1) "tcp" else "bt"
        val lines = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            try {
                if (ct == "tcp") {
                    val host = tcpHostEdit.text.toString().trim()
                    val port = tcpPortEdit.text.toString().toIntOrNull()
                    if (host.isNotBlank() && port != null) {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, port), 4000)
                            BufferedReader(InputStreamReader(s.getInputStream())).use { r ->
                                var count = 0
                                while (count < 3) {
                                    val l = r.readLine() ?: break
                                    if (l.startsWith("$") ) {
                                        lines += l
                                        count++
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val addr = settingsRepo.externalBtAddress.first()
                    if (!addr.isNullOrBlank()) {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        val dev: BluetoothDevice = adapter.getRemoteDevice(addr)
                        val sock = dev.createRfcommSocketToServiceRecord(java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                        adapter.cancelDiscovery()
                        sock.connect()
                        sock.inputStream.bufferedReader().use { r ->
                            var count = 0
                            while (count < 3) {
                                val l = r.readLine() ?: break
                                if (l.startsWith("$")) { lines += l; count++ }
                            }
                        }
                        try { sock.close() } catch (_:Exception){}
                    }
                }
            } catch (e: Exception) {
                lines += "ERROR: ${e.message}" }
        }
        previewText.text = if (lines.isEmpty()) "No data" else lines.joinToString("\n")
    }

    private fun startExportCoordinatesFlow() {
        exportLauncher.launch("coordinates_${System.currentTimeMillis()}.json")
    }

    private fun startImportCoordinatesFlow() {
        if (coordinatesImportJob?.isActive == true) {
            Toast.makeText(requireContext(), "Import already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Import Coordinates")
            .setMessage("Select a JSON file containing coordinates to import. Proceed?")
            .setPositiveButton("Select File") { _, _ ->
                importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun exportCoordinates(uri: Uri) {
        runCatching {
            val coords = withContext(Dispatchers.IO) { repository.getAllPointsList() }
            val arr = JSONArray()
            coords.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("latitude", c.latitude)
                    put("longitude", c.longitude)
                    put("altitude", c.altitude)
                    put("timestamp", c.timestamp)
                    put("icon", c.icon)
                    put("color", c.color)
                })
            }
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri, "w")?.use { os ->
                    os.write(arr.toString(2).toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                } ?: error("Unable to open output stream")
            }
        }.onSuccess {
            Toast.makeText(requireContext(), "Exported coordinates to file", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun prepareImportCoordinatesWithConfirmation(uri: Uri) {
        val existing = withContext(Dispatchers.IO) { repository.getAllPointsList().size }
        if (existing == 0) {
            launchImportCoordinates(uri, replace = false)
            return
        }
        pendingImportUri = uri
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Import Coordinates")
            .setMessage("Existing: $existing. Merge adds; Replace clears first.")
            .setPositiveButton("Merge") { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, false) } }
            .setNeutralButton("Replace") { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, true) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchImportCoordinates(uri: Uri, replace: Boolean) {
        if (coordinatesImportJob?.isActive == true) return
        coordinatesImportJob = lifecycleScope.launch { importCoordinates(uri, replace) }
    }

    private suspend fun importCoordinates(uri: Uri, replace: Boolean) {
        showImportProgress(true, 0, "Starting import…")
        importProcessed = 0
        importTotal = 0
        runCatching {
            val raw = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use { inp ->
                    BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
                } ?: error("Unable to open input stream")
            }
            val arr = JSONArray(raw)
            importTotal = arr.length().coerceAtLeast(1)
            val list = mutableListOf<Coordinate>()
            val detailed = importTotal > 50
            for (i in 0 until arr.length()) {
                if (!coroutineContext.isActive) throw CancellationException("Import canceled")
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = obj.optString("name", id)
                val lat = obj.optDouble("latitude")
                val lon = obj.optDouble("longitude")
                val alt = obj.optDouble("altitude", 0.0)
                val ts = obj.optLong("timestamp", System.currentTimeMillis())
                val icon = obj.optString("icon", "ic_menu_camera")
                val color = obj.optInt("color", 0xFF64B5F6.toInt())
                list.add(Coordinate(id, name, lat, lon, alt, ts, icon, color))
                importProcessed = i + 1
                if (detailed && i % 10 == 0) {
                    val pct = ((i + 1) * 100 / importTotal).coerceAtMost(85)
                    showImportProgress(true, pct, "Parsing $importProcessed/$importTotal…")
                }
            }
            showImportProgress(true, 90, "Writing to database…")
            withContext(Dispatchers.IO) {
                if (replace) repository.deleteAll()
                repository.insertAll(list)
            }
            list.size to replace
        }.onSuccess { (count, replaced) ->
            showImportProgress(false, 100, "Completed")
            Toast.makeText(requireContext(), "Imported $count coordinates (${if (replaced) "replaced" else "merged"})", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            if (e is CancellationException) {
                showImportProgress(false, 0, "Canceled")
            } else {
                showImportProgress(false, 0, "Error")
                Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        coordinatesImportJob = null
    }

    private fun cancelActiveImport() {
        val job = coordinatesImportJob
        if (job?.isActive == true) {
            job.cancel()
            Toast.makeText(requireContext(), "Canceling import…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImportProgress(visible: Boolean, percent: Int, status: String) {
        val b = _binding ?: return
        b.importProgressContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            b.progressImport.progress = percent.coerceIn(0, 100)
            b.textImportProgress.text = status
        }
    }

    override fun onDestroyView() {
        coordinatesImportJob?.cancel()
        coordinatesImportJob = null
        super.onDestroyView()
        _binding = null
    }
}
