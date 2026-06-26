package com.example.surveyingapp.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.example.surveyingapp.BuildConfig
import com.example.surveyingapp.gnss.mock.AndroidMockLocationPublisher
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.DeveloperSettings
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.settings.GnssReceiverSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.EmlidDeviceInfo
import com.example.surveyingapp.domain.model.SelfTest
import com.example.surveyingapp.domain.model.TestStatus
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.settings.SettingsDefaults
import com.example.surveyingapp.gnss.external.repository.ReachDeviceRepository
import com.example.surveyingapp.gnss.external.ReachHttpClient
import com.example.surveyingapp.service.LocationService
import androidx.appcompat.app.AppCompatDelegate
import com.example.surveyingapp.settings.model.AppThemeMode
import com.example.surveyingapp.settings.model.AppearanceSettings
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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard
    @Inject
    lateinit var sourceSettings: com.example.surveyingapp.gnss.source.SourceSettings
    @Inject
    lateinit var gnssSourceCoordinator: com.example.surveyingapp.gnss.source.GnssSourceCoordinator
    @Inject
    lateinit var reachDeviceRepository: ReachDeviceRepository

    // Comment out the SettingsViewModel injection for now since it may not exist
    // @Inject
    // lateinit var settingsViewModel: SettingsViewModel

    // ─────────────────────────── Preferences / Data ───────────────────────────
    @Inject lateinit var repository: CoordinateRepository
    @Inject lateinit var settingsRepo: SettingsRepository

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
    private var tcpConnectJob: Job? = null
    private var tcpConnectAttemptId = 0
    /** True when the receiver's HTTP /info or /status endpoint responded successfully. */
    @Volatile private var receiverHttpReachable = false
    /** Epoch ms of last external fix accepted for status; 0 means none yet. */
    @Volatile private var lastExternalFixTimeMs: Long = 0L
    /** RTK status of the last accepted external fix. */
    @Volatile private var lastExternalFixRtkStatus: RtkStatus? = null
    /** Epoch ms of last raw NMEA sentence from the external adapter; 0 means none. */
    @Volatile private var lastExternalRawNmeaTimeMs: Long = 0L

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
    private enum class ConnectionUiStatus { CONNECTING, REACHABLE, STREAMING, STALE, DISCONNECTED }

    /** Clears all external-receiver transient state. Call when switching to internal or disconnecting. */
    private fun resetExternalReceiverStatus() {
        receiverHttpReachable = false
        provisionalConnectedUntil = 0L
        lastExternalFixTimeMs = 0L
        lastExternalFixRtkStatus = null
        lastExternalRawNmeaTimeMs = 0L
    }

    private fun updateDeviceBox() {
        try {
            val root = currentContentView ?: return
            val box = root.findViewById<LinearLayout>(R.id.container_selected_device)
            val statusTv = root.findViewById<TextView>(R.id.text_device_status)
            val optionsLayout = root.findViewById<LinearLayout>(R.id.layout_rs2_options)
            val radioGroup = root.findViewById<RadioGroup>(R.id.radio_location_source)
            val externalActive = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp
            val dev = selectedDevice

            // The External Receiver Setup card (host/port/profile + status) is only relevant for the
            // External GNSS source. Layout-only: mirrors the same externalActive gate already used
            // for its inner views, so the card hides entirely when Internal GPS is selected.
            root.findViewById<View>(R.id.card_external_receiver_setup)?.visibility =
                if (externalActive) View.VISIBLE else View.GONE

            if (!externalActive) {
                box?.visibility = View.GONE
                optionsLayout?.visibility = View.GONE
                return
            }
            if (dev == null) {
                box?.visibility = View.GONE
                optionsLayout?.visibility = View.VISIBLE
                statusTv?.visibility = View.GONE
                return
            }
            optionsLayout?.visibility = View.GONE
            box?.visibility = View.VISIBLE
            statusTv?.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("SettingsFragment", "updateDeviceBox failed", e)
        }
    }

    companion object {
        // Category IDs (internal)
        private const val CAT_ID_LOCATION           = 1
        private const val CAT_ID_GNSS_CAPTURE       = 2
        private const val CAT_ID_AR_DISPLAY         = 3
        private const val CAT_ID_COORDINATE_DISPLAY = 4
        private const val CAT_ID_DATA               = 5
private const val CAT_ID_DEV                = 7
        private const val CAT_ID_ABOUT              = 8
        private const val CAT_ID_APPEARANCE         = 9
        // Connection status timing thresholds (ms)
        const val FRESH_FIX_MAX_AGE_MS = 5_000L
        const val STALE_FIX_MAX_AGE_MS = 15_000L
    }

    // ────────────────��──────────── Lifecycle hooks ─────────────────────────────
    // repository is Hilt-injected (@Inject above); no manual init needed.

    override fun provideCategories(): List<SettingsCategory> = baseCategories()

    private fun baseCategories(): List<SettingsCategory> = listOf(
        SettingsCategory(CAT_ID_APPEARANCE,         "Appearance",              R.drawable.ic_appearance_24),
        SettingsCategory(CAT_ID_AR_DISPLAY,         "AR Display",              R.drawable.ic_section_ar),
        SettingsCategory(CAT_ID_GNSS_CAPTURE,       "Capture",                 R.drawable.ic_satellite_24),
        SettingsCategory(CAT_ID_COORDINATE_DISPLAY, "Coordinates",             R.drawable.ic_list_coordinates),
        SettingsCategory(CAT_ID_DATA,               "Data",                    R.drawable.ic_file),
        SettingsCategory(CAT_ID_DEV,                "Developer Tools",         R.drawable.ic_dev_tools),
SettingsCategory(CAT_ID_LOCATION,           "Receiver",                R.drawable.ic_section_location),
        SettingsCategory(CAT_ID_ABOUT,              "About",                   R.drawable.ic_section_info)
    )

    private fun refreshCategoriesForSource(@Suppress("UNUSED_PARAMETER") source: LocationSourceType) {
        // RS2+ page removed from Settings; categories are static
        updateCategoriesDynamic(baseCategories())
    }

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        try {
            when (category.id) {
                CAT_ID_LOCATION           -> setupLocationContent(inflater)
                CAT_ID_GNSS_CAPTURE       -> setupGnssCaptureContent(inflater)
                CAT_ID_AR_DISPLAY         -> setupARDisplayContent(inflater)
                CAT_ID_COORDINATE_DISPLAY -> setupCoordinateDisplayContent(inflater)
                CAT_ID_DATA               -> setupDataContent(inflater)
CAT_ID_DEV                -> setupDeveloperContent(inflater)
                CAT_ID_ABOUT              -> setupAboutContent(inflater)
                CAT_ID_APPEARANCE         -> setupAppearanceContent(inflater)
                else -> null
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "buildCategoryContent failed for category ${category.id}", e)
            null
        }


    // ───────────────────────────── Category builders ───────────────────────────

    private fun setupAppearanceContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_appearance, contentContainer, false)
        try {
            val spinner           = view.findViewById<AutoCompleteTextView>(R.id.spinner_theme_mode)
            val switchGnssBar     = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_gnss_status_bar)
            val switchKeepAwake   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_keep_screen_awake)
            val switchMaxBright   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_max_brightness)

            val options = listOf(
                "System Default" to AppThemeMode.SYSTEM,
                "Light"          to AppThemeMode.LIGHT,
                "Dark"           to AppThemeMode.DARK
            )

            spinner?.setAdapter(
                ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, options.map { it.first })
            )

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val current = settingsRepo.appearanceSettings.first()
                    val label = options.firstOrNull { it.second == current.themeMode }?.first ?: options.first().first
                    spinner?.setText(label, false)
                    switchGnssBar?.isChecked   = current.showLiveGnssStatusBar
                    switchKeepAwake?.isChecked = current.keepScreenAwake
                    switchMaxBright?.isChecked = current.maxBrightnessWhileOpen
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Failed to load appearance settings", e)
                }
            }

            spinner?.setOnItemClickListener { _, _, position, _ ->
                val selected = options.getOrNull(position) ?: return@setOnItemClickListener
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(themeMode = selected.second))
                        AppCompatDelegate.setDefaultNightMode(
                            when (selected.second) {
                                AppThemeMode.LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
                                AppThemeMode.DARK   -> AppCompatDelegate.MODE_NIGHT_YES
                                AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save appearance settings", e)
                        showSettingsMessage("Failed to save theme.")
                    }
                }
            }

            switchGnssBar?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(showLiveGnssStatusBar = isChecked))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save GNSS status bar setting", e)
                    }
                }
            }

            switchKeepAwake?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(keepScreenAwake = isChecked))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save keep screen awake setting", e)
                    }
                }
            }

            switchMaxBright?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(maxBrightnessWhileOpen = isChecked))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save max brightness setting", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupAppearanceContent failed", e)
        }
        return view
    }

    private fun setupLocationContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_location, contentContainer, false)
        try { setupLocationSourceUi(view) } catch (e: Exception) { Log.e("SettingsFragment", "setupLocationSourceUi failed", e) }
        return view
    }

    private fun setupDataContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_data, contentContainer, false)
        try {
            view.findViewById<Button>(R.id.btn_export_coordinates)?.setOnClickListener { try { showExportFormatDialog() } catch (e: Exception) { Log.e("SettingsFragment", "showExportFormatDialog failed", e) } }
            view.findViewById<Button>(R.id.btn_import_coordinates)?.setOnClickListener { try { showImportFormatDialog() } catch (e: Exception) { Log.e("SettingsFragment", "showImportFormatDialog failed", e) } }
            view.findViewById<Button>(R.id.btn_cancel_import)?.setOnClickListener { try { cancelActiveImport() } catch (e: Exception) { Log.e("SettingsFragment", "cancelActiveImport failed", e) } }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupDataContent wiring failed", e)
        }
        return view
    }

    private fun setupDeveloperContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_developer, contentContainer, false)
        try {
            val switchDevTools = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_dev_tools)
            if (switchDevTools != null) {
                lifecycleScope.launch {
                    try {
                        switchDevTools.isChecked = settingsRepo.developerSettings.first().developerToolsEnabled
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "dev tools initial load error", e)
                    }
                }
                switchDevTools.setOnCheckedChangeListener { _, v ->
                    try {
                        lifecycleScope.launch {
                            try {
                                settingsRepo.setDeveloperSettings(DeveloperSettings(developerToolsEnabled = v))
                            } catch (e: Exception) {
                                Log.e("SettingsFragment", "dev tools save error", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "dev tools switch error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupDeveloperContent wiring failed", e)
        }
        return view
    }

    private fun setupARDisplayContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_ar_display, contentContainer, false)
        try {
            val spinnerAltitude = view.findViewById<AutoCompleteTextView>(R.id.spinner_altitude_mode)
            val spinnerFilter   = view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter)
            val switchLabels     = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ar_show_labels)
            val switchArrows     = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ar_show_offscreen_arrows)
            val switchDebug      = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ar_debug_overlay)
            val switchDebugTools = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ar_debug_tools)
            val editScale        = view.findViewById<EditText>(R.id.edit_ar_model_scale)
            view.findViewById<Button>(R.id.btn_save_ar_display)?.visibility = View.GONE

            val altitudeModes   = listOf("Stored Altitude" to "STORED", "Terrain Altitude" to "TERRAIN")
            val distanceOptions = listOf("All distances" to -1, "50 m" to 0, "100 m" to 1, "500 m" to 2)
            val tilScale = editScale?.parent?.parent as? com.google.android.material.textfield.TextInputLayout

            spinnerAltitude?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, altitudeModes.map { it.first }))
            spinnerFilter?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, distanceOptions.map { it.first }))

            fun saveAll(altitudeMode: String? = null, distanceIdx: Int? = null) {
                val scale = editScale?.text?.toString()?.toFloatOrNull()
                if (scale == null || scale !in 0.1f..10.0f) {
                    tilScale?.error = "Must be 0.1 – 10.0"
                    return
                }
                tilScale?.error = null
                val settings = ArDisplaySettings(
                    altitudeMode        = altitudeMode ?: (altitudeModes.firstOrNull { it.first == spinnerAltitude?.text?.toString() }?.second ?: "STORED"),
                    distanceFilterIndex = distanceIdx ?: distanceOptions.indexOfFirst { it.first == spinnerFilter?.text?.toString() }.coerceAtLeast(0),
                    showDebugOverlay    = switchDebug?.isChecked ?: false,
                    showLabels          = switchLabels?.isChecked ?: true,
                    showOffscreenArrows = switchArrows?.isChecked ?: true,
                    modelScale          = scale,
                    showArDebugTools    = switchDebugTools?.isChecked ?: false
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        settingsRepo.setArDisplaySettings(settings)
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save AR display settings", e)
                    }
                }
            }

            var isBinding = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val s = settingsRepo.arDisplaySettings.first()
                    isBinding = true
                    spinnerAltitude?.setText(altitudeModes.getOrElse(altitudeModes.indexOfFirst { it.second == s.altitudeMode }.coerceAtLeast(0)) { altitudeModes.first() }.first, false)
                    spinnerFilter?.setText(distanceOptions.getOrElse(s.distanceFilterIndex.coerceIn(0, distanceOptions.lastIndex)) { distanceOptions.first() }.first, false)
                    switchLabels?.isChecked = s.showLabels
                    switchArrows?.isChecked = s.showOffscreenArrows
                    switchDebug?.isChecked      = s.showDebugOverlay
                    switchDebugTools?.isChecked = s.showArDebugTools
                    editScale?.setText(s.modelScale.toString())
                    isBinding = false
                } catch (e: Exception) {
                    isBinding = false
                    Log.e("SettingsFragment", "Failed to load AR display settings", e)
                }
            }

            spinnerAltitude?.setOnItemClickListener { _, _, pos, _ ->
                saveAll(altitudeMode = altitudeModes.getOrNull(pos)?.second)
            }
            spinnerFilter?.setOnItemClickListener { _, _, pos, _ ->
                saveAll(distanceIdx = pos)
            }
            switchLabels?.setOnCheckedChangeListener  { _, _ -> if (!isBinding) saveAll() }
            switchArrows?.setOnCheckedChangeListener  { _, _ -> if (!isBinding) saveAll() }
            switchDebug?.setOnCheckedChangeListener      { _, _ -> if (!isBinding) saveAll() }
            switchDebugTools?.setOnCheckedChangeListener { _, _ -> if (!isBinding) saveAll() }
            editScale?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAll() }
            editScale?.setOnEditorActionListener { _, _, _ -> saveAll(); false }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupARDisplayContent failed", e)
        }
        return view
    }

    private fun setupCoordinateDisplayContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_coordinate_display, contentContainer, false)
        try {
            val switchAccuracy  = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_show_accuracy_indicators)
            val editPrefix      = view.findViewById<EditText>(R.id.edit_coordinate_name_prefix)
            val switchAutoInc   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_auto_increment_names)


            val tilPrefix = editPrefix?.parent?.parent as? com.google.android.material.textfield.TextInputLayout

            fun savePrefix() {
                val prefix = editPrefix?.text?.toString()?.trim().orEmpty()
                if (prefix.length > 20) {
                    tilPrefix?.error = "Max 20 characters"
                    return
                }
                tilPrefix?.error = null
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val s = settingsRepo.coordinateDisplaySettings.first()
                        settingsRepo.setCoordinateDisplaySettings(s.copy(
                            defaultNamePrefix = prefix.ifEmpty { "Point" }
                        ))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save coordinate name prefix", e)
                    }
                }
            }

            fun saveSwitch(accuracy: Boolean, autoInc: Boolean) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val s = settingsRepo.coordinateDisplaySettings.first()
                        settingsRepo.setCoordinateDisplaySettings(s.copy(
                            showAccuracyIndicators = accuracy,
                            autoIncrementNames     = autoInc
                        ))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save coordinate display settings", e)
                    }
                }
            }

            var isBinding = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val s = settingsRepo.coordinateDisplaySettings.first()
                    isBinding = true
                    switchAccuracy?.isChecked = s.showAccuracyIndicators
                    editPrefix?.setText(s.defaultNamePrefix)
                    switchAutoInc?.isChecked  = s.autoIncrementNames
                    isBinding = false
                } catch (e: Exception) {
                    isBinding = false
                    Log.e("SettingsFragment", "Failed to load coordinate display settings", e)
                }
            }

            switchAccuracy?.setOnCheckedChangeListener { _, v ->
                if (!isBinding) saveSwitch(v, switchAutoInc?.isChecked ?: true)
            }
            switchAutoInc?.setOnCheckedChangeListener { _, v ->
                if (!isBinding) saveSwitch(switchAccuracy?.isChecked ?: true, v)
            }
            editPrefix?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) savePrefix() }
            editPrefix?.setOnEditorActionListener { _, _, _ -> savePrefix(); false }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupCoordinateDisplayContent failed", e)
        }
        return view
    }

    private fun setupAboutContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_about, contentContainer, false)
        try {
            val hash  = BuildConfig.BUILD_GIT_HASH
            val dirty = BuildConfig.BUILD_GIT_DIRTY

            view.findViewById<TextView>(R.id.text_about_version)?.text =
                BuildConfig.VERSION_NAME
            view.findViewById<TextView>(R.id.text_about_build_number)?.text =
                BuildConfig.BUILD_NUMBER.toString()
            view.findViewById<TextView>(R.id.text_about_commit)?.text =
                if (dirty) "$hash-dirty" else hash
            view.findViewById<TextView>(R.id.text_about_branch)?.text =
                BuildConfig.BUILD_GIT_BRANCH
            view.findViewById<TextView>(R.id.text_about_dirty)?.text =
                if (dirty) "Yes" else "No"
            view.findViewById<TextView>(R.id.text_about_built)?.text =
                BuildConfig.BUILD_TIME

            view.findViewById<View>(R.id.btn_copy_build_info)?.setOnClickListener {
                val info = buildString {
                    appendLine("App version: ${BuildConfig.VERSION_NAME}")
                    appendLine("Build: ${BuildConfig.BUILD_NUMBER}")
                    appendLine("Commit: $hash${if (dirty) "-dirty" else ""}")
                    appendLine("Branch: ${BuildConfig.BUILD_GIT_BRANCH}")
                    appendLine("Dirty: $dirty")
                    append("Built: ${BuildConfig.BUILD_TIME}")
                }
                val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Build Info", info))
                showSettingsMessage("Build info copied.")
            }

            val btnExport = view.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btn_export_diagnostic
            )
            btnExport?.setOnClickListener {
                btnExport.isEnabled = false
                btnExport.text = "Exporting…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val file = try {
                        com.example.surveyingapp.util.DiagnosticReportExporter.buildReport(requireContext())
                    } catch (ex: Exception) {
                        com.example.surveyingapp.util.DiagnosticsLogger.e("DiagnosticExport", "Export error", ex)
                        null
                    }
                    btnExport.isEnabled = true
                    btnExport.text = "Export Diagnostic Report"
                    if (file == null) {
                        showSettingsMessage("Could not create diagnostic report.")
                        return@launch
                    }
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "SurReal AR Diagnostic Report")
                            putExtra(Intent.EXTRA_TEXT, "Diagnostic report attached.")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share diagnostic report"))
                        showSettingsMessage("Diagnostic report ready.")
                    } catch (ex: Exception) {
                        com.example.surveyingapp.util.DiagnosticsLogger.e("DiagnosticExport", "Share failed", ex)
                        showSettingsMessage("Could not create diagnostic report.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupAboutContent failed", e)
        }
        return view
    }

    // ───────────────────────────── GNSS Capture Settings ──────────────────────
    private fun setupGnssCaptureContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_gnss_capture, contentContainer, false)
        try {
            val spinner     = view.findViewById<AutoCompleteTextView>(R.id.spinner_rtk_status)
            val editMinDur  = view.findViewById<EditText>(R.id.edit_min_duration)
            val editMaxDur  = view.findViewById<EditText>(R.id.edit_max_duration)
            val editSamples = view.findViewById<EditText>(R.id.edit_min_samples)
            val editFixAge  = view.findViewById<EditText>(R.id.edit_max_fix_age)
            val editDiffAge = view.findViewById<EditText>(R.id.edit_max_diff_age)


            val tilMinDur  = editMinDur?.parent?.parent  as? com.google.android.material.textfield.TextInputLayout
            val tilMaxDur  = editMaxDur?.parent?.parent  as? com.google.android.material.textfield.TextInputLayout
            val tilSamples = editSamples?.parent?.parent as? com.google.android.material.textfield.TextInputLayout
            val tilFixAge  = editFixAge?.parent?.parent  as? com.google.android.material.textfield.TextInputLayout
            val tilDiffAge = editDiffAge?.parent?.parent as? com.google.android.material.textfield.TextInputLayout

            val rtkOptions = listOf(
                "RTK FIX only"         to RtkStatus.FIX,
                "RTK FLOAT or better"  to RtkStatus.FLOAT,
                "DGPS or better"       to RtkStatus.DGPS,
                "Single GPS or better" to RtkStatus.SINGLE
            )
            spinner?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, rtkOptions.map { it.first }))

            fun trySave() {
                val minDur  = editMinDur?.text?.toString()?.toIntOrNull()
                val maxDur  = editMaxDur?.text?.toString()?.toIntOrNull()
                val samples = editSamples?.text?.toString()?.toIntOrNull()
                val fixAge  = editFixAge?.text?.toString()?.toIntOrNull()
                val diffAge = editDiffAge?.text?.toString()?.toIntOrNull()

                tilMinDur?.error  = if (minDur == null || minDur !in 1..600)
                    "Minimum Sampling Time must be between 1 and 600 seconds." else null
                tilMaxDur?.error  = if (maxDur == null || maxDur !in 1..600)
                    "Maximum Sampling Time must be between 1 and 600 seconds."
                    else if (minDur != null && maxDur < minDur)
                        "Maximum Sampling Time must be greater than or equal to Minimum Sampling Time." else null
                tilSamples?.error = if (samples == null || samples < 1)
                    "Minimum Accepted Fixes must be at least 1." else null
                tilFixAge?.error  = if (fixAge == null || fixAge < 1)
                    "Max Fix Age must be at least 1 second." else null
                tilDiffAge?.error = if (diffAge == null || diffAge < 1)
                    "Max Correction Age must be at least 1 second." else null

                if (minDur == null || minDur !in 1..600) return
                if (maxDur == null || maxDur !in 1..600 || maxDur < minDur) return
                if (samples == null || samples < 1) return
                if (fixAge == null || fixAge < 1) return
                if (diffAge == null || diffAge < 1) return

                val settings = GnssCaptureSettings(
                    requiredMinStatus = rtkOptions.firstOrNull { it.first == spinner?.text?.toString() }?.second ?: RtkStatus.FIX,
                    minDurationSec    = minDur,
                    maxDurationSec    = maxDur,
                    minSamples        = samples,
                    maxFixAgeSec      = fixAge,
                    maxDiffAgeSec     = diffAge
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        settingsRepo.setGnssCaptureSettings(settings)
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Failed to save GNSS capture settings", e)
                    }
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val saved = settingsRepo.gnssCaptureSettings.first()
                    spinner?.setText(rtkOptions.getOrElse(rtkOptions.indexOfFirst { it.second == saved.requiredMinStatus }.coerceAtLeast(0)) { rtkOptions.first() }.first, false)
                    editMinDur?.setText(saved.minDurationSec.toString())
                    editMaxDur?.setText(saved.maxDurationSec.toString())
                    editSamples?.setText(saved.minSamples.toString())
                    editFixAge?.setText(saved.maxFixAgeSec.toString())
                    editDiffAge?.setText(saved.maxDiffAgeSec.toString())
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Failed to load GNSS capture settings", e)
                }
            }

            spinner?.setOnItemClickListener { _, _, _, _ -> trySave() }
            listOf(editMinDur, editMaxDur, editSamples, editFixAge, editDiffAge).forEach { edit ->
                edit?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) trySave() }
                edit?.setOnEditorActionListener { _, _, _ -> trySave(); false }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "setupGnssCaptureContent failed", e)
        }
        return view
    }

    // ───────────────────────────── Location Source UI ─────────────────────────
    private fun setupLocationSourceUi(view: View) {
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_location_source)
        val internalGpsGroup = view.findViewById<LinearLayout>(R.id.group_internal_gps)
        val switchHighAccuracy = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_high_accuracy)
        val rs2OptionsLayout = view.findViewById<LinearLayout>(R.id.layout_rs2_options)
        val editHost = view.findViewById<EditText>(R.id.edit_host)
        val editPort = view.findViewById<EditText>(R.id.edit_port)

        // ── External receiver profile selector ──────────────────────────────────────
        // All profiles use the SAME External TCP NMEA pipeline; the profile only carries display
        // labels + sensible defaults. Uses the same Material ExposedDropdownMenu component as the
        // capture/appearance dropdowns (defined in the layout). Selecting a profile persists it and
        // applies its default port.
        run {
            val profiles = com.example.surveyingapp.settings.model.ExternalReceiverProfile.entries
            val profileDropdown = view.findViewById<AutoCompleteTextView>(R.id.dropdown_receiver_profile)
            val profileHint = view.findViewById<TextView>(R.id.text_receiver_profile_hint)

            profileDropdown?.setAdapter(
                ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, profiles.map { it.label })
            )

            fun applyHint(profile: com.example.surveyingapp.settings.model.ExternalReceiverProfile) {
                val baseHelp = "Choose the receiver model so the app can use the right labels, " +
                    "defaults, and diagnostics. Position data still comes from NMEA over TCP."
                profileHint?.text = profile.hint?.let { "$baseHelp\n\n$it" } ?: baseHelp
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val current = runCatching { settingsRepo.externalReceiverProfile.first() }
                    .getOrDefault(com.example.surveyingapp.settings.model.ExternalReceiverProfile.DEFAULT)
                profileDropdown?.setText(current.label, false)
                applyHint(current)
            }

            profileDropdown?.setOnItemClickListener { _, _, position, _ ->
                val profile = profiles.getOrNull(position) ?: return@setOnItemClickListener
                applyHint(profile)
                // Persist + apply default port only on a genuine change.
                viewLifecycleOwner.lifecycleScope.launch {
                    val persisted = runCatching { settingsRepo.externalReceiverProfile.first() }
                        .getOrDefault(com.example.surveyingapp.settings.model.ExternalReceiverProfile.DEFAULT)
                    if (persisted != profile) {
                        runCatching { settingsRepo.setExternalReceiverProfile(profile) }
                        // Conservative: only apply the new profile's default port if the current
                        // port is missing/invalid or wasn't user-customized (a custom port is kept).
                        val currentPort = editPort?.text?.toString()?.trim()?.toIntOrNull()
                        editPort?.setText(
                            com.example.surveyingapp.settings.model.ExternalReceiverProfile
                                .portForProfileChange(currentPort, profile).toString()
                        )
                    }
                }
            }
        }

        val btnConnect = view.findViewById<Button>(R.id.btn_connect)
        val btnDisconnect = view.findViewById<Button>(R.id.btn_disconnect_device)
        val textDeviceStatus = view.findViewById<TextView>(R.id.text_device_status)
        val tvDataStream     = view.findViewById<TextView>(R.id.text_status_data_stream)
        val tvPositionFix    = view.findViewById<TextView>(R.id.text_status_position_fix)
        val tvSatellites     = view.findViewById<TextView>(R.id.text_status_satellites)
        val tvCorrections    = view.findViewById<TextView>(R.id.text_status_corrections)
        val tvBattery        = view.findViewById<TextView>(R.id.text_status_battery)
        val tvStatusSub      = view.findViewById<TextView>(R.id.text_device_status_sub)
        val chipSource       = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_source)
        val chipFix          = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_fix)
        val chipSats         = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_sats)
        val chipDataStream   = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_data_stream)
        val chipBattery      = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_battery)
        val defaultChipBg    = chipSource?.chipBackgroundColor
        val defaultChipTextColor = chipFix?.textColors

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
                showSettingsMessage("Device info not available. Ensure external connection is active.")
            }
        }

        // Observe device info from repository
        viewLifecycleOwner.lifecycleScope.launch {
            reachDeviceRepository.deviceInfo.collect { reachInfo ->
                try {
                    if (reachInfo != null) {
                        currentDeviceInfo = convertToEmlidDeviceInfo(reachInfo)
                        updateDeviceInfoDisplay()
                        if (selectedDeviceName == selectedDevice?.first || selectedDeviceName.isNullOrBlank()) {
                            selectedDeviceName = reachInfo.name ?: selectedDevice?.first
                            selectedDevice?.let { (host, port) ->
                                settingsRepo.setExternalTcp(host, port, selectedDeviceName ?: "Unknown Device")
                            }
                            updateDeviceBox()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "deviceInfo collect error", e)
                }
            }
        }

        var lastDiagnosed: Pair<String, Int>? = null

        fun runDiagnostic(host: String, port: Int) {
            val pair = host to port
            if (host.isBlank()) return
            if (lastDiagnosed == pair) return
            lastDiagnosed = pair
            lifecycleScope.launch {
                try {
                    val (result, _) = withContext(Dispatchers.IO) { testRs2Tcp(host, port) }
                    if (selectedDeviceName == null || selectedDeviceName == host) {
                        launch {
                            try {
                                val resolved = ReachNameResolver.resolveReachName(requireContext(), host)
                                if (!resolved.isNullOrBlank() && isAdded && currentDeviceInfo == null) {
                                    selectedDeviceName = resolved
                                    settingsRepo.setExternalTcp(host, port, resolved)
                                    updateDeviceBox()
                                }
                            } catch (e: Exception) {
                                Log.w("SettingsFragment", "resolveReachName failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SettingsFragment", "runDiagnostic failed for $host:$port", e)
                }
            }
        }

        fun attemptConnectFromInline() {
            try {
                val focusedView = activity?.currentFocus
                if (focusedView != null) {
                    val imm = requireContext().getSystemService(InputMethodManager::class.java)
                    imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
                    focusedView.clearFocus()
                }
                val host = editHost?.text?.toString()?.trim().orEmpty()
                val port = SettingsDefaults.sanitizeTcpPort(editPort?.text?.toString()?.trim()?.toIntOrNull())
                if (host.isNotEmpty()) {
                    selectedDevice = host to port
                    selectedDeviceLabel = getString(R.string.host_port, host, port)
                    selectedDeviceName = host
                    provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                    updateDeviceBox()
                    runDiagnostic(host, port)
                    connectViaTcpFlow(host, port)
                } else {
                    showSettingsMessage(getString(R.string.host_required))
                }
            } catch (e: Exception) {
                Log.e("SettingsFragment", "attemptConnectFromInline failed", e)
            }
        }

        btnConnect?.setOnClickListener { attemptConnectFromInline() }
        editPort?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptConnectFromInline(); true } else false
        }

        btnDisconnect?.setOnClickListener {
            // Cancel any in-flight connect attempt before touching settings.
            ++tcpConnectAttemptId
            tcpConnectJob?.cancel()
            tcpConnectJob = null
            resetExternalReceiverStatus()
            // Switch back to internal in the switchboard immediately (synchronous).
            gnssSourceCoordinator.activateInternalProvider("settings")
            // Clear selected device so the UI shows the TCP entry form again.
            selectedDevice = null
            selectedDeviceLabel = null
            selectedDeviceName = null
            currentDeviceInfo = null
            // Update radio + visibility to Internal.
            radioGroup?.check(R.id.radio_internal)
            updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
            updateDeviceBox()
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_publish_mock_location)
                ?.let { it.isChecked = false; it.isEnabled = false }
            lifecycleScope.launch {
                try {
                    settingsRepo.setMockLocationEnabled(false)
                    settingsRepo.setLocationSource(LocationSourceType.INTERNAL)
                    settingsRepo.clearExternalTcp()
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "disconnect failed", e)
                }
            }
        }

        // Prefill inline inputs with last used values. The host is left blank on first use (no
        // hardcoded device address), and the port defaults to 9000 (the most common Reach/RS2+ TCP
        // port). Nothing is persisted here — settings are only saved when the user connects.
        lifecycleScope.launch {
            val lastHost = settingsRepo.externalTcpHost.first()
            val lastPort = settingsRepo.externalTcpPort.first()

            editHost?.setText(lastHost.orEmpty())
            editPort?.setText(lastPort?.toString() ?: SettingsDefaults.externalTcpPort.toString())
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
            // lastExternalFixTimeMs, lastExternalFixRtkStatus, lastExternalRawNmeaTimeMs are
            // fragment-scope fields so resetExternalReceiverStatus() can clear them from any path.
            var lastUiStatus: ConnectionUiStatus = ConnectionUiStatus.DISCONNECTED

            fun currentExternalActive(): Boolean = radioGroup?.checkedRadioButtonId == R.id.radio_es2_tcp

            fun deriveStatus(now: Long): ConnectionUiStatus {
                if (!currentExternalActive()) return ConnectionUiStatus.DISCONNECTED
                if (provisionalConnectedUntil > now && selectedDevice != null) return ConnectionUiStatus.CONNECTING
                val age = if (lastExternalFixTimeMs > 0L) now - lastExternalFixTimeMs else Long.MAX_VALUE
                return when {
                    age <= FRESH_FIX_MAX_AGE_MS -> ConnectionUiStatus.STREAMING
                    age <= STALE_FIX_MAX_AGE_MS -> ConnectionUiStatus.STALE
                    receiverHttpReachable -> ConnectionUiStatus.REACHABLE
                    lastExternalFixTimeMs == 0L -> ConnectionUiStatus.CONNECTING
                    else -> ConnectionUiStatus.DISCONNECTED
                }
            }

            fun statusLines(status: ConnectionUiStatus): Pair<String, String> = when (status) {
                ConnectionUiStatus.CONNECTING   -> "Connecting…" to ""
                ConnectionUiStatus.REACHABLE    -> "Receiver reachable" to "Waiting for GNSS data"
                ConnectionUiStatus.STREAMING    -> "Receiver connected" to when (lastExternalFixRtkStatus?.qualityRank() ?: 0) {
                    0    -> "No position fix yet"
                    else -> "GNSS fix active"
                }
                ConnectionUiStatus.STALE        -> "Receiver reachable" to "GNSS data stale"
                ConnectionUiStatus.DISCONNECTED -> "Receiver unavailable" to "Check IP or Wi-Fi"
            }

            fun applyColor(tv: TextView?, status: ConnectionUiStatus, external: Boolean) {
                val colorRes = if (!external) android.R.color.darker_gray else when (status) {
                    ConnectionUiStatus.CONNECTING -> android.R.color.holo_orange_dark
                    ConnectionUiStatus.REACHABLE -> android.R.color.holo_orange_light
                    ConnectionUiStatus.STREAMING -> android.R.color.holo_green_dark
                    ConnectionUiStatus.STALE -> android.R.color.holo_orange_light
                    ConnectionUiStatus.DISCONNECTED -> android.R.color.darker_gray
                }
                tv?.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            }

            fun refresh(now: Long) {
                val newStatus = deriveStatus(now)
                if (newStatus != lastUiStatus) lastUiStatus = newStatus
                val extActive = currentExternalActive()
                val (mainStatus, subStatus) = statusLines(lastUiStatus)
                textDeviceStatus?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = mainStatus
                    applyColor(tv, lastUiStatus, extActive)
                }
                tvStatusSub?.let { tv ->
                    if (subStatus.isNotEmpty() && extActive) {
                        tv.text = subStatus
                        tv.visibility = View.VISIBLE
                    } else {
                        tv.visibility = View.GONE
                    }
                }

                if (!extActive) {
                    tvDataStream?.text  = "—"
                    tvPositionFix?.text = "—"
                    tvSatellites?.text  = "—"
                    tvCorrections?.text = "—"
                    tvBattery?.text     = "—"
                    listOf(chipFix, chipSats, chipDataStream, chipBattery).forEach { chip ->
                        chip?.text = "—"
                        defaultChipBg?.let { chip?.chipBackgroundColor = it }
                        defaultChipTextColor?.let { chip?.setTextColor(it) }
                    }
                    updateDeviceBox()
                    return
                }

                val ctx = context ?: run { updateDeviceBox(); return }
                fun clr(res: Int) = ContextCompat.getColor(ctx, res)
                fun chipClr(res: Int) = android.content.res.ColorStateList.valueOf(clr(res))

                val nmeaAge = if (lastExternalRawNmeaTimeMs > 0L) now - lastExternalRawNmeaTimeMs else Long.MAX_VALUE
                val fixAge  = if (lastExternalFixTimeMs > 0L)     now - lastExternalFixTimeMs     else Long.MAX_VALUE
                val sky = fixSwitchboard.sky.value
                val bat = reachDeviceRepository.batteryInfo.value

                // Data Stream row + chip
                tvDataStream?.let { tv ->
                    when {
                        nmeaAge <= 8_000L -> {
                            tv.text = "Receiving NMEA"
                            tv.setTextColor(clr(android.R.color.holo_green_dark))
                        }
                        lastExternalRawNmeaTimeMs > 0L && nmeaAge <= 30_000L -> {
                            tv.text = "Stale (${nmeaAge / 1000}s ago)"
                            tv.setTextColor(clr(android.R.color.holo_orange_light))
                        }
                        lastExternalRawNmeaTimeMs == 0L -> {
                            tv.text = "Waiting…"
                            tv.setTextColor(clr(android.R.color.darker_gray))
                        }
                        else -> {
                            tv.text = "No data"
                            tv.setTextColor(clr(android.R.color.holo_red_dark))
                        }
                    }
                }
                chipDataStream?.let { chip ->
                    when {
                        nmeaAge <= 8_000L -> { chip.text = "NMEA"; chip.chipBackgroundColor = chipClr(R.color.app_success); chip.setTextColor(android.graphics.Color.WHITE) }
                        lastExternalRawNmeaTimeMs > 0L && nmeaAge <= 30_000L -> { chip.text = "Stale"; chip.chipBackgroundColor = chipClr(R.color.app_warning); chip.setTextColor(android.graphics.Color.WHITE) }
                        lastExternalRawNmeaTimeMs == 0L -> { chip.text = "Waiting"; defaultChipBg?.let { chip.chipBackgroundColor = it }; defaultChipTextColor?.let { chip.setTextColor(it) } }
                        else -> { chip.text = "No Data"; chip.chipBackgroundColor = chipClr(R.color.app_error); chip.setTextColor(android.graphics.Color.WHITE) }
                    }
                }

                // Position Fix row + chip
                tvPositionFix?.let { tv ->
                    when {
                        fixAge <= FRESH_FIX_MAX_AGE_MS -> {
                            val (label, colorRes) = when (lastExternalFixRtkStatus) {
                                RtkStatus.FIX            -> "RTK Fixed"       to android.R.color.holo_green_dark
                                RtkStatus.FLOAT          -> "RTK Float"       to android.R.color.holo_orange_light
                                RtkStatus.DGPS           -> "DGPS"            to android.R.color.holo_orange_light
                                RtkStatus.SINGLE         -> "Single"          to android.R.color.holo_orange_dark
                                RtkStatus.DEAD_RECKONING -> "Dead Reckoning"  to android.R.color.darker_gray
                                else                     -> "No fix"          to android.R.color.darker_gray
                            }
                            tv.text = label
                            tv.setTextColor(clr(colorRes))
                        }
                        fixAge <= STALE_FIX_MAX_AGE_MS -> {
                            tv.text = "Stale fix"
                            tv.setTextColor(clr(android.R.color.holo_orange_light))
                        }
                        lastExternalFixTimeMs == 0L -> {
                            tv.text = "No fix yet"
                            tv.setTextColor(clr(android.R.color.darker_gray))
                        }
                        else -> {
                            tv.text = "No fix"
                            tv.setTextColor(clr(android.R.color.darker_gray))
                        }
                    }
                }
                chipFix?.let { chip ->
                    when {
                        fixAge <= FRESH_FIX_MAX_AGE_MS -> {
                            val (label, colorRes) = when (lastExternalFixRtkStatus) {
                                RtkStatus.FIX            -> "Fixed"  to R.color.app_success
                                RtkStatus.FLOAT          -> "Float"  to R.color.app_warning
                                RtkStatus.DGPS           -> "DGPS"   to R.color.app_info
                                RtkStatus.SINGLE         -> "Single" to R.color.app_warning
                                RtkStatus.DEAD_RECKONING -> "DR"     to R.color.app_warning
                                else                     -> "No Fix" to R.color.app_error
                            }
                            chip.text = label; chip.chipBackgroundColor = chipClr(colorRes); chip.setTextColor(android.graphics.Color.WHITE)
                        }
                        fixAge <= STALE_FIX_MAX_AGE_MS -> { chip.text = "Stale"; chip.chipBackgroundColor = chipClr(R.color.app_warning); chip.setTextColor(android.graphics.Color.WHITE) }
                        else -> { chip.text = "—"; defaultChipBg?.let { chip.chipBackgroundColor = it }; defaultChipTextColor?.let { chip.setTextColor(it) } }
                    }
                }

                // Satellites row + chip
                tvSatellites?.let { tv ->
                    when {
                        sky.totalVisible > 0 -> {
                            tv.text = "${sky.totalUsed} used / ${sky.totalVisible} visible"
                            tv.setTextColor(clr(android.R.color.darker_gray))
                        }
                        nmeaAge <= 8_000L -> {
                            tv.text = "No satellites in view"
                            tv.setTextColor(clr(android.R.color.holo_orange_light))
                        }
                        else -> {
                            tv.text = "—"
                            tv.setTextColor(clr(android.R.color.darker_gray))
                        }
                    }
                }
                chipSats?.let { chip ->
                    when {
                        sky.totalVisible > 0 -> { chip.text = "${sky.totalUsed}/${sky.totalVisible} Sats"; chip.chipBackgroundColor = chipClr(R.color.app_success); chip.setTextColor(android.graphics.Color.WHITE) }
                        nmeaAge <= 8_000L    -> { chip.text = "0 Sats"; chip.chipBackgroundColor = chipClr(R.color.app_warning); chip.setTextColor(android.graphics.Color.WHITE) }
                        else                 -> { chip.text = "—"; defaultChipBg?.let { chip.chipBackgroundColor = it }; defaultChipTextColor?.let { chip.setTextColor(it) } }
                    }
                }

                // Corrections row (unchanged)
                tvCorrections?.let { tv ->
                    val info = reachDeviceRepository.correctionsInfo.value
                    if (info == null) {
                        tv.text = "—"
                        tv.setTextColor(clr(android.R.color.darker_gray))
                    } else if (info.isReceiving) {
                        val ch = info.channel ?: "Connected"
                        val age = info.ageSeconds?.let { " (${it.toInt()}s)" } ?: ""
                        tv.text = "$ch$age"
                        tv.setTextColor(clr(android.R.color.holo_green_dark))
                    } else {
                        tv.text = "Not connected"
                        tv.setTextColor(clr(android.R.color.darker_gray))
                    }
                }

                // Battery row + chip
                tvBattery?.let { tv ->
                    if (bat == null) {
                        tv.text = "—"
                        tv.setTextColor(clr(android.R.color.darker_gray))
                    } else {
                        val chargeSuffix = bat.chargerStatus?.let { s ->
                            if (s.lowercase().let { it.contains("charging") && !it.contains("not") }) " ⚡" else ""
                        } ?: ""
                        tv.text = "${bat.percent}%$chargeSuffix"
                        tv.setTextColor(clr(when {
                            bat.percent < 20 -> android.R.color.holo_red_dark
                            bat.percent < 40 -> android.R.color.holo_orange_light
                            else             -> android.R.color.darker_gray
                        }))
                    }
                }
                chipBattery?.let { chip ->
                    if (bat == null) {
                        chip.text = "—"; defaultChipBg?.let { chip.chipBackgroundColor = it }; defaultChipTextColor?.let { chip.setTextColor(it) }
                    } else {
                        val chargeSuffix = bat.chargerStatus?.let { s ->
                            if (s.lowercase().let { it.contains("charging") && !it.contains("not") }) "⚡" else ""
                        } ?: ""
                        chip.text = "${bat.percent}%$chargeSuffix"
                        chip.chipBackgroundColor = chipClr(when {
                            bat.percent < 20 -> R.color.app_error
                            bat.percent < 40 -> R.color.app_warning
                            else             -> R.color.app_success
                        })
                        chip.setTextColor(android.graphics.Color.WHITE)
                    }
                }

                updateDeviceBox()
            }

            // Collect external fixes only — internal GPS fixes must never affect external status.
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        fixSwitchboard.fixes.collect { fix ->
                            if (fix.provider == Provider.INTERNAL) return@collect
                            if (!currentExternalActive()) return@collect
                            lastExternalFixTimeMs = System.currentTimeMillis()
                            lastExternalFixRtkStatus = fix.rtkStatus
                            provisionalConnectedUntil = 0L
                            try { refresh(lastExternalFixTimeMs) } catch (e: Exception) { Log.w("SettingsFragment", "status refresh error", e) }
                        }
                    } catch (e: Exception) {
                        Log.w("SettingsFragment", "fix collect error in status job", e)
                    }
                }
            }

            // Track raw NMEA activity (external adapter only) to distinguish "no data" from "no fix".
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    fixSwitchboard.rawNmea.collect {
                        if (currentExternalActive()) lastExternalRawNmeaTimeMs = System.currentTimeMillis()
                    }
                }
            }
            // Periodic refresh to age out status
            while (isActive) {
                try { refresh(System.currentTimeMillis()) } catch (e: Exception) { Log.w("SettingsFragment", "periodic refresh error", e) }
                delay(1_000L)
            }
        }
        textDeviceStatus?.text = ""


        switchHighAccuracy?.apply {
            lifecycleScope.launch {
                try {
                    isChecked = settingsRepo.gnssReceiverSettings.first().highAccuracy
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "high accuracy initial load error", e)
                }
            }
            setOnCheckedChangeListener { _, v ->
                lifecycleScope.launch {
                    try {
                        val current = settingsRepo.gnssReceiverSettings.first()
                        settingsRepo.setGnssReceiverSettings(current.copy(highAccuracy = v))
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "high accuracy save error", e)
                    }
                }
            }
        }

        // ── Advanced: mock location publishing ──
        val switchMock   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_publish_mock_location)
        val errorBanner  = view.findViewById<LinearLayout>(R.id.layout_mock_location_error)
        val btnDevOpts   = view.findViewById<Button>(R.id.btn_open_developer_options)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                switchMock?.isChecked = settingsRepo.mockLocationEnabled.first()
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Failed to load mock location setting", e)
            }
        }

        switchMock?.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    settingsRepo.setMockLocationEnabled(checked)
                    if (!checked) errorBanner?.visibility = View.GONE
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Failed to save mock location setting", e)
                }
            }
        }

        btnDevOpts?.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (ex: Exception) {
                    Log.w("SettingsFragment", "Could not open developer settings", ex)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    var lastShownError: AndroidMockLocationPublisher.MockLocationError? = null
                    SurveyingApp.mockLocationPublisher.errorEvents.collect { error ->
                        val msg = when (error) {
                            AndroidMockLocationPublisher.MockLocationError.NOT_PERMITTED ->
                                "Mock location is not enabled for this app. Open Developer Options and select this app as the mock location app."
                            AndroidMockLocationPublisher.MockLocationError.PROVIDER_ERROR ->
                                "Mock location provider error. See Logcat for details."
                        }
                        errorBanner?.visibility = View.VISIBLE
                        view.findViewById<TextView>(R.id.text_mock_location_error)?.text = msg
                        if (error != lastShownError) {
                            lastShownError = error
                            showSettingsMessage(msg, Snackbar.LENGTH_LONG)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "mock location error observer failed", e)
                }
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
            // Mock switch is only usable when external source is active
            switchMock?.isEnabled = source == LocationSourceType.EXTERNAL
            // Update categories based on current source
            refreshCategoriesForSource(source)
            // Now that radio is set, update device box with possibly restored device
            updateDeviceBox()
            // Restore device info panel immediately if the repository has cached data.
            // The collect observer may fire before currentContentView is set, causing
            // updateDeviceInfoDisplay() to return early — so we re-check here explicitly.
            if (currentDeviceInfo == null) {
                reachDeviceRepository.deviceInfo.value?.let { cached ->
                    currentDeviceInfo = convertToEmlidDeviceInfo(cached)
                }
            }
            if (currentDeviceInfo != null) {
                updateDeviceInfoDisplay()
            }
            // Sync runtime provider with persisted source. The radio listener is skipped
            // while initializing=true, so we must do this explicitly. For EXTERNAL the
            // active provider stays INTERNAL until the receiver is confirmed reachable
            // (connectViaTcpFlow sets it inside the receiverPresent block).
            if (source != LocationSourceType.EXTERNAL) {
                gnssSourceCoordinator.activateInternalProvider("settings")
            }
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
                    com.example.surveyingapp.util.DiagnosticsLogger.i(
                        "GNSS", "Switched back to Internal — cancelling external connect attempt #$tcpConnectAttemptId"
                    )
                    // Invalidate and cancel any in-progress TCP connection attempt
                    ++tcpConnectAttemptId
                    tcpConnectJob?.cancel()
                    tcpConnectJob = null
                    resetExternalReceiverStatus()
                    // Update switchboard provider to internal
                    gnssSourceCoordinator.activateInternalProvider("settings")
                    Log.d("SettingsFragment", "Called activateInternalProvider(INTERNAL)")
                    // Update categories immediately when switching to internal (hide RS2+)
                    refreshCategoriesForSource(LocationSourceType.INTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
                    rs2OptionsLayout?.visibility = View.GONE
                    provisionalConnectedUntil = 0L
                    updateDeviceBox()
                    // Immediately disable mock publishing and update its switch UI
                    switchMock?.isChecked = false
                    switchMock?.isEnabled = false
                    lifecycleScope.launch {
                        settingsRepo.setMockLocationEnabled(false)
                        settingsRepo.setLocationSource(LocationSourceType.INTERNAL)
                    }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
                R.id.radio_es2_tcp -> {
                    Log.d("SettingsFragment", "Radio switched to EXTERNAL_TCP")
                    // Re-enable mock switch (user must still turn it on explicitly)
                    switchMock?.isEnabled = true
                    // Update categories immediately when switching to external (show RS2+)
                    refreshCategoriesForSource(LocationSourceType.EXTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.EXTERNAL, internalGpsGroup)
                    // Show either the device box (if already selected) or options
                    updateDeviceBox()
                    // Persist source selection asynchronously — does not need to complete
                    // before the connect attempt starts.
                    lifecycleScope.launch {
                        settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                        settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                    }
                    // Reconnect using the device already loaded from stored settings.
                    // Calling connectViaTcpFlow directly (not inside a separate launch) ensures
                    // the attempt ID is incremented synchronously so radio_internal can cancel it.
                    val dev = selectedDevice
                    if (dev != null) {
                        selectedDeviceName = selectedDeviceName ?: dev.first
                        updateDeviceBox()
                        connectViaTcpFlow(dev.first, dev.second)
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
    private suspend fun connectAndReadTcpNmea(host: String, port: Int): TcpTestResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        try {
            socket = Socket().apply { tcpNoDelay = true; keepAlive = true }
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
            } catch (e: Exception) {
                Log.d("TCP", "Connect failed to $host:$port: ${e.javaClass.simpleName}")
                return@withContext TcpTestResult.CONNECT_FAILED
            }

            runCatching { socket.soTimeout = 3000 }
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + 5000L
            var sawNonNmea = false
            while (System.currentTimeMillis() < deadline) {
                val line = withTimeoutOrNull(1000L) { runCatching { reader.readLine() }.getOrNull() }
                    ?: continue
                if (line.startsWith("$")) return@withContext TcpTestResult.RECEIVING_NMEA
                if (line.isNotEmpty()) sawNonNmea = true
            }
            if (sawNonNmea) TcpTestResult.RECEIVING_RTCM_OR_BIN else TcpTestResult.CONNECTED_NO_DATA
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("TCP", "TCP probe error for $host:$port: ${e.javaClass.simpleName}")
            TcpTestResult.CONNECT_FAILED
        } finally {
            runCatching { reader?.close() }
            runCatching { socket?.close() }
        }
    }

    /**
     * Checks whether the receiver's HTTP API is reachable. A 200-range response on /info or
     * /status is sufficient — the receiver is present even if no GNSS fix is available yet.
     */
    private suspend fun probeHttpReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        val client = ReachHttpClient(host, connectTimeoutMs = 3000, readTimeoutMs = 3000)
        try { client.get("/info"); true }
        catch (_: Exception) {
            try { client.get("/status"); true }
            catch (_: Exception) { false }
        }
    }

    private fun connectViaTcpFlow(host: String, port: Int) {
        val attemptId = ++tcpConnectAttemptId
        tcpConnectJob?.cancel()
        tcpConnectJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.text = "Connecting…"
                    tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
                }
                view?.findViewById<TextView>(R.id.text_device_status_sub)?.visibility = View.GONE

                // Run TCP/NMEA and HTTP probes in parallel.
                val (tcpResult, httpReachable) = coroutineScope {
                    val tcp  = async { connectAndReadTcpNmea(host, port) }
                    val http = async { probeHttpReachable(host) }
                    tcp.await() to http.await()
                }

                // Stale-attempt guards — drop results from superseded or invalidated attempts.
                if (attemptId != tcpConnectAttemptId) return@launch
                if (!isAdded || view == null) return@launch
                if (settingsRepo.locationSource.first() != LocationSourceType.EXTERNAL) return@launch

                receiverHttpReachable = httpReachable

                val tcpConnected = tcpResult != TcpTestResult.CONNECT_FAILED
                val receiverPresent = tcpConnected || httpReachable

                if (receiverPresent) {
                    // Persist connection details BEFORE activating the external provider. The
                    // external adapter reads host/port from SettingsRepository the moment the
                    // switchboard rebinds to it, so activating first could let it come up against
                    // stale or blank TCP settings. Order: conn type → host/port → selected source
                    // → activate provider.
                    settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                    settingsRepo.setExternalTcp(host, port)
                    settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                    com.example.surveyingapp.util.DiagnosticsLogger.i(
                        "GNSS", "External TCP settings saved host=$host port=$port (attempt #$attemptId)"
                    )

                    // Final stale-attempt guard before flipping the live provider. If the user
                    // switched back to Internal while the probe/persist was in flight, the attempt
                    // id has advanced — abort so this old attempt can't activate External after the
                    // user already returned to Internal.
                    if (attemptId != tcpConnectAttemptId) {
                        com.example.surveyingapp.util.DiagnosticsLogger.i(
                            "GNSS", "Stale external connect attempt #$attemptId ignored before activation"
                        )
                        return@launch
                    }

                    // Activate via the coordinator (the single entry point for source actions).
                    // Persistence above already completed, so ordering is preserved.
                    gnssSourceCoordinator.activateExternalTcpProvider(host, port, reason = "settings-connect #$attemptId")
                    provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                    updateDeviceBox()

                    val (statusText, statusSubText, statusColor) = when (tcpResult) {
                        TcpTestResult.RECEIVING_NMEA ->
                            Triple("Receiver connected", "Receiving NMEA", android.R.color.holo_green_dark)
                        TcpTestResult.RECEIVING_RTCM_OR_BIN ->
                            Triple("Receiver connected", "Stream is not NMEA", android.R.color.holo_orange_dark)
                        TcpTestResult.CONNECTED_NO_DATA ->
                            Triple("Receiver reachable", "Waiting for GNSS data", android.R.color.holo_orange_light)
                        TcpTestResult.CONNECT_FAILED ->
                            // TCP failed but HTTP succeeded — receiver is present but NMEA port unavailable.
                            Triple("Receiver reachable", "NMEA stream unavailable", android.R.color.holo_orange_light)
                    }
                    view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                        tv.visibility = View.VISIBLE
                        tv.text = statusText
                        tv.setTextColor(ContextCompat.getColor(requireContext(), statusColor))
                    }
                    view?.findViewById<TextView>(R.id.text_device_status_sub)?.let { tv ->
                        tv.text = statusSubText
                        tv.visibility = View.VISIBLE
                    }

                    if (selectedDeviceName == null || selectedDeviceName == host) {
                        launch {
                            try {
                                val resolved = ReachNameResolver.resolveReachName(requireContext(), host)
                                if (attemptId != tcpConnectAttemptId) return@launch
                                if (!resolved.isNullOrBlank() && isAdded && currentDeviceInfo == null) {
                                    selectedDeviceName = resolved
                                    settingsRepo.setExternalTcp(host, port, resolved)
                                    updateDeviceBox()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("SettingsFragment", "resolveReachName failed", e)
                            }
                        }
                    }
                } else {
                    // Both TCP and HTTP failed — receiver unreachable.
                    view?.findViewById<TextView>(R.id.text_device_status)?.let { tv ->
                        tv.visibility = View.VISIBLE
                        tv.text = "Receiver unavailable"
                        tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                    }
                    view?.findViewById<TextView>(R.id.text_device_status_sub)?.let { tv ->
                        tv.text = "Check IP or Wi-Fi"
                        tv.visibility = View.VISIBLE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attemptId == tcpConnectAttemptId) {
                    Log.e("SettingsFragment", "connectViaTcpFlow[$attemptId] error", e)
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
        try {
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
                    lifecycleScope.launch { try { prepareImportCoordinatesWithConfirmation(uri, isCsv = false) } catch (e: Exception) { Log.e("SettingsFragment", "import_json failed", e) } }
                }
                currentOperation == "import_csv" -> {
                    pendingImportIsCsv = true
                    lifecycleScope.launch { try { prepareImportCoordinatesWithConfirmation(uri, isCsv = true) } catch (e: Exception) { Log.e("SettingsFragment", "import_csv failed", e) } }
                }
                currentOperation == "import_data" -> {
                    val fileName = try { getFileNameFromUri(uri) } catch (_: Exception) { null } ?: ""
                    val isCsvFile = fileName.lowercase().endsWith(".csv")
                    pendingImportIsCsv = isCsvFile
                    lifecycleScope.launch { try { prepareImportCoordinatesWithConfirmation(uri, isCsv = isCsvFile) } catch (e: Exception) { Log.e("SettingsFragment", "import_data failed", e) } }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "handleCustomFilePickerResult failed", e)
        } finally {
            currentOperation = null
        }
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
                    showSettingsMessage("JSON exported successfully: $fileName", Snackbar.LENGTH_LONG)
                }
            }.onFailure { e ->
                showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
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
                    showSettingsMessage("CSV exported successfully: $fileName", Snackbar.LENGTH_LONG)
                }
            }.onFailure { e ->
                showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
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
            showSettingsMessage("Imported $count ${if (isCsv) "CSV" else "JSON"} (${if (replaced) "replaced" else "merged"})", Snackbar.LENGTH_LONG)
        }.onFailure { e ->
            if (e is CancellationException) {
                showImportProgress(false, 0, "Canceled")
            } else {
                showImportProgress(false, 0, "Error")
                showSettingsMessage("Import failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
        coordinatesImportJob = null
    }

    private fun parseJsonCoordinates(raw: String): List<Coordinate> {
        val arr = org.json.JSONArray(raw)
        val list = mutableListOf<Coordinate>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            try {
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
            } catch (e: Exception) {
                Log.w("SettingsFragment", "Skipping malformed JSON object at index $i", e)
            }
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
            try {
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
            } catch (e: Exception) {
                Log.w("SettingsFragment", "Skipping malformed CSV line: $line", e)
            }
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
        try {
            currentContentView?.findViewById<LinearLayout>(R.id.import_progress_container)?.let { c ->
                c.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) {
                    c.findViewById<ProgressBar>(R.id.progress_import)?.progress = percent.coerceIn(0, 100)
                    c.findViewById<TextView>(R.id.text_import_progress)?.text = status
                }
            }
        } catch (e: Exception) {
            Log.w("SettingsFragment", "showImportProgress failed", e)
        }
    }

    private fun showSettingsMessage(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        val v = view ?: return
        Snackbar.make(v, message, duration).show()
    }

    private fun cancelActiveImport() {
        try {
            coordinatesImportJob?.takeIf { it.isActive }?.cancel()?.also {
                showSettingsMessage(getString(R.string.import_cancel))
            }
        } catch (e: Exception) {
            Log.w("SettingsFragment", "cancelActiveImport failed", e)
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
            ConnectionUiStatus.CONNECTING    -> "Connecting"
            ConnectionUiStatus.REACHABLE     -> "Reachable"
            ConnectionUiStatus.STREAMING     -> "Connected"
            ConnectionUiStatus.STALE         -> "Stale"
            ConnectionUiStatus.DISCONNECTED  -> if (isExternal) "Disconnected" else "Idle"
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
                showSettingsMessage("Device info copied to clipboard")
            }
            .show()
    }

    // ───────────────────────────── Device Information Update Functions ─────────────────────────────
    private fun updateDeviceInfoDisplay() {
        try {
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

            deviceInfoPanel?.visibility = View.VISIBLE
            textDeviceName?.text = deviceInfo.deviceName
            val port = selectedDevice?.second
            textDeviceIp?.text = if (port != null) "${deviceInfo.ipAddress}:$port" else deviceInfo.ipAddress

            if (deviceInfo.model != null) {
                layoutDeviceModel?.visibility = View.VISIBLE
                textDeviceModel?.text = deviceInfo.model
            } else {
                layoutDeviceModel?.visibility = View.GONE
            }

            if (deviceInfo.firmwareVersion != null) {
                layoutDeviceFirmware?.visibility = View.VISIBLE
                textDeviceFirmware?.text = deviceInfo.firmwareVersion
            } else {
                layoutDeviceFirmware?.visibility = View.GONE
            }

            if (deviceInfo.selfTests.isNotEmpty()) {
                layoutSelfTests?.visibility = View.VISIBLE
                // Summary text + color decisions live in SelfTestDisplay (pure + unit-tested).
                val summaryColor = SelfTestDisplay.summaryColorRes(deviceInfo.selfTests)
                textSelfTestsSummary?.text = SelfTestDisplay.summaryText(deviceInfo.selfTests)
                try { textSelfTestsSummary?.setTextColor(ContextCompat.getColor(requireContext(), summaryColor)) } catch (_: Exception) {}
                layoutSelfTestsGrid?.removeAllViews()
                try { populateSelfTestsGrid(layoutSelfTestsGrid, deviceInfo.selfTests) } catch (e: Exception) { Log.w("SettingsFragment", "populateSelfTestsGrid failed", e) }
            } else {
                layoutSelfTests?.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "updateDeviceInfoDisplay failed", e)
        }

    }

    private fun populateSelfTestsGrid(container: LinearLayout?, tests: List<SelfTest>) {
        if (container == null || tests.isEmpty()) return
        try {
            val context = requireContext()
            val testsPerRow = 4
            val rows = tests.chunked(testsPerRow)
            rows.forEach { rowTests ->
                try {
                    val rowLayout = LinearLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(4) }
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = testsPerRow.toFloat()
                    }
                    rowTests.forEachIndexed { index, test ->
                        try {
                            val testItemLayout = createSelfTestItem(context, test)
                            testItemLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (index < rowTests.size - 1) rightMargin = dpToPx(4)
                            }
                            rowLayout.addView(testItemLayout)
                        } catch (e: Exception) {
                            Log.w("SettingsFragment", "createSelfTestItem failed for ${test.name}", e)
                        }
                    }
                    if (rowTests.size < testsPerRow) {
                        rowLayout.addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, (testsPerRow - rowTests.size).toFloat())
                        })
                    }
                    container.addView(rowLayout)
                } catch (e: Exception) {
                    Log.w("SettingsFragment", "populateSelfTestsGrid row failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "populateSelfTestsGrid failed", e)
        }
    }

    private fun createSelfTestItem(context: Context, test: SelfTest): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            try { background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background) } catch (_: Exception) {}
            val statusIcon = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpToPx(6) }
                val icon = SelfTestDisplay.statusIcon(test.status)
                val color = SelfTestDisplay.statusColorRes(test.status)
                text = icon
                textSize = 14f
                try { setTextColor(ContextCompat.getColor(context, color)) } catch (_: Exception) {}
                gravity = android.view.Gravity.CENTER
                minWidth = dpToPx(20)
            }
            val testName = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = test.name
                textSize = 11f
                try { setTextColor(ContextCompat.getColor(context, android.R.color.black)) } catch (_: Exception) {}
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(statusIcon)
            addView(testName)
            if (!test.description.isNullOrBlank()) {
                setOnClickListener {
                    try {
                        AlertDialog.Builder(context)
                            .setTitle(test.name)
                            .setMessage("Status: ${test.status.name}\n\n${test.description}")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: Exception) {
                        Log.w("SettingsFragment", "self test detail dialog failed", e)
                    }
                }
                try { foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background) } catch (_: Exception) {}
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = requireContext().resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // ───────────────────────────── Model Conversion Helper ─────────────────────────────
    private fun convertToEmlidDeviceInfo(reachInfo: com.example.surveyingapp.gnss.external.model.ReachDeviceInfo): EmlidDeviceInfo {
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
