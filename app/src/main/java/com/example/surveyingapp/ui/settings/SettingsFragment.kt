package com.example.surveyingapp.ui.settings

import android.content.*
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.EmlidDeviceInfo
import com.example.surveyingapp.domain.model.SelfTest
import com.example.surveyingapp.domain.model.TestStatus
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.domain.repository.ReachDeviceRepository
import com.example.surveyingapp.service.LocationService
import com.example.surveyingapp.ui.common.BaseTwoPaneFragment
import com.example.surveyingapp.util.ReachNameResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard
    @Inject
    lateinit var sourceSettings: com.example.surveyingapp.gnss.settings.SourceSettings
    @Inject
    lateinit var reachDeviceRepository: ReachDeviceRepository

    // Comment out the SettingsViewModel injection for now since it may not exist
    // @Inject
    // lateinit var settingsViewModel: SettingsViewModel

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

    // Emlid device information
    private var currentDeviceInfo: EmlidDeviceInfo? = null

    // Missing properties for import/export functionality
    private var currentOperation: String? = null
    private var pendingImportUri: Uri? = null
    private var pendingImportIsCsv: Boolean = false
    private var coordinatesImportJob: Job? = null
    private var importProcessed: Int = 0
    private var importTotal: Int = 0
    private var deviceStatusJob: Job? = null

    // Activity result launcher for file picker
    private val customFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleCustomFilePickerResult(uri)
            }
        }
    }

    private enum class TcpTestResult { CONNECT_FAILED, CONNECTED_NO_DATA, RECEIVING_NMEA, RECEIVING_RTCM_OR_BIN }

    // UI status for external connection
    private enum class ConnectionUiStatus { CONNECTING, STREAMING, STALE, DISCONNECTED }

    private fun updateDeviceBox() {
        val root = currentContentView ?: return
        val box = root.findViewById<LinearLayout>(R.id.container_selected_device)
        val statusTv = root.findViewById<TextView>(R.id.text_device_status)
        val optionsLayout = root.findViewById<LinearLayout>(R.id.layout_rs2_options)
        val radioGroup = root.findViewById<RadioGroup>(R.id.radio_location_source)
        val externalActive = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp
        val dev = selectedDevice

        if (!externalActive) {
            // Internal source: hide diagnostic container
            box?.visibility = View.GONE
            optionsLayout?.visibility = View.GONE
            return
        }

        if (dev == null) {
            // No device selected yet: show options, hide box
            box?.visibility = View.GONE
            optionsLayout?.visibility = View.VISIBLE
            statusTv?.visibility = View.GONE
            return
        }

        // Device present: show box, hide options
        optionsLayout?.visibility = View.GONE
        box?.visibility = View.VISIBLE

        // Ensure status line is visible immediately with placeholder until streaming/connecting updates arrive
        statusTv?.let {
            if (it.visibility != View.VISIBLE) {
                it.visibility = View.VISIBLE
                it.text = getString(R.string.disconnected)
            } else if (it.text.isNullOrBlank()) {
                it.text = getString(R.string.disconnected)
            }
        }
    }

    companion object {
        // Preference keys (public for external access e.g., MainActivity)
        const val PREFS_NAME = "SurveyingAppPrefs"
        const val PREF_HIGH_ACCURACY = "high_accuracy"
        const val PREF_DEV_TOOLS = "dev_tools"
        // Category IDs (internal)
        private const val CAT_ID_LOCATION = 1
        private const val CAT_ID_DATA = 2
        private const val CAT_ID_DEV = 3
        private const val CAT_ID_ABOUT = 4
        // Connection status timing thresholds (ms)
        const val FRESH_FIX_MAX_AGE_MS = 5_000L
        const val STALE_FIX_MAX_AGE_MS = 15_000L
    }

    // ────────────────��──────────── Lifecycle hooks ─────────────────────────────
    override fun onRootCreated(root: View) {
        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        repository = CoordinateRepositoryImpl(AppDatabase.getDatabase(requireContext()).coordinateDao())
    }

    override fun provideCategories(): List<SettingsCategory> = baseCategories()

    private fun baseCategories(): List<SettingsCategory> = listOf(
        SettingsCategory(CAT_ID_LOCATION, "Location", R.drawable.ic_section_location),
        SettingsCategory(CAT_ID_DATA, "Data", R.drawable.ic_section_data),
        SettingsCategory(CAT_ID_DEV, "Developer Tools", R.drawable.ic_dev_tools),
        SettingsCategory(CAT_ID_ABOUT, "About", R.drawable.ic_home)
    )

    private fun refreshCategoriesForSource(@Suppress("UNUSED_PARAMETER") source: LocationSourceType) {
        // RS2+ page removed from Settings; categories are static
        updateCategoriesDynamic(baseCategories())
    }

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        when (category.id) {
            CAT_ID_LOCATION -> setupLocationContent(inflater)
            CAT_ID_DATA -> setupDataContent(inflater)
            CAT_ID_DEV -> setupDeveloperContent(inflater)
            CAT_ID_ABOUT -> setupAboutContent(inflater)
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
        val editHost = view.findViewById<EditText>(R.id.edit_host)
        val editPort = view.findViewById<EditText>(R.id.edit_port)
        val btnConnect = view.findViewById<Button>(R.id.btn_connect)
        val btnDisconnect = view.findViewById<Button>(R.id.btn_disconnect_device)
        val textDeviceStatus = view.findViewById<TextView>(R.id.text_device_status)

        // Wire up device info button handlers - now observes the repository's StateFlow
        val btnRefreshDeviceInfo = view.findViewById<Button>(R.id.btn_refresh_device_info)
        btnRefreshDeviceInfo?.setOnClickListener {
            // The repository automatically polls device info when external source is active
            // Show current info or message if not available
            val currentInfo = reachDeviceRepository.deviceInfo.value
            if (currentInfo != null) {
                // Convert ReachDeviceInfo to EmlidDeviceInfo for display
                currentDeviceInfo = convertToEmlidDeviceInfo(currentInfo)
                updateDeviceInfoDisplay()
            } else {
                Toast.makeText(requireContext(), "Device info not available. Ensure external connection is active.", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe device info from repository
        viewLifecycleOwner.lifecycleScope.launch {
            reachDeviceRepository.deviceInfo.collect { reachInfo ->
                if (reachInfo != null) {
                    currentDeviceInfo = convertToEmlidDeviceInfo(reachInfo)
                    updateDeviceInfoDisplay()

                    // Update device name if we got a better name from the device info
                    if (selectedDeviceName == selectedDevice?.first || selectedDeviceName.isNullOrBlank()) {
                        selectedDeviceName = reachInfo.name ?: selectedDevice?.first
                        selectedDevice?.let { (host, port) ->
                            settingsRepo.setExternalTcp(host, port, selectedDeviceName ?: "Unknown Device")
                        }
                        updateDeviceBox()
                    }
                }
            }
        }

        var lastDiagnosed: Pair<String, Int>? = null

        fun runDiagnostic(host: String, port: Int) {
            val pair = host to port
            if (host.isBlank()) return
            // Skip if same as last diagnosis to avoid spam
            if (lastDiagnosed == pair) return
            lastDiagnosed = pair
            lifecycleScope.launch {
                val (result, _) = withContext(Dispatchers.IO) { testRs2Tcp(host, port) }

                // The ReachDeviceRepository will automatically start polling device info
                // when the external source becomes active through the settings change below.
                // The device info will be available through the StateFlow observer we set up earlier.

                // Resolve friendly Reach name (fallback if device info fails)
                if (selectedDeviceName == null || selectedDeviceName == host) {
                    launch {
                        val resolved = ReachNameResolver.resolveReachName(requireContext(), host)
                        if (!resolved.isNullOrBlank() && isAdded) {
                            // Only use resolved name if we didn't get a better name from device info
                            if (currentDeviceInfo == null) {
                                selectedDeviceName = resolved
                                settingsRepo.setExternalTcp(host, port, resolved)
                                updateDeviceBox()
                            }
                        }
                    }
                }
            }
        }

        fun attemptConnectFromInline() {
            val host = editHost?.text?.toString()?.trim().orEmpty()
            val port = editPort?.text?.toString()?.trim()?.toIntOrNull() ?: 9001
            if (host.isNotEmpty()) {
                selectedDevice = host to port
                selectedDeviceLabel = getString(R.string.host_port, host, port)
                selectedDeviceName = host
                // Enter a provisional Connecting state to avoid transient Error/Idle display
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                updateDeviceBox()
                // Auto-run diagnostic for newly entered device
                runDiagnostic(host, port)
                lifecycleScope.launch { connectViaTcpFlow(host, port) }
            } else {
                Toast.makeText(requireContext(), getString(R.string.host_required), Toast.LENGTH_SHORT).show()
            }
        }

        btnConnect?.setOnClickListener { attemptConnectFromInline() }
        editPort?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptConnectFromInline(); true } else false
        }


        btnDisconnect?.setOnClickListener {
            lifecycleScope.launch {
                 settingsRepo.clearExternalTcp()
                 selectedDevice = null
                 selectedDeviceLabel = null
                 selectedDeviceName = null
                 updateDeviceBox() // will show options layout again
            }
        }

        // Prefill inline inputs with last used values
        lifecycleScope.launch {
            val lastHost = settingsRepo.externalTcpHost.first()
            val lastPort = settingsRepo.externalTcpPort.first()

            // Pre-populate with your GPS device if no previous settings
            if (lastHost.isNullOrBlank()) {
                editHost?.setText("192.168.2.174")
                settingsRepo.setExternalTcp("192.168.2.174", 9001)
            } else {
                editHost?.setText(lastHost)
            }

            if (lastPort != null) {
                editPort?.setText(lastPort.toString())
            } else {
                editPort?.setText("9001")
                settingsRepo.setExternalTcp(lastHost ?: "192.168.2.174", 9001)
            }
        }

        // Observe stored TCP host/port and auto-diagnose on new device
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepo.externalTcpHost.combine(settingsRepo.externalTcpPort) { h, p -> h to p }.collect { (host, port) ->
                // Only react to TCP host/port changes when External source is selected in the UI
                val isExternalSelected = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp
                if (!isExternalSelected) {
                    return@collect
                }
                if (host != null && port != null) {
                    val prev = selectedDevice
                    val isNew = prev == null || prev.first != host || prev.second != port
                    if (isNew) {
                        // Auto-run diagnostic for newly selected device (e.g., after user input or settings change)
                        runDiagnostic(host, port)
                      }
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
            var lastFixTimeMs: Long = 0L
            var lastUiStatus: ConnectionUiStatus = ConnectionUiStatus.DISCONNECTED

            fun currentExternalActive(): Boolean = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp

            fun deriveStatus(now: Long): ConnectionUiStatus {
                if (!currentExternalActive()) return ConnectionUiStatus.DISCONNECTED
                if (provisionalConnectedUntil > now && selectedDevice != null) return ConnectionUiStatus.CONNECTING
                if (lastFixTimeMs == 0L) return ConnectionUiStatus.CONNECTING // still waiting on first data
                val age = now - lastFixTimeMs
                return when {
                    age <= FRESH_FIX_MAX_AGE_MS -> ConnectionUiStatus.STREAMING
                    age <= STALE_FIX_MAX_AGE_MS -> ConnectionUiStatus.STALE
                    else -> ConnectionUiStatus.DISCONNECTED
                }
            }

            fun statusLine(status: ConnectionUiStatus): String = when (status) {
                ConnectionUiStatus.CONNECTING -> "Connecting…"
                ConnectionUiStatus.STREAMING -> "Connected"
                ConnectionUiStatus.STALE -> "Stale"
                ConnectionUiStatus.DISCONNECTED -> getString(R.string.disconnected)
            }

            fun applyColor(tv: TextView?, status: ConnectionUiStatus, external: Boolean) {
                val colorRes = if (!external) android.R.color.darker_gray else when (status) {
                    ConnectionUiStatus.CONNECTING -> android.R.color.holo_orange_dark
                    ConnectionUiStatus.STREAMING -> android.R.color.holo_green_dark
                    ConnectionUiStatus.STALE -> android.R.color.holo_orange_light
                    ConnectionUiStatus.DISCONNECTED -> android.R.color.darker_gray
                }
                tv?.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            }

            fun refresh(now: Long) {
                val newStatus = deriveStatus(now)
                if (newStatus != lastUiStatus) lastUiStatus = newStatus
                textDeviceStatus?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = statusLine(lastUiStatus)
                    applyColor(tv, lastUiStatus, currentExternalActive())
                }
                updateDeviceBox()
            }

            // Collect fixes to update recency
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    fixSwitchboard.fixes.collect { _ ->
                        lastFixTimeMs = System.currentTimeMillis()
                        provisionalConnectedUntil = 0L
                        refresh(lastFixTimeMs)
                    }
                }
            }
            // Periodic refresh to age out status
            while (isActive) {
                refresh(System.currentTimeMillis())
                delay(1_000L)
            }
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
            // Also reflect stored values in inline fields if present
            if (!storedHost.isNullOrBlank()) editHost?.setText(storedHost)
            if (storedPort != null) {
                editPort?.setText(storedPort.toString())
            } else {
                editPort?.setText(getString(R.string.default_port))
            }
            val source = settingsRepo.locationSource.first()
            val sel = if (source == LocationSourceType.EXTERNAL) R.id.radio_es2_tcp else R.id.radio_internal
            radioGroup?.check(sel)
            updateLocationSourceVisibility(source, internalGpsGroup)
            // Update categories based on current source
            refreshCategoriesForSource(source)
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
                    Log.d("SettingsFragment", "Radio switched to INTERNAL")
                    // Update switchboard provider to internal
                    sourceSettings.setActiveProvider(com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL)
                    Log.d("SettingsFragment", "Called setActiveProvider(INTERNAL)")
                    // Update categories immediately when switching to internal (hide RS2+)
                    refreshCategoriesForSource(LocationSourceType.INTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
                    rs2OptionsLayout?.visibility = View.GONE
                    // Reflect immediate disconnect from RS2+
                    selectedDevice = null
                    selectedDeviceLabel = null
                    selectedDeviceName = null
                    provisionalConnectedUntil = 0L
                    updateDeviceBox()
                    lifecycleScope.launch { settingsRepo.setLocationSource(LocationSourceType.INTERNAL) }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
                R.id.radio_es2_tcp -> {
                    Log.d("SettingsFragment", "Radio switched to RS2_EXTERNAL")
                    // Update switchboard provider to external
                    sourceSettings.setActiveProvider(com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.RS2_EXTERNAL)
                    Log.d("SettingsFragment", "Called setActiveProvider(RS2_EXTERNAL)")
                    // Update categories immediately when switching to external (show RS2+)
                    refreshCategoriesForSource(LocationSourceType.EXTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.EXTERNAL, internalGpsGroup)
                    // Show either the device box (if already selected) or options
                    updateDeviceBox()
                    lifecycleScope.launch {
                        settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                        settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                        // Try to reconnect if we have a saved TCP host/port from last time
                        val host = settingsRepo.externalTcpHost.first()
                        val port = settingsRepo.externalTcpPort.first()
                        if (!host.isNullOrBlank() && port != null) {
                            // Keep UI device selection in sync
                            selectedDevice = host to port
                            selectedDeviceLabel = getString(R.string.host_port, host, port)
                            selectedDeviceName = selectedDeviceName ?: host
                            updateDeviceBox()
                            connectViaTcpFlow(host, port)
                        }
                    }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
            }
        }
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
            Log.d("TCP", "Attempting to connect to $host:$port")
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
            }

            // More detailed connection error handling
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
                Log.d("TCP", "Successfully connected to $host:$port")
            } catch (e: java.net.ConnectException) {
                Log.e("TCP", "Connection refused to $host:$port - device may be offline or port closed", e)
                return@withContext false
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("TCP", "Connection timeout to $host:$port - device may be unreachable", e)
                return@withContext false
            } catch (e: java.net.UnknownHostException) {
                Log.e("TCP", "Unknown host $host - check IP address", e)
                return@withContext false
            } catch (e: java.net.NoRouteToHostException) {
                Log.e("TCP", "No route to host $host - check network connectivity", e)
                return@withContext false
            } catch (e: Exception) {
                Log.e("TCP", "Unexpected connection error to $host:$port", e)
                return@withContext false
            }

            runCatching { socket.soTimeout = 3000 }
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val start = System.currentTimeMillis()
            Log.d("TCP", "Reading data from $host:$port for up to 5 seconds")
            while (System.currentTimeMillis() - start < 5000L) {
                val line = withTimeoutOrNull(1000L) { runCatching { reader.readLine() }.getOrNull() }
                if (line == null) continue
                Log.d("TCP", "Received line: $line")
                if (line.startsWith("$")) {
                    gotData = true
                    Log.d("TCP", "NMEA sentence detected")
                    break
                }
            }
            if (!gotData) {
                Log.w("TCP", "No NMEA data received from $host:$port within timeout")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.no_nmea_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            // Consider connection successful even if no NMEA yet (device may stream RTCM or be slow to start)
            Log.d("TCP", "Connection test completed successfully for $host:$port")
            true
        } catch (e: Exception) {
            Log.e("TCP", "Unexpected error during TCP connection test to $host:$port", e)
            false
        } finally {
            runCatching { reader?.close() }
            runCatching { socket?.close() }
            Log.d("TCP", "Cleaned up connection resources for $host:$port")
        }
    }

    private fun connectViaTcpFlow(host: String, port: Int) {
        lifecycleScope.launch {
            Log.d("SettingsFragment", "connectViaTcpFlow: attempting connection to $host:$port")

            // Set provisional Connecting state immediately when attempting to connect
            provisionalConnectedUntil = System.currentTimeMillis() + 8000L
            view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.diag_testing).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
            }

            val success = connectAndReadTcpNmea(host, port)
            Log.d("SettingsFragment", "connectViaTcpFlow: connection result=$success")

            if (success) {
                // Ensure provider is switched to external in routing
                sourceSettings.setActiveProvider(com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.RS2_EXTERNAL)
                 // User may have switched to Internal while connection was in-flight; respect current selection
                val currentSource = settingsRepo.locationSource.first()
                Log.d("SettingsFragment", "connectViaTcpFlow: current source after connection=$currentSource")
                if (currentSource != LocationSourceType.EXTERNAL) {
                    Log.w("SettingsFragment", "connectViaTcpFlow: source switched to INTERNAL during connection, aborting")
                    return@launch
                }
                Toast.makeText(requireContext(), "TCP connection successful", Toast.LENGTH_SHORT).show()

                Log.d("SettingsFragment", "connectViaTcpFlow: setting external TCP connection")
                settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                settingsRepo.setExternalTcp(host, port)

                // Provisional connected state for up to 8s while LocationManager transitions from Idle
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                updateDeviceBox()
                view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = "Connected"
                    tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                }

                // The ReachDeviceRepository will automatically start polling device info
                // when the external source becomes active through the settings change below.
                // The device info will be available through the StateFlow observer we set up earlier.

                // Resolve friendly Reach name (fallback if device info fails)
                if (selectedDeviceName == null || selectedDeviceName == host) {
                    launch {
                        val resolved = ReachNameResolver.resolveReachName(requireContext(), host)
                        if (!resolved.isNullOrBlank() && isAdded) {
                            // Only use resolved name if we didn't get a better name from device info
                            if (currentDeviceInfo == null) {
                                selectedDeviceName = resolved
                                settingsRepo.setExternalTcp(host, port, resolved)
                                updateDeviceBox()
                            }
                        }
                    }
                }
            } else {
                // On failure, keep provider internal if no recent fix
                Log.w("SettingsFragment", "connectViaTcpFlow: connection to $host:$port failed")
                view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = "Connection failed"
                    tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                }
            }
        }
    }

    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
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
                    BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
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
        uiStatus: ConnectionUiStatus,
        fix: Fix?,
        isExternal: Boolean,
        deviceLabel: String?
    ): String {
        val sourcePart = if (isExternal) {
            val dev = deviceLabel ?: "(no device)"
            "RS2+ TCP $dev"
        } else "Internal GPS"
        val connPart = when (uiStatus) {
            ConnectionUiStatus.CONNECTING -> "Connecting"
            ConnectionUiStatus.STREAMING -> "Connected"
            ConnectionUiStatus.STALE -> "Stale"
            ConnectionUiStatus.DISCONNECTED -> if (isExternal) "Disconnected" else "Idle"
        }
        val fixPart = if (!isExternal) {
            if (fix != null) "Fused" else "Fused (pending)"
        } else {
            when (fix?.rtkStatus) {
                RtkStatus.FIX -> "Fixed RTK"
                RtkStatus.FLOAT -> "Float"
                RtkStatus.DGPS -> "DGPS"
                RtkStatus.SINGLE -> "Single"
                RtkStatus.DEAD_RECKONING -> "DR"
                RtkStatus.INVALID, RtkStatus.NONE -> "No Fix"
                null -> if (uiStatus == ConnectionUiStatus.STREAMING) "No Fix" else "--"
            }
        }
        val satsUsed = fix?.satsUsed
        val satsVis = fix?.satsVisible
        val satsPart = when {
            satsUsed != null && satsVis != null -> "${satsUsed}/${satsVis} sats"
            satsUsed != null -> "${satsUsed} sats"
            else -> "-- sats"
        }
        val dopPart = "DOP --"
        val basePart = if (isExternal) "Base --" else null
        return listOfNotNull(sourcePart, connPart, fixPart, satsPart, dopPart, basePart).joinToString(" • ")
    }

    private fun bytesToHexPreview(bytes: ByteArray, max: Int = 64): String {
        val sb = StringBuilder()
        val limit = min(bytes.size, max)
        for (i in 0 until limit) {
            sb.append(String.format("%02X", bytes[i]))
            if (i < limit - 1) sb.append(' ')
        }
        return sb.toString()
    }

    private fun testRs2Tcp(host: String, port: Int): Pair<TcpTestResult, ByteArray?> {
        var socket: Socket? = null
        return try {
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
            }
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 2000
            val inp = socket.getInputStream()
            val buffer = ByteArray(512)
            val baos = ByteArrayOutputStream()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2500) {
                val n = inp.read(buffer)
                if (n > 0) {
                    baos.write(buffer, 0, n)
                    if (baos.size() >= 64) break
                } else if (n == 0) {
                    // brief pause
                    Thread.sleep(20)
                }
            }
            val data = baos.toByteArray()
            if (data.isEmpty()) return TcpTestResult.CONNECTED_NO_DATA to null
            // Heuristics: NMEA if an ASCII '$' starts a line
            val ascii = runCatching { data.toString(Charsets.US_ASCII) }.getOrNull()
            if (ascii != null) {
                val hasNmea = ascii.lineSequence().any { it.startsWith("$") }
                if (hasNmea) return TcpTestResult.RECEIVING_NMEA to data
            }
            TcpTestResult.RECEIVING_RTCM_OR_BIN to data
        } catch (_: Exception) {
            TcpTestResult.CONNECT_FAILED to null
        } finally {
            runCatching { socket?.close() }
        }
    }


    // ───────────────────────────── Emlid Device Information Display ─────────────────────────────
    private fun showEmlidDeviceInfo(deviceInfo: EmlidDeviceInfo) {
        val message = buildString {
            append("Device Name: ${deviceInfo.deviceName}\n")
            append("IP Address: ${deviceInfo.ipAddress}\n")

            deviceInfo.model?.let { append("Model: $it\n") }
            deviceInfo.firmwareVersion?.let { append("Firmware: $it\n") }
            deviceInfo.serialNumber?.let { append("Serial: $it\n") }
            deviceInfo.uptime?.let { append("Uptime: $it\n") }
            deviceInfo.temperature?.let { append("Temperature: ${String.format("%.1f°C", it)}\n") }
            deviceInfo.batteryLevel?.let { append("Battery: ${String.format("%.1f%%", it)}\n") }

            if (deviceInfo.selfTests.isNotEmpty()) {
                append("\nSelf Tests:\n")
                deviceInfo.selfTests.forEach { test ->
                    val statusIcon = when (test.status) {
                        com.example.surveyingapp.domain.model.TestStatus.PASSED -> "✓"
                        com.example.surveyingapp.domain.model.TestStatus.FAILED -> "✗"
                        com.example.surveyingapp.domain.model.TestStatus.WARNING -> "⚠"
                        com.example.surveyingapp.domain.model.TestStatus.UNKNOWN -> "?"
                    }
                    append("  $statusIcon ${test.name}")
                    test.description?.let { desc -> append(" - $desc") }
                    append("\n")
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Emlid Device Information")
            .setMessage(message.trim())
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy Info") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Emlid Device Info", message.trim())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Device info copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ───────────────────────────── Device Information Update Functions ─────────────────────────────
    private fun updateDeviceInfoDisplay() {
        val root = currentContentView ?: return
        val deviceInfo = currentDeviceInfo ?: return

        val deviceInfoPanel = root.findViewById<LinearLayout>(R.id.device_info_panel)
        val textDeviceName = root.findViewById<TextView>(R.id.text_device_name)
        val textDeviceIp = root.findViewById<TextView>(R.id.text_device_ip)
        val layoutDeviceModel = root.findViewById<LinearLayout>(R.id.layout_device_model)
        val textDeviceModel = root.findViewById<TextView>(R.id.text_device_model)
        val layoutDeviceFirmware = root.findViewById<LinearLayout>(R.id.layout_device_firmware)
        val textDeviceFirmware = root.findViewById<TextView>(R.id.text_device_firmware)
        val layoutSelfTests = root.findViewById<LinearLayout>(R.id.layout_self_tests)
        val textSelfTestsSummary = root.findViewById<TextView>(R.id.text_self_tests_summary)
        val layoutSelfTestsGrid = root.findViewById<LinearLayout>(R.id.layout_self_tests_grid)

        // Show device info panel
        deviceInfoPanel?.visibility = View.VISIBLE

        // Populate basic device information - device name first, then IP
        textDeviceName?.text = deviceInfo.deviceName
        textDeviceIp?.text = deviceInfo.ipAddress

        // Show/hide and populate model if available
        if (deviceInfo.model != null) {
            layoutDeviceModel?.visibility = View.VISIBLE
            textDeviceModel?.text = deviceInfo.model
        } else {
            layoutDeviceModel?.visibility = View.GONE
        }

        // Show/hide and populate firmware if available
        if (deviceInfo.firmwareVersion != null) {
            layoutDeviceFirmware?.visibility = View.VISIBLE
            textDeviceFirmware?.text = deviceInfo.firmwareVersion
        } else {
            layoutDeviceFirmware?.visibility = View.GONE
        }

        // Show/hide and populate self tests if available
        if (deviceInfo.selfTests.isNotEmpty()) {
            layoutSelfTests?.visibility = View.VISIBLE

            // Update summary with counts
            val passedCount = deviceInfo.selfTests.count { it.status == com.example.surveyingapp.domain.model.TestStatus.PASSED }
            val failedCount = deviceInfo.selfTests.count { it.status == com.example.surveyingapp.domain.model.TestStatus.FAILED }
            val warningCount = deviceInfo.selfTests.count { it.status == com.example.surveyingapp.domain.model.TestStatus.WARNING }
            val totalCount = deviceInfo.selfTests.size

            val summaryText = "$passedCount/$totalCount passed"
            val summaryColor = when {
                failedCount > 0 -> android.R.color.holo_red_dark
                warningCount > 0 -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_green_dark
            }

            textSelfTestsSummary?.text = summaryText
            textSelfTestsSummary?.setTextColor(ContextCompat.getColor(requireContext(), summaryColor))

            // Clear existing test views and populate grid
            layoutSelfTestsGrid?.removeAllViews()
            populateSelfTestsGrid(layoutSelfTestsGrid, deviceInfo.selfTests)
        } else {
            layoutSelfTests?.visibility = View.GONE
        }

    }

    private fun populateSelfTestsGrid(container: LinearLayout?, tests: List<SelfTest>) {
        if (container == null || tests.isEmpty()) return

        val context = requireContext()
        val testsPerRow = 4 // Four columns layout

        // Group tests into rows
        val rows = tests.chunked(testsPerRow)

        rows.forEach { rowTests ->
            val rowLayout = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(4)
                }
                orientation = LinearLayout.HORIZONTAL
                weightSum = testsPerRow.toFloat()
            }

            rowTests.forEachIndexed { index, test ->
                val testItemLayout = createSelfTestItem(context, test)
                testItemLayout.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (index < rowTests.size - 1) {
                        rightMargin = dpToPx(4) // Reduced margin for tighter spacing with 4 columns
                    }
                }
                rowLayout.addView(testItemLayout)
            }

            // If row is not full, add empty space
            if (rowTests.size < testsPerRow) {
                val emptyView = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (testsPerRow - rowTests.size).toFloat()
                    )
                }
                rowLayout.addView(emptyView)
            }

            container.addView(rowLayout)
        }
    }

    private fun createSelfTestItem(context: Context, test: SelfTest): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)

            // Status icon
            val statusIcon = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dpToPx(6)
                }

                val (icon, color) = when (test.status) {
                    TestStatus.PASSED -> "✓" to android.R.color.holo_green_dark
                    TestStatus.FAILED -> "✗" to android.R.color.holo_red_dark
                    TestStatus.WARNING -> "⚠" to android.R.color.holo_orange_dark
                    TestStatus.UNKNOWN -> "?" to android.R.color.darker_gray
                }

                text = icon
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, color))
                gravity = android.view.Gravity.CENTER
                minWidth = dpToPx(20)
            }

            // Test name
            val testName = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = test.name
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, android.R.color.black))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            addView(statusIcon)
            addView(testName)

            // Add click listener to show test details if available
            if (!test.description.isNullOrBlank()) {
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(test.name)
                        .setMessage("Status: ${test.status.name}\n\n${test.description}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                // Add visual feedback for clickable items
                foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = requireContext().resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // ───────────────────────────── Model Conversion Helper ─────────────────────────────
    private fun convertToEmlidDeviceInfo(reachInfo: com.example.surveyingapp.domain.repository.ReachDeviceInfo): EmlidDeviceInfo {
        return EmlidDeviceInfo(
            deviceName = reachInfo.name ?: reachInfo.ip ?: "Unknown Device",
            ipAddress = reachInfo.ip ?: "Unknown IP",
            selfTests = emptyList(), // ReachDeviceInfo doesn't include self tests
            firmwareVersion = reachInfo.firmware,
            serialNumber = reachInfo.serial,
            model = reachInfo.model,
            uptime = reachInfo.uptimeSec?.let { "${it}s" }, // Convert seconds to string
            temperature = null, // Not available in ReachDeviceInfo
            batteryLevel = null // Battery info is handled separately in ReachBatteryInfo
        )
    }
}
