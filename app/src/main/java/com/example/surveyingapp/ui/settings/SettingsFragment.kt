package com.example.surveyingapp.ui.settings

import android.content.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.service.LocationService
import com.example.surveyingapp.ui.common.BaseTwoPaneFragment
import com.example.surveyingapp.util.ReachNameResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.jmdns.JmDNS


class SettingsFragment : BaseTwoPaneFragment() {

    // ─────────────────────────── Preferences / Data ───────────────────────────
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: CoordinateRepositoryImpl
    private val settingsRepo: SettingsRepository by lazy { SurveyingApp.settingsRepo }

    // Selected device for TCP connection (class scope)
    private var selectedDevice: Pair<String, Int>? = null
    private var selectedDeviceLabel: String? = null
    private var selectedDeviceName: String? = null
    private var autoReconnectAttempted = false // new flag
    private var provisionalConnectedUntil: Long = 0L // provisional connected status timeout

    // NMEA log state
    private var nmeaLogJob: Job? = null
    private var nmeaLogHasContent = false
    private val nmeaLines: ArrayDeque<String> = ArrayDeque()
    private val maxNmeaLines = 50

    private fun updateDeviceBox() {
        val root = currentContentView ?: return
        val box = root.findViewById<LinearLayout>(R.id.container_selected_device)
        val nameTv = root.findViewById<TextView>(R.id.text_device_name)
        val addrTv = root.findViewById<TextView>(R.id.text_device_address)
        val statusTv = root.findViewById<TextView>(R.id.text_device_status)
        val optionsLayout = root.findViewById<LinearLayout>(R.id.layout_rs2_options)
        val radioGroup = root.findViewById<RadioGroup>(R.id.radio_location_source)
        val externalActive = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp
        val dev = selectedDevice
        val nmeaContainer = root.findViewById<LinearLayout>(R.id.device_nmea_log_container)
        val nmeaLogText = root.findViewById<TextView>(R.id.text_nmea_log)
        if (!externalActive) {
            // Internal source: hide both
            box?.visibility = View.GONE
            optionsLayout?.visibility = View.GONE
            nmeaContainer?.visibility = View.GONE
            return
        }
        if (dev == null) {
            // No device selected yet: show options, hide box
            box?.visibility = View.GONE
            optionsLayout?.visibility = View.VISIBLE
            addrTv?.text = "IP:PORT" // reset to placeholder when no selection
            statusTv?.visibility = View.GONE
            nmeaContainer?.visibility = View.GONE
            return
        }
        // Device present: show box, hide options
        optionsLayout?.visibility = View.GONE
        val label = selectedDeviceLabel
        val parsedName = selectedDeviceName ?: label?.substringBefore("(")?.trim()?.takeIf { it.isNotBlank() }
        val host = dev.first
        val port = dev.second
        nameTv?.text = parsedName ?: host
        addrTv?.text = "$host:$port"
        box?.visibility = View.VISIBLE
        // Ensure status line is visible immediately with placeholder until streaming/connecting updates arrive
        statusTv?.let {
            if (it.visibility != View.VISIBLE) {
                it.visibility = View.VISIBLE
                it.text = "Disconnected"
            } else if (it.text.isNullOrBlank()) {
                it.text = "Disconnected"
            }
        }
        // Always show NMEA container when device selected; placeholder if no data yet
        nmeaContainer?.visibility = View.VISIBLE
        if (nmeaLines.isEmpty()) {
            nmeaLogText?.text = "(Waiting for NMEA sentences…)"
        }
    }

    // Sidebar categories
    private val categories = listOf(
        SettingsCategory(1, "Location", R.drawable.ic_section_location),
        SettingsCategory(2, "Data", R.drawable.ic_section_data),
        SettingsCategory(3, "Developer Tools", R.drawable.ic_dev_tools),
        SettingsCategory(4, "About", R.drawable.ic_home)
    )

    // Import/Export jobs
    private var coordinatesImportJob: Job? = null
    private var pendingImportUri: Uri? = null
    private var importTotal = 0
    private var importProcessed = 0

    // Track format for pending import (true if CSV)
    private var pendingImportIsCsv: Boolean = false

    // Custom file picker launchers for export and import
    private val customFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleCustomFilePickerResult(uri)
            }
        }
    }

    // State to track current operation
    private var currentOperation: String? = null // "export_json", "export_csv", "import_json", "import_csv"

    // ───────────────────────────── Bluetooth state ─────────────────────────────
    private var tcpDiscoveryJob: Job? = null
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var deviceStatusJob: Job? = null // added for RS2+ live status collection

    companion object {
        const val PREFS_NAME = "SurveyingAppPrefs"
        const val PREF_HIGH_ACCURACY = "high_accuracy"
        const val PREF_DEV_TOOLS = "dev_tools"
    }

    // ────────────────��──────────── Lifecycle hooks ─────────────────────────────
    override fun onRootCreated(root: View) {
        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        repository = CoordinateRepositoryImpl(AppDatabase.getDatabase(requireContext()).coordinateDao())
    }

    override fun provideCategories(): List<SettingsCategory> = categories

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        when (category.id) {
            1 -> setupLocationContent(inflater)
            2 -> setupDataContent(inflater)
            3 -> setupDeveloperContent(inflater)
            4 -> setupAboutContent(inflater)
            else -> null
        }

    // ───────────────────────────── Category builders ───────────────────────────
    private fun setupLocationContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_location, contentContainer, false)
        setupLocationSourceUi(view)
        return view
    }

    private fun setupDataContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_data, contentContainer, false)
        view.findViewById<Button>(R.id.btn_export_coordinates)?.setOnClickListener { showExportFormatDialog() }
        view.findViewById<Button>(R.id.btn_import_coordinates)?.setOnClickListener { showImportFormatDialog() }
        view.findViewById<Button>(R.id.btn_cancel_import)?.setOnClickListener { cancelActiveImport() }
        return view
    }

    private fun setupDeveloperContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_developer, contentContainer, false)
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_dev_tools)?.apply {
            isChecked = preferences.getBoolean(PREF_DEV_TOOLS, false)
            setOnCheckedChangeListener { _, v ->
                preferences.edit { putBoolean(PREF_DEV_TOOLS, v) }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.dev_toggle_developer_tools_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return view
    }

    private fun setupAboutContent(inflater: LayoutInflater): View =
        inflater.inflate(R.layout.content_settings_about, contentContainer, false)

    // ───────────────────────────── Location Source UI ─────────────────────────
    private fun setupLocationSourceUi(view: View) {
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_location_source)
        val internalGpsGroup = view.findViewById<LinearLayout>(R.id.group_internal_gps)
        val switchHighAccuracy = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_high_accuracy)
        val rs2OptionsLayout = view.findViewById<LinearLayout>(R.id.layout_rs2_options)
        val btnManualEntry = view.findViewById<Button>(R.id.btn_manual_entry)
        val btnScanDevice = view.findViewById<Button>(R.id.btn_scan_device)
        val deviceBox = view.findViewById<LinearLayout>(R.id.container_selected_device)
        val btnDisconnect = view.findViewById<Button>(R.id.btn_disconnect_device)
        val textDeviceStatus = view.findViewById<TextView>(R.id.text_device_status)
        val nmeaLogContainer = view.findViewById<LinearLayout>(R.id.device_nmea_log_container)
        val nmeaLogText = view.findViewById<TextView>(R.id.text_nmea_log)
        val nmeaScroll = view.findViewById<ScrollView>(R.id.scroll_nmea_log)

        btnDisconnect?.setOnClickListener {
            lifecycleScope.launch {
                settingsRepo.clearExternalTcp()
                selectedDevice = null
                selectedDeviceLabel = null
                selectedDeviceName = null
                updateDeviceBox() // will show options layout again
            }
        }

        // Observe stored TCP host/port to populate selected device label (covers app restart)
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepo.externalTcpHost.combine(settingsRepo.externalTcpPort) { h, p -> h to p }.collect { (host, port) ->
                if (host != null && port != null) {
                    val prev = selectedDevice
                    if (prev == null || prev.first != host || prev.second != port) {
                        selectedDevice = host to port
                        selectedDeviceLabel = selectedDeviceLabel ?: "$host:$port"
                        // don't overwrite a previously set friendly name
                        if (selectedDeviceName == null || selectedDeviceName == prev?.first || selectedDeviceName == host) {
                            // will be updated by name flow if persisted
                            selectedDeviceName = selectedDeviceName ?: host
                        }
                        updateDeviceBox()
                    }
                    // Attempt auto-reconnect once if external TCP was last selected
                    if (!autoReconnectAttempted) {
                        launch {
                            val locSrc = settingsRepo.locationSource.first()
                            val connType = settingsRepo.externalConnType.firstOrNull()
                            if (locSrc == LocationSourceType.EXTERNAL && connType == ExternalConnectionType.TCP) {
                                autoReconnectAttempted = true
                                connectViaTcpFlow(host, port)
                            }
                        }
                    }
                } else {
                    // Cleared
                    selectedDevice = null
                    selectedDeviceName = null
                    selectedDeviceLabel = null
                    updateDeviceBox()
                }
            }
        }
        // Observe persisted friendly name
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepo.externalTcpName.collect { storedName ->
                if (!storedName.isNullOrBlank() && selectedDevice != null) {
                    if (selectedDeviceName == null || selectedDeviceName == selectedDevice!!.first) {
                        selectedDeviceName = storedName
                        updateDeviceBox()
                    }
                }
            }
        }

        deviceStatusJob?.cancel()
        deviceStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            var lastFix: Fix? = null
            var lastStatus: LocationStatus = LocationStatus.Idle
            fun statusLine(status: LocationStatus, fix: Fix?): String {
                val now = System.currentTimeMillis()
                val effectiveStatus = if (status is LocationStatus.Idle && provisionalConnectedUntil > now && selectedDevice != null) {
                    // Treat as streaming (provisional) until real streaming event or timeout
                    LocationStatus.Streaming
                } else status
                return when (effectiveStatus) {
                    is LocationStatus.Connecting -> "Connecting…"
                    is LocationStatus.Error -> "Error"
                    is LocationStatus.Streaming -> buildString {
                        append("Connected")
                        val fixPart = when (fix?.rtkStatus) {
                            RtkStatus.FIX -> "Fixed RTK"
                            RtkStatus.FLOAT -> "Float"
                            RtkStatus.DGPS -> "DGPS"
                            RtkStatus.SINGLE -> "Single"
                            RtkStatus.INVALID -> "No Fix"
                            null -> "No Fix"
                        }
                        append(" • ").append(fixPart)
                        val satsUsed = fix?.satsUsed
                        val satsVis = fix?.satsVisible
                        if (satsUsed != null && satsVis != null) append(" • ").append("${satsUsed}/${satsVis} sats")
                        fix?.pdop?.let { append(" • PDOP ").append(String.format(java.util.Locale.US, "%.1f", it)) }
                        fix?.hdop?.let { append(" • HDOP ").append(String.format(java.util.Locale.US, "%.1f", it)) }
                        fix?.baseStationId?.let { append(" • Base ").append(it) }
                    }
                    LocationStatus.Idle -> "Disconnected"
                }
            }
            fun applyColor(tv: TextView?, status: LocationStatus, fix: Fix?, external: Boolean) {
                val now = System.currentTimeMillis()
                val effectiveStatus = if (status is LocationStatus.Idle && provisionalConnectedUntil > now && selectedDevice != null) {
                    LocationStatus.Streaming
                } else status
                val colorRes = if (!external) android.R.color.holo_blue_dark else when {
                    effectiveStatus is LocationStatus.Error -> android.R.color.holo_red_dark
                    effectiveStatus is LocationStatus.Connecting -> android.R.color.holo_orange_dark
                    fix?.rtkStatus == RtkStatus.FIX -> android.R.color.holo_green_dark
                    fix?.rtkStatus == RtkStatus.FLOAT -> android.R.color.holo_orange_dark
                    fix?.rtkStatus == RtkStatus.DGPS -> android.R.color.holo_blue_dark
                    fix?.rtkStatus == RtkStatus.SINGLE -> android.R.color.darker_gray
                    else -> android.R.color.holo_red_dark
                }
                tv?.setTextColor(requireContext().getColor(colorRes))
            }
            fun update() {
                val externalActive = view?.findViewById<RadioGroup>(R.id.radio_location_source)?.checkedRadioButtonId == R.id.radio_es2_tcp
                val statusView = view?.findViewById<TextView>(R.id.text_device_status)
                val show = externalActive && selectedDevice != null
                if (show) {
                    statusView?.visibility = View.VISIBLE
                    statusView?.text = statusLine(lastStatus, lastFix)
                } else {
                    statusView?.visibility = View.GONE
                }
                applyColor(statusView, lastStatus, lastFix, externalActive)
            }
            val mgr = SurveyingApp.locationManager
            launch { mgr.fixFlow.collect { f: Fix -> lastFix = f; if (f != null) provisionalConnectedUntil = 0L; update(); updateDeviceBox() } }
            launch { mgr.statusFlow.collectLatest { s: LocationStatus -> if (s is LocationStatus.Streaming) provisionalConnectedUntil = 0L; lastStatus = s; update() } }
            // Initial forced update so UI reflects existing (idle) state immediately
            update()
        }
        textDeviceStatus?.text = ""

        switchHighAccuracy?.apply {
            isChecked = preferences.getBoolean(PREF_HIGH_ACCURACY, true)
            setOnCheckedChangeListener { _, v ->
                preferences.edit { putBoolean(PREF_HIGH_ACCURACY, v) }
                Toast.makeText(requireContext(), "High accuracy: $v", Toast.LENGTH_SHORT).show()
            }
        }

        var initializing = true
        lifecycleScope.launch {
            // Early load stored host/port so UI can show device immediately after reopen
            val storedHost = settingsRepo.externalTcpHost.first()
            val storedPort = settingsRepo.externalTcpPort.first()
            if (storedHost != null && storedPort != null && selectedDevice == null) {
                selectedDevice = storedHost to storedPort
                selectedDeviceLabel = selectedDeviceLabel ?: "$storedHost:$storedPort"
                selectedDeviceName = selectedDeviceName ?: storedHost
            }
            val source = settingsRepo.locationSource.first()
            val sel = if (source == LocationSourceType.EXTERNAL) R.id.radio_es2_tcp else R.id.radio_internal
            radioGroup?.check(sel)
            updateLocationSourceVisibility(source, internalGpsGroup)
            // Now that radio is set, update device box with possibly restored device
            updateDeviceBox()
            // Attempt single auto reconnect if external source active and we have stored device
            if (source == LocationSourceType.EXTERNAL && storedHost != null && storedPort != null && !autoReconnectAttempted) {
                autoReconnectAttempted = true
                connectViaTcpFlow(storedHost, storedPort)
            }
            initializing = false
        }

        radioGroup?.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.radio_internal -> {
                    updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
                    rs2OptionsLayout?.visibility = View.GONE
                    selectedDevice = null
                    updateDeviceBox()
                    lifecycleScope.launch { settingsRepo.setLocationSource(LocationSourceType.INTERNAL) }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
                R.id.radio_es2_tcp -> {
                    updateLocationSourceVisibility(LocationSourceType.EXTERNAL, internalGpsGroup)
                    // Show either the device box (if already selected) or options
                    updateDeviceBox()
                    lifecycleScope.launch {
                        settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                        settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                    }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
            }
        }

        btnManualEntry?.setOnClickListener { showManualEntryDialog() }
        btnScanDevice?.setOnClickListener { showScanDeviceDialog() }
    }

    private fun showManualEntryDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val hostEdit = EditText(requireContext()).apply {
            hint = "IP Address or Hostname"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val portEdit = EditText(requireContext()).apply {
            hint = "Port"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(R.string.default_port)
        }

        // Pre-populate with last used IP address and port
        lifecycleScope.launch {
            val lastHost = settingsRepo.externalTcpHost.first()
            val lastPort = settingsRepo.externalTcpPort.first()

            if (!lastHost.isNullOrBlank()) {
                hostEdit.setText(lastHost)
            }
            if (lastPort != null && lastPort != 9001) { // Only if different from default
                portEdit.setText(lastPort.toString())
            }
        }

        container.addView(hostEdit); container.addView(portEdit)
        AlertDialog.Builder(requireContext())
            .setTitle("Manual Entry")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostEdit.text.toString().trim()
                val port = portEdit.text.toString().trim().toIntOrNull() ?: 9001
                selectedDevice = host to port
                selectedDeviceLabel = "$host:$port"
                selectedDeviceName = host
                updateDeviceBox()
                lifecycleScope.launch { connectViaTcpFlow(host, port) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showScanDeviceDialog() {
        val ctx = requireContext()
        val dialogView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,0) }
        val progressBar = ProgressBar(ctx).apply { isIndeterminate = true }
        val statusText = TextView(ctx).apply { text = ctx.getString(R.string.scan_scanning_rs2); setPadding(0,12,0,12); textSize = 14f }
        val spinner = Spinner(ctx).apply { visibility = View.GONE }

        // Add troubleshooting text
        val troubleshootText = TextView(ctx).apply {
            text = "Scanning network for RS2+ devices...\nMake sure device is on same WiFi network"
            setPadding(0, 8, 0, 16)
            textSize = 12f
            setTextColor(ctx.getColor(android.R.color.darker_gray))
        }

        dialogView.addView(progressBar)
        dialogView.addView(statusText)
        dialogView.addView(troubleshootText)
        dialogView.addView(spinner)

        val foundLabels = mutableListOf<String>()
        val deviceMap = LinkedHashMap<String, Pair<String, Int>>()
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, foundLabels)
        spinner.adapter = adapter
        var discoveryJob: Job? = null

        fun startDiscovery() {
            discoveryJob?.cancel()
            foundLabels.clear()
            deviceMap.clear()
            adapter.notifyDataSetChanged()
            progressBar.visibility = View.VISIBLE
            spinner.visibility = View.GONE
            statusText.text = "Scanning devices..."
            troubleshootText.text = "Phase 1: Scanning Bluetooth (10s)\nPhase 2: Network discovery (5s)\nPhase 3: Network sweep (if needed)"

            discoveryJob = viewLifecycleOwner.lifecycleScope.launch {
                var deviceCount = 0

                try {
                    withTimeoutOrNull(25000L) { // Increased timeout for 3-phase discovery
                        com.example.surveyingapp.util.ReachDiscoveryHelper.discoverReachDevices(ctx).collect { device ->
                            deviceCount++

                            // Enhanced label with discovery method and additional info
                            val methodLabel = when (device.discoveryMethod) {
                                "ble" -> "📱 BLE"
                                "mdns" -> "🌐 mDNS"
                                "http_sweep" -> "🔍 Network"
                                else -> "❓ Unknown"
                            }

                            val baseLabel = if (device.hostname != null && device.hostname.isNotBlank()) {
                                "${device.hostname} (${device.ip})"
                            } else {
                                // Use just the IP when no hostname instead of 'Device (...)'
                                device.ip
                            }

                            val extraInfo = mutableListOf<String>()
                            if (device.wifiSsid != null) extraInfo.add("WiFi: ${device.wifiSsid}")
                            if (device.bluetoothAddress != null) extraInfo.add("BT: ${device.bluetoothAddress}")

                            val label = if (extraInfo.isNotEmpty()) {
                                "$baseLabel [$methodLabel] - ${extraInfo.joinToString(", ")}"
                            } else {
                                "$baseLabel [$methodLabel]"
                            }

                            // Accept devices with either port open
                            if (device.port9001Open || device.port5000Open) {
                                if (!deviceMap.containsKey(label)) {
                                    deviceMap[label] = device.ip to 9001
                                    foundLabels += label
                                    adapter.notifyDataSetChanged()

                                    if (spinner.visibility != View.VISIBLE) {
                                        spinner.visibility = View.VISIBLE
                                        progressBar.visibility = View.GONE
                                        statusText.text = "Found devices - select one:"
                                        troubleshootText.text = "Found ${foundLabels.size} device(s) via ${device.discoveryMethod.uppercase()}"
                                    } else {
                                        troubleshootText.text = "Found ${foundLabels.size} device(s)"
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Device discovery error", e)
                }

                if (isActive) {
                    if (deviceMap.isEmpty()) {
                        progressBar.visibility = View.GONE
                        statusText.text = "No devices found"
                        troubleshootText.text = "No RS2+ devices found.\n\nTroubleshooting:\n• Ensure device is powered on and Bluetooth enabled\n• Check WiFi connection to same network\n• Try manual IP entry\n• Enable location permissions for BLE scanning"
                    } else if (statusText.text.toString().contains("Scanning")) {
                        statusText.text = "Found devices - select one:"
                        troubleshootText.text = "Found ${foundLabels.size} device(s)"
                    }
                }
            }
        }
        val alertDialog = AlertDialog.Builder(ctx)
            .setTitle("Scan for RS2+ Devices")
            .setView(dialogView)
            .setPositiveButton("Connect", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Rescan") { _, _ -> }
            .create()
        alertDialog.setOnShowListener {
            alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { startDiscovery() }
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = spinner.selectedItem as? String ?: return@setOnClickListener
                val (ip, port) = deviceMap[selected] ?: return@setOnClickListener
                selectedDevice = ip to port
                selectedDeviceLabel = selected
                selectedDeviceName = selected.substringBefore("(").trim()
                updateDeviceBox()
                alertDialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch { connectViaTcpFlow(ip, port) }
            }
            startDiscovery()
        }
        alertDialog.setOnDismissListener { discoveryJob?.cancel() }
        alertDialog.show()
    }

    // ───────────────────────────── Source Visibility ───────────────────────────
    private fun updateLocationSourceVisibility(selected: LocationSourceType, internal: LinearLayout?) {
        internal?.visibility = if (selected == LocationSourceType.INTERNAL) View.VISIBLE else View.GONE
    }

    // ─────────────────────────── TCP connect + read ────────────────────────────
    private suspend fun connectAndReadTcpNmea(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        var gotData = false
        try {
            socket = Socket()
            runCatching { socket.connect(InetSocketAddress(host, port), 4000) }.onFailure { return@withContext false }
            runCatching { socket.soTimeout = 3000 }
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 5000L) {
                val line = withTimeoutOrNull(1000L) { runCatching { reader.readLine() }.getOrNull() }
                if (line == null) continue
                if (line.startsWith("$")) { gotData = true; break }
            }
            if (!gotData) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.no_nmea_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            gotData
        } catch (_: Exception) {
            false
        } finally {
            runCatching { reader?.close() }
            runCatching { socket?.close() }
        }
    }

    private fun connectViaTcpFlow(host: String, port: Int) {
        lifecycleScope.launch {
            val success = connectAndReadTcpNmea(host, port)
            if (success) {
                Toast.makeText(requireContext(), "TCP connection successful", Toast.LENGTH_SHORT).show()
                settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                settingsRepo.setExternalTcp(host, port)
                // Provisional connected state for up to 8s while LocationManager transitions from Idle
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                updateDeviceBox()
                view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = "Connected • No Fix"
                    tv.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark))
                }
                // Resolve friendly Reach name
                if (selectedDeviceName == null || selectedDeviceName == host) {
                    launch {
                        val resolved = ReachNameResolver.resolveReachName(requireContext(), host)
                        if (!resolved.isNullOrBlank() && isAdded) {
                            selectedDeviceName = resolved
                            settingsRepo.setExternalTcpName(resolved)
                            updateDeviceBox()
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), "TCP connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───────────────────────────── Import / Export ─────────────────────────────
    private fun showExportFormatDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Export Format")
            .setItems(arrayOf("JSON", "CSV")) { d, which ->
                val ts = System.currentTimeMillis()
                when (which) {
                    0 -> exportJson(ts)
                    1 -> exportCsv(ts)
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportFormatDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Import Format")
            .setItems(arrayOf("JSON", "CSV")) { d, which ->
                when (which) {
                    0 -> importJson()
                    1 -> importCsv()
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Export helpers ---
    private fun exportJson(ts: Long) {
        currentOperation = "export_json_$ts"
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILTER_MODE, com.example.surveyingapp.ui.filepicker.FilePickerActivity.FILTER_MODE_FOLDER_SELECT)
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select Folder to Export JSON")
        }
        customFilePickerLauncher.launch(intent)
    }

    private fun exportCsv(ts: Long) {
        currentOperation = "export_csv_$ts"
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILTER_MODE, com.example.surveyingapp.ui.filepicker.FilePickerActivity.FILTER_MODE_FOLDER_SELECT)
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select Folder to Export CSV")
        }
        customFilePickerLauncher.launch(intent)
    }

    // --- Import helpers using custom file picker ---
    private fun importJson() {
        currentOperation = "import_json"
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILE_EXTENSIONS, arrayOf(".json"))
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select JSON File to Import")
        }
        customFilePickerLauncher.launch(intent)
    }

    private fun importCsv() {
        currentOperation = "import_csv"
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILE_EXTENSIONS, arrayOf(".csv"))
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select CSV File to Import")
        }
        customFilePickerLauncher.launch(intent)
    }

    private fun importDataFiles() {
        currentOperation = "import_data"
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILTER_MODE, com.example.surveyingapp.ui.filepicker.FilePickerActivity.FILTER_MODE_IMPORT_DATA)
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select Data File to Import")
        }
        customFilePickerLauncher.launch(intent)
    }

    private fun handleCustomFilePickerResult(uri: Uri) {
        when {
            currentOperation?.startsWith("export_json_") == true -> {
                val timestamp = currentOperation?.substringAfter("export_json_")?.toLongOrNull() ?: System.currentTimeMillis()
                performJsonExport(uri, timestamp)
            }
            currentOperation?.startsWith("export_csv_") == true -> {
                val timestamp = currentOperation?.substringAfter("export_csv_")?.toLongOrNull() ?: System.currentTimeMillis()
                performCsvExport(uri, timestamp)
            }
            currentOperation == "import_json" -> {
                pendingImportIsCsv = false
                lifecycleScope.launch { prepareImportCoordinatesWithConfirmation(uri, isCsv = false) }
            }
            currentOperation == "import_csv" -> {
                pendingImportIsCsv = true
                lifecycleScope.launch { prepareImportCoordinatesWithConfirmation(uri, isCsv = true) }
            }
            currentOperation == "import_data" -> {
                // Auto-detect file type based on extension
                val fileName = getFileNameFromUri(uri) ?: ""
                val isCsvFile = fileName.lowercase().endsWith(".csv")
                pendingImportIsCsv = isCsvFile
                lifecycleScope.launch { prepareImportCoordinatesWithConfirmation(uri, isCsv = isCsvFile) }
            }
        }
        currentOperation = null
    }

    private fun performJsonExport(folderUri: Uri, timestamp: Long) {
        lifecycleScope.launch {
            runCatching {
                val coords = withContext(Dispatchers.IO) { repository.getAllCoordinatesList() }
                val arr = org.json.JSONArray()
                coords.forEach { c ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", c.id); put("name", c.name); put("latitude", c.latitude); put("longitude", c.longitude)
                        put("altitude", c.altitude); put("timestamp", c.timestamp); put("icon", c.icon); put("color", c.color)
                    })
                }

                // Create file in selected folder
                val fileName = "coordinates_${timestamp}.json"

                withContext(Dispatchers.IO) {
                    if (folderUri.scheme == "file") {
                        // Handle file:// URIs directly
                        val folderPath = folderUri.path ?: throw Exception("Invalid folder path")
                        val file = java.io.File(folderPath, fileName)
                        file.writeText(arr.toString(2), StandardCharsets.UTF_8)
                    } else {
                        // Handle content:// URIs using DocumentsContract
                        val mimeType = "application/json"
                        val fileUri = DocumentsContract.createDocument(
                            requireContext().contentResolver,
                            folderUri,
                            mimeType,
                            fileName
                        ) ?: throw Exception("Failed to create document")

                        requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                            outputStream.write(arr.toString(2).toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "JSON exported successfully: $fileName", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performCsvExport(folderUri: Uri, timestamp: Long) {
        lifecycleScope.launch {
            runCatching {
                val coords = withContext(Dispatchers.IO) { repository.getAllCoordinatesList() }
                val sb = StringBuilder()
                sb.append("id,name,latitude,longitude,altitude,timestamp,icon,color\n")
                coords.forEach { c ->
                    fun esc(v: String): String = if (v.contains(',') || v.contains('"') || v.contains('\n')) '"' + v.replace("\"", "\"\"") + '"' else v
                    sb.append(esc(c.id)).append(',')
                        .append(esc(c.name)).append(',')
                        .append(c.latitude).append(',')
                        .append(c.longitude).append(',')
                        .append(c.altitude).append(',')
                        .append(c.timestamp).append(',')
                        .append(esc(c.icon)).append(',')
                        .append(c.color)
                        .append('\n')
                }

                // Create file in selected folder
                val fileName = "coordinates_${timestamp}.csv"

                withContext(Dispatchers.IO) {
                    if (folderUri.scheme == "file") {
                        // Handle file:// URIs directly
                        val folderPath = folderUri.path ?: throw Exception("Invalid folder path")
                        val file = java.io.File(folderPath, fileName)
                        file.writeText(sb.toString(), StandardCharsets.UTF_8)
                    } else {
                        // Handle content:// URIs using DocumentsContract
                        val mimeType = "text/csv"
                        val fileUri = DocumentsContract.createDocument(
                            requireContext().contentResolver,
                            folderUri,
                            mimeType,
                            fileName
                        ) ?: throw Exception("Failed to create document")

                        requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                            outputStream.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "CSV exported successfully: $fileName", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }

    // --- Import pipeline (extended for CSV) ---
    private suspend fun prepareImportCoordinatesWithConfirmation(uri: Uri, isCsv: Boolean) {
        val existing = withContext(Dispatchers.IO) { repository.getAllCoordinatesList().size }
        if (existing == 0) { launchImportCoordinates(uri, replace = false, isCsv = isCsv); return }
        pendingImportUri = uri
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(if (isCsv) "Import CSV" else getString(R.string.import_select_file_title))
            .setMessage(getString(R.string.import_merge_replace_message))
            .setPositiveButton(R.string.import_merge) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, false, isCsv) } }
            .setNeutralButton(R.string.import_replace) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, true, isCsv) } }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    private fun launchImportCoordinates(uri: Uri, replace: Boolean, isCsv: Boolean) {
        if (coordinatesImportJob?.isActive == true) return
        coordinatesImportJob = lifecycleScope.launch { importCoordinates(uri, replace, isCsv) }
    }

    private suspend fun importCoordinates(uri: Uri, replace: Boolean, isCsv: Boolean) {
        showImportProgress(true, 0, "Scanning…")
        importProcessed = 0; importTotal = 0
        runCatching {
            val raw = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use { inp ->
                    java.io.BufferedReader(java.io.InputStreamReader(inp, java.nio.charset.StandardCharsets.UTF_8)).readText()
                } ?: error("Unable to open input stream")
            }
            val list = if (isCsv) parseCsvCoordinates(raw) else parseJsonCoordinates(raw)
            importTotal = list.size.coerceAtLeast(1)
            showImportProgress(true, 70, "Writing ${list.size}…")
            withContext(Dispatchers.IO) { if (replace) repository.deleteAll(); repository.insertAll(list) }
            list.size to replace
        }.onSuccess { (count, replaced) ->
            showImportProgress(false, 100, "Completed")
            Toast.makeText(requireContext(), "Imported $count ${if (isCsv) "CSV" else "JSON"} (${if (replaced) "replaced" else "merged"})", Toast.LENGTH_LONG).show()
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

    private fun parseJsonCoordinates(raw: String): List<Coordinate> {
        val arr = org.json.JSONArray(raw)
        val list = mutableListOf<Coordinate>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
            val name = obj.optString("name", id)
            val lat = obj.optDouble("latitude")
            val lon = obj.optDouble("longitude")
            val alt = obj.optDouble("altitude", 0.0)
            val ts = obj.optLong("timestamp", now)
            val rawIcon = obj.optString("icon", "ic_pin")
            val icon = when (rawIcon) { "ic_menu_camera" -> "ic_pin"; "ic_menu_gallery" -> "ic_star"; "ic_menu_slideshow" -> "ic_home"; else -> rawIcon }
            val color = obj.optInt("color", 0xFF64B5F6.toInt())
            list.add(Coordinate(id, name, lat, lon, alt, ts, icon, color))
        }
        return list
    }

    private fun parseCsvCoordinates(raw: String): List<Coordinate> {
        val lines = raw.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().trim().lowercase()
        val hasHeader = header.contains("latitude") && header.contains("longitude")
        val dataLines = if (hasHeader) lines.drop(1) else lines
        val list = mutableListOf<Coordinate>()
        val now = System.currentTimeMillis()
        dataLines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size < 4) return@forEach
            fun col(i: Int): String = cols.getOrNull(i)?.trim().orEmpty()
            val idRaw = col(0)
            val name = col(1).ifBlank { idRaw }
            val lat = col(2).toDoubleOrNull() ?: return@forEach
            val lon = col(3).toDoubleOrNull() ?: return@forEach
            val alt = col(4).toDoubleOrNull() ?: 0.0
            val ts = col(5).toLongOrNull() ?: now
            val rawIcon = col(6).ifBlank { "ic_pin" }
            val icon = when (rawIcon) { "ic_menu_camera" -> "ic_pin"; "ic_menu_gallery" -> "ic_star"; "ic_menu_slideshow" -> "ic_home"; else -> rawIcon }
            val color = col(7).toLongOrNull()?.toInt() ?: 0xFF64B5F6.toInt()
            val id = if (idRaw.isBlank()) java.util.UUID.randomUUID().toString() else idRaw
            list.add(Coordinate(id, if (name.isBlank()) id else name, lat, lon, alt, ts, icon, color))
        }
        return list
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else { inQuotes = !inQuotes }
                }
                ',' -> if (!inQuotes) { result.add(sb.toString()); sb.setLength(0) } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun showImportProgress(visible: Boolean, percent: Int, status: String) {
        currentContentView?.findViewById<LinearLayout>(R.id.import_progress_container)?.let { c ->
            c.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                c.findViewById<ProgressBar>(R.id.progress_import)?.progress = percent.coerceIn(0, 100)
                c.findViewById<TextView>(R.id.text_import_progress)?.text = status
            }
        }
    }

    private fun cancelActiveImport() {
        coordinatesImportJob?.takeIf { it.isActive }?.cancel()?.also {
            Toast.makeText(requireContext(), R.string.import_cancel, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildUnifiedStatusLine(
        lmStatus: com.example.surveyingapp.domain.model.LocationStatus,
        fix: com.example.surveyingapp.domain.model.Fix?,
        isExternal: Boolean,
        deviceLabel: String?
    ): String {
        val sourcePart = if (isExternal) {
            val dev = deviceLabel ?: "(no device)"
            "RS2+ TCP $dev"
        } else "Internal GPS"
        val connPart = when (lmStatus) {
            is com.example.surveyingapp.domain.model.LocationStatus.Connecting -> "Connecting"
            is com.example.surveyingapp.domain.model.LocationStatus.Error -> "Error"
            com.example.surveyingapp.domain.model.LocationStatus.Idle -> if (isExternal) "Disconnected" else "Idle"
            is com.example.surveyingapp.domain.model.LocationStatus.Streaming -> "Connected"
        }
        val fixPart = if (!isExternal) {
            if (fix != null) "Fused" else "Fused (pending)"
        } else {
            when (fix?.rtkStatus) {
                com.example.surveyingapp.domain.model.RtkStatus.FIX -> "Fixed RTK"
                com.example.surveyingapp.domain.model.RtkStatus.FLOAT -> "Float"
                com.example.surveyingapp.domain.model.RtkStatus.DGPS -> "DGPS"
                com.example.surveyingapp.domain.model.RtkStatus.SINGLE -> "Single"
                com.example.surveyingapp.domain.model.RtkStatus.INVALID -> "No Fix"
                null -> if (lmStatus is com.example.surveyingapp.domain.model.LocationStatus.Streaming) "No Fix" else "--"
            }
        }
        val satsUsed = fix?.satsUsed
        val satsVis = fix?.satsVisible
        val satsPart = when {
            satsUsed != null && satsVis != null -> "${satsUsed}/${satsVis} sats"
            satsUsed != null -> "${satsUsed} sats"
            else -> "-- sats"
        }
        val pdop = fix?.pdop?.let { String.format(java.util.Locale.US, "%.1f", it) }
        val hdop = fix?.hdop?.let { String.format(java.util.Locale.US, "%.1f", it) }
        val dopPart = when {
            pdop != null && hdop != null -> "PDOP $pdop / HDOP $hdop"
            pdop != null -> "PDOP $pdop"
            hdop != null -> "HDOP $hdop"
            else -> "PDOP -- / HDOP --"
        }
        val basePart = if (isExternal) fix?.baseStationId?.let { "Base ID $it" } else null
        return listOfNotNull(sourcePart, connPart, fixPart, satsPart, dopPart, basePart).joinToString(" • ")
    }
}
