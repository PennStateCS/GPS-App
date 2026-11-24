package app.surrealar.ui.settings

import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase.PartialReplaceDecision
import app.surrealar.util.BuildInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import app.surrealar.BuildConfig
import app.surrealar.gnss.mock.AndroidMockLocationPublisher
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.DeveloperSettings
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import app.surrealar.gnss.settings.GnssReceiverSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import app.surrealar.R
import app.surrealar.SurRealApplication
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.model.Coordinate
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.EmlidDeviceInfo
import app.surrealar.domain.model.SelfTest
import app.surrealar.domain.model.TestStatus
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.settings.SettingsDefaults
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.ReceiverHttpProbe
import app.surrealar.service.LocationService
import androidx.appcompat.app.AppCompatDelegate
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.settings.model.AppearanceSettings
import app.surrealar.ui.common.BaseTwoPaneFragment
import app.surrealar.util.ReachNameResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
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

/**
 * Settings screen host: presents the settings categories and edits values through the DataStore-backed
 * settings repositories. Holds no settings state of its own and writes nothing to Room.
 */
@AndroidEntryPoint
class SettingsFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard
    @Inject
    lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings
    @Inject
    lateinit var gnssSourceCoordinator: app.surrealar.gnss.source.GnssSourceCoordinator
    @Inject
    lateinit var receiverDevices: ReceiverDeviceManager

    // Comment out the SettingsViewModel injection for now since it may not exist
    // @Inject
    // lateinit var settingsViewModel: SettingsViewModel

    // ─────────────────────────── Preferences / Data ───────────────────────────
    @Inject lateinit var repository: CoordinateRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var modelRepository: app.surrealar.domain.repository.ModelRepository
    @Inject lateinit var exportBackup: app.surrealar.domain.usecase.ExportCoordinateBackupUseCase
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    @Inject lateinit var importBackup: app.surrealar.domain.usecase.ImportCoordinateBackupUseCase

    /**
     * Scope for user-triggered settings WRITES (Main dispatcher). It outlives this screen, so a save
     * fired by focus loss as the user leaves Settings still completes; `categoryScope` and the view
     * scopes are cancelled at that moment. Loads and view binding stay on `categoryScope`; UI touched
     * from a save must check that the view still exists.
     */
    private val saveScope: CoroutineScope
        get() = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope

    // Selected device for TCP connection (class scope)
    private var selectedDevice: Pair<String, Int>? = null
    private var selectedDeviceLabel: String? = null
    private var selectedDeviceName: String? = null
    private var autoReconnectAttempted = false // new flag
    private var provisionalConnectedUntil: Long = 0L // provisional connected status timeout

    // Emlid device information
    private var currentDeviceInfo: EmlidDeviceInfo? = null

    // Coordinate backup import/export state
    private var pendingImportUri: Uri? = null
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

    // Coordinate backup export: system "create document" save dialog. A null uri means the
    // user cancelled — do nothing (no error).
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackupTo(uri) }

    // Survey points CSV export (configured coordinate system). A null uri means cancelled.
    private val exportPointsCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) writePointsCsvTo(uri) }

    // Coordinate backup import: system "open document" picker. A null uri means cancelled.
    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportDocumentPicked(uri) }

    private enum class TcpTestResult { CONNECT_FAILED, CONNECTED_NO_DATA, RECEIVING_NMEA, RECEIVING_RTCM_OR_BIN }

    // UI status for external connection
    private enum class ConnectionUiStatus { CONNECTING, REACHABLE, STREAMING, STALE, DISCONNECTED }

    /** Clears all external-receiver transient state. Call when switching to internal or disconnecting. */
    /** Nearest TextInputLayout wrapping [field] (TextInputEditText sits inside a FrameLayout in it). */
    private fun inputLayoutOf(field: View): com.google.android.material.textfield.TextInputLayout? {
        var p = field.parent
        while (p != null) {
            if (p is com.google.android.material.textfield.TextInputLayout) return p
            p = p.parent
        }
        return null
    }

    private fun showAntennaHeightError(field: View) {
        val msg = "Enter a height in metres (0 – ${AntennaHeightInput.MAX_M.toInt()}). Previous value kept."
        val layout = inputLayoutOf(field)
        if (layout != null) layout.error = msg
        else android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun clearAntennaHeightError(field: View) {
        inputLayoutOf(field)?.error = null
    }

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
            settingsError("Settings receiver status update failed", e)
        }
    }

    /**
     * Logs a Settings failure at E with its exception. A cancelled coroutine (category switched, screen
     * closed) is rethrown instead of logged, so callers can use it in any catch / onFailure.
     */
    private fun settingsError(message: String, e: Throwable, tag: String = DiagTags.SETTINGS) {
        if (e is CancellationException) throw e
        DiagnosticsLogger.e(tag, message, e)
    }

    /** [settingsError] at W, for handled problems (a UI refresh skipped, a best-effort lookup failed). */
    private fun settingsWarn(message: String, e: Throwable, tag: String = DiagTags.SETTINGS) {
        if (e is CancellationException) throw e
        DiagnosticsLogger.w(tag, message, e)
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
        private const val CAT_ID_MAP                = 10
        /** Nav argument: id of the category to open first (e.g. [CATEGORY_RECEIVER] from the toolbar). */
        const val ARG_INITIAL_CATEGORY_ID = "initialCategoryId"
        /** The Receiver category (receiver status, battery, device info). */
        const val CATEGORY_RECEIVER = CAT_ID_LOCATION
        // Connection status timing thresholds (ms)
        const val FRESH_FIX_MAX_AGE_MS = 5_000L
        const val STALE_FIX_MAX_AGE_MS = 15_000L
    }

    // ────────────────��──────────── Lifecycle hooks ─────────────────────────────
    // repository is Hilt-injected (@Inject above); no manual init needed.

    override fun provideCategories(): List<SettingsCategory> = baseCategories()

    /** Opens [ARG_INITIAL_CATEGORY_ID] when given (the toolbar's battery token opens Receiver). */
    override fun initialCategoryIndex(): Int {
        val id = arguments?.getInt(ARG_INITIAL_CATEGORY_ID, -1) ?: -1
        return provideCategories().indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: super.initialCategoryIndex()
    }

    // Categories are listed alphabetically by display name (case-insensitive), with About pinned last.
    private fun baseCategories(): List<SettingsCategory> = listOf(
        SettingsCategory(CAT_ID_APPEARANCE,         "Appearance",              R.drawable.ic_appearance_24),
        SettingsCategory(CAT_ID_AR_DISPLAY,         "AR Display",              R.drawable.ic_section_ar),
        SettingsCategory(CAT_ID_GNSS_CAPTURE,       "Capture",                 R.drawable.ic_satellite_24),
        SettingsCategory(CAT_ID_COORDINATE_DISPLAY, "Coordinates",             R.drawable.ic_list_coordinates),
        SettingsCategory(CAT_ID_DATA,               "Data",                    R.drawable.ic_file),
        SettingsCategory(CAT_ID_DEV,                "Developer Tools",         R.drawable.ic_dev_tools),
        SettingsCategory(CAT_ID_MAP,                "Map",                     R.drawable.ic_map),
        SettingsCategory(CAT_ID_LOCATION,           "Receiver",                R.drawable.ic_section_location),
        SettingsCategory(CAT_ID_ABOUT,              "About",                   R.drawable.ic_section_info),
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
                CAT_ID_MAP                -> setupMapContent(inflater)
                CAT_ID_DATA               -> setupDataContent(inflater)
CAT_ID_DEV                -> setupDeveloperContent(inflater)
                CAT_ID_ABOUT              -> setupAboutContent(inflater)
                CAT_ID_APPEARANCE         -> setupAppearanceContent(inflater)
                else -> null
            }
        } catch (e: Exception) {
            settingsError("Settings category build failed id=${category.id}", e)
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

            categoryScope.launch {
                try {
                    val current = settingsRepo.appearanceSettings.first()
                    val label = options.firstOrNull { it.second == current.themeMode }?.first ?: options.first().first
                    spinner?.setText(label, false)
                    switchGnssBar?.isChecked   = current.showLiveGnssStatusBar
                    switchKeepAwake?.isChecked = current.keepScreenAwake
                    switchMaxBright?.isChecked = current.maxBrightnessWhileOpen
                } catch (e: Exception) {
                    settingsError("Settings load failed section=appearance", e)
                }
            }

            spinner?.setOnItemClickListener { _, _, position, _ ->
                val selected = options.getOrNull(position) ?: return@setOnItemClickListener
                saveScope.launch {
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
                        settingsError("Settings save failed section=appearance", e)
                        showSettingsMessage("Failed to save theme.")
                    }
                }
            }

            switchGnssBar?.setOnCheckedChangeListener { _, isChecked ->
                saveScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(showLiveGnssStatusBar = isChecked))
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=gnssStatusBar", e)
                    }
                }
            }

            switchKeepAwake?.setOnCheckedChangeListener { _, isChecked ->
                saveScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(keepScreenAwake = isChecked))
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=keepScreenAwake", e)
                    }
                }
            }

            switchMaxBright?.setOnCheckedChangeListener { _, isChecked ->
                saveScope.launch {
                    try {
                        val current = settingsRepo.appearanceSettings.first()
                        settingsRepo.setAppearanceSettings(current.copy(maxBrightnessWhileOpen = isChecked))
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=maxBrightness", e)
                    }
                }
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=appearance", e)
        }
        return view
    }

    private fun setupLocationContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_location, contentContainer, false)
        try { setupLocationSourceUi(view) } catch (e: Exception) { settingsError("Settings section setup failed section=locationSource", e) }
        return view
    }

    private fun setupDataContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_data, contentContainer, false)
        try {
            view.findViewById<Button>(R.id.btn_export_coordinates)?.setOnClickListener {
                try {
                    exportBackupLauncher.launch("coordinates_backup_${System.currentTimeMillis()}.json")
                } catch (e: Exception) { settingsError("Backup export launch failed", e) }
            }
            view.findViewById<Button>(R.id.btn_export_points_csv)?.setOnClickListener {
                try {
                    exportPointsCsvLauncher.launch("survey_points_${System.currentTimeMillis()}.csv")
                } catch (e: Exception) { settingsError("CSV export launch failed", e) }
            }
            view.findViewById<Button>(R.id.btn_import_coordinates)?.setOnClickListener {
                try {
                    // Most providers label .json as application/json; the broader types are a
                    // fallback for providers that mislabel it (the parser still validates content).
                    importBackupLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
                } catch (e: Exception) { settingsError("Import launch failed", e) }
            }
            view.findViewById<Button>(R.id.btn_cancel_import)?.setOnClickListener { try { cancelActiveImport() } catch (e: Exception) { settingsError("Import cancel failed", e) } }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=data", e)
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
                        settingsError("Settings load failed section=developerTools", e)
                    }
                }
                switchDevTools.setOnCheckedChangeListener { _, v ->
                    try {
                        saveScope.launch {
                            try {
                                settingsRepo.setDeveloperSettings(DeveloperSettings(developerToolsEnabled = v))
                            } catch (e: Exception) {
                                settingsError("Settings save failed section=developerTools", e)
                            }
                        }
                    } catch (e: Exception) {
                        settingsError("Settings developer tools switch failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=developer", e)
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

            val altitudeModes   = listOf("Automatic (recommended)" to "AUTO", "Stored Altitude" to "STORED", "Terrain Altitude" to "TERRAIN")
            val distanceOptions = listOf("All distances" to -1, "50 m" to 0, "100 m" to 1, "500 m" to 2)
            val tilScale = editScale?.parent?.parent as? com.google.android.material.textfield.TextInputLayout

            spinnerAltitude?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, altitudeModes.map { it.first }))
            spinnerFilter?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, distanceOptions.map { it.first }))

            // Last valid model scale. An invalid scale entry must not block saving the OTHER AR
            // settings (it used to return early, silently dropping switch/spinner changes).
            var lastValidScale = ArDisplaySettings().modelScale
            fun saveAll(altitudeMode: String? = null, distanceIdx: Int? = null) {
                val typed = editScale?.text?.toString()?.toFloatOrNull()
                val scale = if (typed != null && typed in 0.1f..10.0f) {
                    tilScale?.error = null
                    lastValidScale = typed
                    typed
                } else {
                    tilScale?.error = "Must be 0.1 – 10.0"
                    lastValidScale
                }
                val settings = ArDisplaySettings(
                    altitudeMode        = altitudeMode ?: (altitudeModes.firstOrNull { it.first == spinnerAltitude?.text?.toString() }?.second ?: "AUTO"),
                    distanceFilterIndex = distanceIdx ?: distanceOptions.indexOfFirst { it.first == spinnerFilter?.text?.toString() }.coerceAtLeast(0),
                    showDebugOverlay    = switchDebug?.isChecked ?: false,
                    showLabels          = switchLabels?.isChecked ?: true,
                    showOffscreenArrows = switchArrows?.isChecked ?: true,
                    modelScale          = scale,
                    showArDebugTools    = switchDebugTools?.isChecked ?: false,
                )
                saveScope.launch {
                    try {
                        settingsRepo.setArDisplaySettings(settings)
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=arDisplay", e)
                    }
                }
            }

            var isBinding = false
            categoryScope.launch {
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
                    lastValidScale = s.modelScale
                    isBinding = false
                } catch (e: Exception) {
                    isBinding = false
                    settingsError("Settings load failed section=arDisplay", e)
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
            // Also save while typing (debounced), but only once the box holds a valid value.
            editScale?.saveWhileTyping(saveScope) {
                if (editScale.text?.toString()?.toFloatOrNull()?.let { it in 0.1f..10.0f } == true) saveAll()
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=arDisplay", e)
        }
        return view
    }

    private fun setupMapContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_map, contentContainer, false)
        try {
            // ── Map defaults (MapSettings) ──────────────────────────────────────────
            val spinnerType   = view.findViewById<AutoCompleteTextView>(R.id.spinner_map_type)
            val spinnerGrid   = view.findViewById<AutoCompleteTextView>(R.id.spinner_grid_mode)
            val spinnerLabel  = view.findViewById<AutoCompleteTextView>(R.id.spinner_label_mode)
            val switchTools   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_map_tools_open)
            val switchDrawer  = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_drawer_expanded)
            val switchMyLoc   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_show_my_location)

            val typeOptions = listOf(
                "Normal" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL,
                "Satellite" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_SATELLITE,
                "Hybrid" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_HYBRID,
                "Terrain" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_TERRAIN,
            )
            val gridOptions = app.surrealar.ui.rendermap.MapGridMode.entries.map { it.label to it }
            val labelOptions = app.surrealar.ui.rendermap.PointLabelMode.entries.map { it.label to it }
            spinnerType?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, typeOptions.map { it.first }))
            spinnerGrid?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, gridOptions.map { it.first }))
            spinnerLabel?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, labelOptions.map { it.first }))

            var currentMap = app.surrealar.settings.SettingsDefaults.map
            fun persistMap(updated: app.surrealar.ui.rendermap.MapSettings) {
                currentMap = updated
                saveScope.launch { runCatching { settingsRepo.setMapSettings(updated) }.onFailure { settingsError("Settings save failed section=map", it) } }
            }

            // ── Stakeout defaults (StakeoutSettings) ────────────────────────────────
            val editTolerance = view.findViewById<EditText>(R.id.edit_stakeout_tolerance)
            val editWarnAcc   = view.findViewById<EditText>(R.id.edit_stakeout_warning_accuracy)
            val switchHaptics = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_stakeout_haptics)
            val switchAudio   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_stakeout_audio)
            val switchCompass = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_stakeout_compass)
            val switchKeepOn  = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_stakeout_keep_screen_on)
            var currentStakeout = app.surrealar.settings.SettingsDefaults.stakeout
            // Only positive numbers are accepted; a blank/partial entry ("", "0.") keeps the saved value.
            fun positiveOrNull(e: EditText?): Double? =
                e?.text?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
            fun persistStakeout() {
                val updated = currentStakeout.copy(
                    toleranceMeters = positiveOrNull(editTolerance) ?: currentStakeout.toleranceMeters,
                    warningAccuracyMeters = positiveOrNull(editWarnAcc) ?: currentStakeout.warningAccuracyMeters,
                    enableHaptics = switchHaptics?.isChecked ?: currentStakeout.enableHaptics,
                    enableAudio = switchAudio?.isChecked ?: currentStakeout.enableAudio,
                    guidanceUsesCompassHeading = switchCompass?.isChecked ?: currentStakeout.guidanceUsesCompassHeading,
                    keepScreenOnDuringStakeout = switchKeepOn?.isChecked ?: currentStakeout.keepScreenOnDuringStakeout,
                )
                if (updated == currentStakeout) return
                currentStakeout = updated
                // Save scope (not the view's): a save started as the user leaves the screen still completes.
                saveScope.launch { runCatching { settingsRepo.setStakeoutSettings(updated) }.onFailure { settingsError("Settings save failed section=stakeout", it) } }
            }

            categoryScope.launch {
                try {
                    currentMap = settingsRepo.mapSettings.first()
                    spinnerType?.setText(typeOptions.firstOrNull { it.second == currentMap.defaultMapType }?.first ?: "Normal", false)
                    spinnerGrid?.setText(currentMap.defaultGridMode.label, false)
                    spinnerLabel?.setText(currentMap.defaultPointLabelMode.label, false)
                    switchTools?.isChecked = currentMap.keepMapToolsOpenByDefault
                    switchDrawer?.isChecked = currentMap.mapPointsDrawerExpandedByDefault
                    switchMyLoc?.isChecked = currentMap.showMyLocationByDefault

                    spinnerType?.setOnItemClickListener { _, _, pos, _ -> typeOptions.getOrNull(pos)?.let { persistMap(currentMap.copy(defaultMapType = it.second)) } }
                    spinnerGrid?.setOnItemClickListener { _, _, pos, _ -> gridOptions.getOrNull(pos)?.let { persistMap(currentMap.copy(defaultGridMode = it.second)) } }
                    spinnerLabel?.setOnItemClickListener { _, _, pos, _ -> labelOptions.getOrNull(pos)?.let { persistMap(currentMap.copy(defaultPointLabelMode = it.second)) } }
                    switchTools?.setOnCheckedChangeListener { _, c -> persistMap(currentMap.copy(keepMapToolsOpenByDefault = c)) }
                    switchDrawer?.setOnCheckedChangeListener { _, c -> persistMap(currentMap.copy(mapPointsDrawerExpandedByDefault = c)) }
                    switchMyLoc?.setOnCheckedChangeListener { _, c -> persistMap(currentMap.copy(showMyLocationByDefault = c)) }

                    currentStakeout = settingsRepo.stakeoutSettings.first()
                    editTolerance?.setText(currentStakeout.toleranceMeters.toString())
                    editWarnAcc?.setText(currentStakeout.warningAccuracyMeters.toString())
                    switchHaptics?.isChecked = currentStakeout.enableHaptics
                    switchAudio?.isChecked = currentStakeout.enableAudio
                    switchCompass?.isChecked = currentStakeout.guidanceUsesCompassHeading
                    switchKeepOn?.isChecked = currentStakeout.keepScreenOnDuringStakeout

                    switchHaptics?.setOnCheckedChangeListener { _, _ -> persistStakeout() }
                    switchAudio?.setOnCheckedChangeListener { _, _ -> persistStakeout() }
                    switchCompass?.setOnCheckedChangeListener { _, _ -> persistStakeout() }
                    switchKeepOn?.setOnCheckedChangeListener { _, _ -> persistStakeout() }
                    // Save as soon as the box holds a valid value (and on Done / focus loss), so leaving
                    // the screen right after typing never loses the edit.
                    for (edit in listOfNotNull(editTolerance, editWarnAcc)) {
                        edit.addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) { persistStakeout() }
                        })
                        edit.setOnEditorActionListener { _, _, _ -> persistStakeout(); false }
                        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistStakeout() }
                    }
                } catch (e: Exception) {
                    settingsError("Settings load failed section=map", e)
                }
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=map", e)
        }
        return view
    }

    /**
     * Settings → Coordinates → Coordinate System: dropdowns for system, State Plane zone (all 118
     * SPCS83 zones, filterable), datum, units, height and external receiver datum, each saved
     * immediately, plus a live preview of the current position in the chosen system. State Plane
     * forces NAD83(2011), so the datum dropdown is disabled then.
     */
    private fun setupCoordinateSystemCard(view: View) {
        val spinnerH = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal) ?: return
        val spinnerD = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum)
        val spinnerU = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_unit)
        val spinnerV = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_vertical)
        val spinnerR = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_rx_datum)
        val preview = view.findViewById<android.widget.TextView>(R.id.text_coord_preview)
        val zoneRow = view.findViewById<View>(R.id.row_coord_spcs_zone)
        val spinnerZ = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_spcs_zone)
        val zoneHint = view.findViewById<android.widget.TextView>(R.id.text_coord_zone_hint)
        val zones = io.github.pennstatecs.geokit.projection.SpcsZones.all.sortedBy { it.label }
        val zoneByLabel = zones.associateBy { it.label }
        val defaultZoneHint = "All ${zones.size} SPCS83 zones. Type a state name to filter."
        var suggestedZoneEpsg: Int? = null

        val hs = app.surrealar.settings.model.HorizontalSystem.entries
        val ds = app.surrealar.settings.model.OutputDatum.entries
        val us = app.surrealar.settings.model.LinearUnit.entries
        val vs = app.surrealar.settings.model.VerticalReference.entries
        val rs = app.surrealar.settings.model.ReceiverDatum.entries
        fun adapter(labels: List<String>) = ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, labels)
        spinnerH.setAdapter(adapter(hs.map { it.label }))
        spinnerD?.setAdapter(adapter(ds.map { it.label }))
        spinnerU?.setAdapter(adapter(us.map { it.label }))
        spinnerV?.setAdapter(adapter(vs.map { it.label }))
        spinnerR?.setAdapter(adapter(rs.map { it.label }))
        // Editable so typing filters the 118 zones (ArrayAdapter matches word prefixes: "Tex", "north").
        spinnerZ?.setAdapter(adapter(zones.map { it.label }))

        var current = app.surrealar.settings.model.CoordinateSystemSettings()

        fun showPreview() {
            val fix = fixSwitchboard.currentFix.value
            preview?.text = if (fix == null) "Current position: waiting for GNSS" else runCatching {
                val p = coordinateSystemService.convertFix(fix, current)
                "Current position: ${app.surrealar.coords.CoordinateFormat.horizontal(p)} · " +
                    "${app.surrealar.coords.CoordinateFormat.height(p)}\n${app.surrealar.coords.CoordinateFormat.systemSummary(p)}"
            }.getOrElse { "Current position: conversion unavailable (${it.message})" }
        }

        fun bind(s: app.surrealar.settings.model.CoordinateSystemSettings) {
            current = s
            spinnerH.setText(s.horizontal.label, false)
            spinnerD?.setText(s.effectiveDatum.label, false)
            spinnerU?.setText(s.unit.label, false)
            spinnerV?.setText(s.vertical.label, false)
            spinnerR?.setText(s.externalReceiverDatum.label, false)
            val zone = io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(s.spcsZoneEpsg)
            zoneRow?.visibility = if (s.horizontal.isStatePlane) View.VISIBLE else View.GONE
            if (spinnerZ?.hasFocus() != true) spinnerZ?.setText(zone?.label.orEmpty(), false)
            // Hint: suggestion notice, or a warning when the live position is outside the chosen zone.
            val fix = fixSwitchboard.currentFix.value
            zoneHint?.text = when {
                zone != null && suggestedZoneEpsg == zone.epsg ->
                    "Suggested from your position. Check it: zone areas overlap near state lines."
                zone != null && fix != null && !zone.contains(fix.latDeg, fix.lonDeg) ->
                    "Your current position is outside this zone."
                else -> defaultZoneHint
            }
            // State Plane is defined on NAD83(2011): the datum choice does not apply.
            spinnerD?.isEnabled = !s.horizontal.isStatePlane
            (spinnerD?.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.alpha =
                if (s.horizontal.isStatePlane) 0.5f else 1f
            showPreview()
        }

        fun save(s: app.surrealar.settings.model.CoordinateSystemSettings) {
            bind(s)
            saveScope.launch {
                runCatching { settingsRepo.setCoordinateSystemSettings(s) }
                    .onFailure { settingsError("Coordinate system save failed", it) }
            }
        }

        spinnerH.setOnItemClickListener { _, _, pos, _ ->
            val h = hs.getOrNull(pos) ?: return@setOnItemClickListener
            var next = current.copy(horizontal = h)
            // Switching to State Plane: if the saved zone doesn't cover the live position, suggest one.
            val fix = fixSwitchboard.currentFix.value
            if (h.isStatePlane && fix != null &&
                io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(current.spcsZoneEpsg)?.contains(fix.latDeg, fix.lonDeg) != true
            ) {
                io.github.pennstatecs.geokit.projection.SpcsZone.suggestFor(fix.latDeg, fix.lonDeg)?.let {
                    next = next.copy(spcsZoneEpsg = it.epsg); suggestedZoneEpsg = it.epsg
                }
            }
            save(next)
        }
        spinnerZ?.setOnItemClickListener { parent, _, pos, _ ->
            // Positions refer to the FILTERED list: resolve by label, not index.
            val z = zoneByLabel[parent.getItemAtPosition(pos) as? String] ?: return@setOnItemClickListener
            suggestedZoneEpsg = null
            spinnerZ.clearFocus()
            save(current.copy(spcsZoneEpsg = z.epsg))
        }
        // Leaving the field without picking restores the saved zone's name.
        spinnerZ?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) spinnerZ.setText(io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(current.spcsZoneEpsg)?.label.orEmpty(), false)
        }
        spinnerD?.setOnItemClickListener { _, _, pos, _ -> ds.getOrNull(pos)?.let { save(current.copy(datum = it)) } }
        spinnerU?.setOnItemClickListener { _, _, pos, _ -> us.getOrNull(pos)?.let { save(current.copy(unit = it)) } }
        spinnerV?.setOnItemClickListener { _, _, pos, _ -> vs.getOrNull(pos)?.let { save(current.copy(vertical = it)) } }
        spinnerR?.setOnItemClickListener { _, _, pos, _ -> rs.getOrNull(pos)?.let { save(current.copy(externalReceiverDatum = it)) } }

        categoryScope.launch {
            runCatching { bind(settingsRepo.coordinateSystemSettings.first()) }
                .onFailure { settingsError("Coordinate system load failed", it) }
        }
        // Keep the preview live while the page is open.
        categoryScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.currentFix.collect { showPreview() }
            }
        }
    }

    private fun setupCoordinateDisplayContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_coordinate_display, contentContainer, false)
        try { setupCoordinateSystemCard(view) } catch (e: Exception) { settingsError("Settings section setup failed section=coordinateSystem", e) }
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
                saveScope.launch {
                    try {
                        val s = settingsRepo.coordinateDisplaySettings.first()
                        settingsRepo.setCoordinateDisplaySettings(s.copy(
                            defaultNamePrefix = prefix.ifEmpty { "Point" }
                        ))
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=coordinateNamePrefix", e)
                    }
                }
            }

            fun saveSwitch(accuracy: Boolean, autoInc: Boolean) {
                saveScope.launch {
                    try {
                        val s = settingsRepo.coordinateDisplaySettings.first()
                        settingsRepo.setCoordinateDisplaySettings(s.copy(
                            showAccuracyIndicators = accuracy,
                            autoIncrementNames     = autoInc
                        ))
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=coordinateDisplay", e)
                    }
                }
            }

            var isBinding = false
            categoryScope.launch {
                try {
                    val s = settingsRepo.coordinateDisplaySettings.first()
                    isBinding = true
                    switchAccuracy?.isChecked = s.showAccuracyIndicators
                    editPrefix?.setText(s.defaultNamePrefix)
                    switchAutoInc?.isChecked  = s.autoIncrementNames
                    isBinding = false
                } catch (e: Exception) {
                    isBinding = false
                    settingsError("Settings load failed section=coordinateDisplay", e)
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
            // Save while typing too (debounced); a momentarily empty box is not saved as "Point".
            editPrefix?.saveWhileTyping(saveScope) {
                if (editPrefix.text?.toString()?.trim().isNullOrEmpty().not()) savePrefix()
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=coordinateDisplay", e)
        }
        return view
    }

    private fun setupAboutContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.content_settings_about, contentContainer, false)
        try {
            val hash  = BuildInfo.gitHash
            val dirty = BuildInfo.gitDirty

            view.findViewById<TextView>(R.id.text_about_version)?.text =
                BuildConfig.VERSION_NAME
            view.findViewById<TextView>(R.id.text_about_build_number)?.text =
                BuildInfo.buildNumber.toString()
            view.findViewById<TextView>(R.id.text_about_commit)?.text =
                if (dirty) "$hash-dirty" else hash
            view.findViewById<TextView>(R.id.text_about_branch)?.text =
                BuildInfo.gitBranch
            view.findViewById<TextView>(R.id.text_about_dirty)?.text =
                if (dirty) "Yes" else "No"
            view.findViewById<TextView>(R.id.text_about_built)?.text =
                BuildInfo.buildTime

            val btnExport = view.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btn_export_diagnostic
            )
            btnExport?.setOnClickListener {
                btnExport.isEnabled = false
                btnExport.setText(R.string.diag_export_button_busy)
                categoryScope.launch {
                    val ctx = requireContext()
                    // ExportOutcome → share, or a friendly message per failure reason (never exception text).
                    val message = DiagnosticExportUi.exportAndShare(ctx)
                    btnExport.isEnabled = true
                    btnExport.setText(R.string.diag_export_button)
                    showSettingsMessage(getString(message),
                        if (message == R.string.diag_export_ready) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG)
                }
            }

            view.findViewById<View>(R.id.btn_mark_problem)?.setOnClickListener {
                val appContext = requireContext().applicationContext
                DiagnosticExportUi.showMarkProblemDialog(requireContext()) { note ->
                    categoryScope.launch {
                        val marker = DiagnosticExportUi.markProblem(appContext, note)
                        if (!isAdded) return@launch
                        showSettingsMessage(
                            if (marker == null) getString(R.string.diag_mark_failed)
                            else getString(R.string.diag_mark_done, DiagnosticExportUi.markTime(marker.epochMs)),
                            Snackbar.LENGTH_LONG,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=about", e)
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
            val spinnerPreset = view.findViewById<AutoCompleteTextView>(R.id.spinner_capture_preset)
            val textPresetSummary = view.findViewById<android.widget.TextView>(R.id.text_capture_preset_summary)
            val editPrecision = view.findViewById<EditText>(R.id.edit_precision_target)
            val tilPrecision = editPrecision?.parent?.parent as? com.google.android.material.textfield.TextInputLayout
            val presets = io.github.pennstatecs.geokit.gnss.capture.CapturePreset.entries
            spinnerPreset?.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, presets.map { it.label }))
            var currentPreset = io.github.pennstatecs.geokit.gnss.capture.CapturePreset.DEFAULT

            /** One line per mode describing what the selected preset will do. */
            fun describe(p: io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy): String {
                val q = app.surrealar.gnss.format.GnssStatusFormatter.formatCaptureFixStatus(p.requiredMinStatus.name)
                val precision = p.horizontalPrecisionTargetM?.let { " · spread ≤ ${"%.3f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.')} m" } ?: ""
                return "${p.minDurationSec}–${p.maxDurationSec} s · ${p.minSamples} fixes · $q+$precision"
            }
            fun applyPresetUi(preset: io.github.pennstatecs.geokit.gnss.capture.CapturePreset) {
                currentPreset = preset
                val custom = preset == io.github.pennstatecs.geokit.gnss.capture.CapturePreset.CUSTOM
                textPresetSummary?.text = if (custom)
                    "Custom: external receiver uses the rules below. Device GNSS: ${describe(io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy.INTERNAL)}."
                else
                    "External receiver: ${describe(preset.external!!)}\nDevice GNSS: ${describe(preset.internal!!)}"
                // The detailed fields only apply to Custom; show them read-only otherwise.
                val fields = listOf<View?>(spinner, editMinDur, editMaxDur, editSamples, editFixAge, editDiffAge, editPrecision)
                fields.forEach { f ->
                    f?.isEnabled = custom
                    (f?.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.let { til ->
                        til.isEnabled = custom
                        til.alpha = if (custom) 1f else 0.5f
                    }
                }
            }

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
                // Precision target: blank = off; otherwise 0.001–10 m.
                val precisionText = editPrecision?.text?.toString()?.trim().orEmpty()
                val precision = precisionText.toDoubleOrNull()
                val precisionValid = precisionText.isEmpty() || (precision != null && precision > 0.0 && precision <= 10.0)
                tilPrecision?.error = if (precisionValid) null else "Spread Target must be between 0.001 and 10 m, or blank."

                if (!precisionValid) return
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
                    maxDiffAgeSec     = diffAge,
                    preset            = currentPreset,
                    precisionTargetM  = if (precisionText.isEmpty()) null else precision
                )
                saveScope.launch {
                    try {
                        settingsRepo.setGnssCaptureSettings(settings)
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=gnssCapture", e)
                    }
                }
            }

            categoryScope.launch {
                try {
                    val saved = settingsRepo.gnssCaptureSettings.first()
                    spinner?.setText(rtkOptions.getOrElse(rtkOptions.indexOfFirst { it.second == saved.requiredMinStatus }.coerceAtLeast(0)) { rtkOptions.first() }.first, false)
                    editMinDur?.setText(saved.minDurationSec.toString())
                    editMaxDur?.setText(saved.maxDurationSec.toString())
                    editSamples?.setText(saved.minSamples.toString())
                    editFixAge?.setText(saved.maxFixAgeSec.toString())
                    editDiffAge?.setText(saved.maxDiffAgeSec.toString())
                    editPrecision?.setText(saved.precisionTargetM?.toString().orEmpty())
                    spinnerPreset?.setText(saved.preset.label, false)
                    applyPresetUi(saved.preset)
                } catch (e: Exception) {
                    settingsError("Settings load failed section=gnssCapture", e)
                }
            }

            spinnerPreset?.setOnItemClickListener { _, _, pos, _ ->
                val chosen = presets.getOrNull(pos) ?: return@setOnItemClickListener
                applyPresetUi(chosen)
                // Save just the preset (stored Custom values are kept as-is), independent of whether
                // the Custom fields currently validate.
                saveScope.launch {
                    runCatching {
                        val stored = settingsRepo.gnssCaptureSettings.first()
                        settingsRepo.setGnssCaptureSettings(stored.copy(preset = chosen))
                    }.onFailure { settingsError("Settings save failed section=capturePreset", it) }
                }
            }
            spinner?.setOnItemClickListener { _, _, _, _ -> trySave() }
            listOf(editMinDur, editMaxDur, editSamples, editFixAge, editDiffAge, editPrecision).forEach { edit ->
                edit?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) trySave() }
                edit?.setOnEditorActionListener { _, _, _ -> trySave(); false }
                // Save while typing too (debounced); trySave only writes when every rule is valid.
                edit?.saveWhileTyping(saveScope) { if (currentPreset == io.github.pennstatecs.geokit.gnss.capture.CapturePreset.CUSTOM) trySave() }
            }
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=gnssCapture", e)
        }
        return view
    }

    // ───────────────────────────── Location Source UI ─────────────────────────
    private fun setupLocationSourceUi(view: View) {
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_location_source)
        val internalGpsGroup = view.findViewById<LinearLayout>(R.id.group_internal_gps)
        val rs2OptionsLayout = view.findViewById<LinearLayout>(R.id.layout_rs2_options)
        val editHost = view.findViewById<EditText>(R.id.edit_host)
        val editPort = view.findViewById<EditText>(R.id.edit_port)
        val editAntennaHeight = view.findViewById<EditText>(R.id.edit_antenna_height)
        val editInternalAntennaHeight = view.findViewById<EditText>(R.id.edit_internal_antenna_height)

        // ── External receiver profile selector ──────────────────────────────────────
        // All profiles use the SAME External TCP NMEA pipeline; the profile only carries display
        // labels + sensible defaults. Uses the same Material ExposedDropdownMenu component as the
        // capture/appearance dropdowns (defined in the layout). Selecting a profile persists it and
        // applies its default port.
        run {
            val profiles = app.surrealar.settings.model.ExternalReceiverProfile.entries
            val profileDropdown = view.findViewById<AutoCompleteTextView>(R.id.dropdown_receiver_profile)
            val profileHint = view.findViewById<TextView>(R.id.text_receiver_profile_hint)

            profileDropdown?.setAdapter(
                ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, profiles.map { it.label })
            )

            fun applyHint(profile: app.surrealar.settings.model.ExternalReceiverProfile) {
                val baseHelp = "Choose the receiver model so the app can use the right labels, " +
                    "defaults, and diagnostics. Position data still comes from NMEA over TCP."
                profileHint?.text = profile.hint?.let { "$baseHelp\n\n$it" } ?: baseHelp
            }

            categoryScope.launch {
                val current = runCatching { settingsRepo.externalReceiverProfile.first() }
                    .getOrDefault(app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT)
                profileDropdown?.setText(current.label, false)
                applyHint(current)
            }

            profileDropdown?.setOnItemClickListener { _, _, position, _ ->
                val profile = profiles.getOrNull(position) ?: return@setOnItemClickListener
                applyHint(profile)
                // Persist + apply default port only on a genuine change.
                saveScope.launch {
                    val persisted = runCatching { settingsRepo.externalReceiverProfile.first() }
                        .getOrDefault(app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT)
                    if (persisted != profile) {
                        runCatching { settingsRepo.setExternalReceiverProfile(profile) }
                            .onFailure { settingsError("Settings save failed section=receiverProfile", it) }
                        if (this@SettingsFragment.view == null) return@launch
                        // Conservative: only apply the new profile's default port if the current
                        // port is missing/invalid or wasn't user-customized (a custom port is kept).
                        val currentPort = editPort?.text?.toString()?.trim()?.toIntOrNull()
                        editPort?.setText(
                            app.surrealar.settings.model.ExternalReceiverProfile
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

        // Show the receiver's battery / corrections / device-info controls only when the selected profile
        // reports them (Emlid: all; Generic NMEA: none).
        categoryScope.launch {
            receiverDevices.profileCapabilities.collect { caps ->
                (tvBattery?.parent as? View)?.isVisible = caps.hasBattery
                chipBattery?.isVisible = caps.hasBattery
                (tvCorrections?.parent as? View)?.isVisible = caps.hasCorrectionsStatus
                view.findViewById<View>(R.id.btn_refresh_device_info)?.isVisible = caps.hasDeviceStatus
            }
        }
        val defaultChipBg    = chipSource?.chipBackgroundColor
        val defaultChipTextColor = chipFix?.textColors

        // Wire up device info button handlers - now observes the repository's StateFlow
        val btnRefreshDeviceInfo = view.findViewById<Button>(R.id.btn_refresh_device_info)
        btnRefreshDeviceInfo?.setOnClickListener {
            // The repository automatically polls device info when external source is active
            // Show current info or message if not available
            val currentInfo = receiverDevices.deviceInfo.value
            if (currentInfo != null) {
                // Convert the receiver-neutral device info to EmlidDeviceInfo for display
                currentDeviceInfo = convertToEmlidDeviceInfo(currentInfo)
                updateDeviceInfoDisplay()
            } else {
                showSettingsMessage("Device info not available. Ensure external connection is active.")
            }
        }

        // Observe device info from repository
        categoryScope.launch {
            receiverDevices.deviceInfo.collect { reachInfo ->
                try {
                    if (reachInfo != null) {
                        currentDeviceInfo = convertToEmlidDeviceInfo(reachInfo)
                        updateDeviceInfoDisplay()
                        if (selectedDeviceName == selectedDevice?.first || selectedDeviceName.isNullOrBlank()) {
                            selectedDeviceName = reachInfo.name ?: selectedDevice?.first
                            selectedDevice?.let { (host, port) ->
                                val name = selectedDeviceName ?: "Unknown Device"
                                saveScope.launch {
                                    runCatching { settingsRepo.setExternalTcp(host, port, name) }
                                        .onFailure { settingsError("Settings save failed section=receiverName", it) }
                                }
                            }
                            updateDeviceBox()
                        }
                    }
                } catch (e: Exception) {
                    settingsWarn("External receiver info display failed", e, DiagTags.RECEIVER)
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
                                    saveScope.launch {
                                        runCatching { settingsRepo.setExternalTcp(host, port, resolved) }
                                            .onFailure { settingsError("Settings save failed section=receiverName", it) }
                                    }
                                    updateDeviceBox()
                                }
                            } catch (e: Exception) {
                                settingsWarn("External receiver name lookup failed", e, DiagTags.RECEIVER)
                            }
                        }
                    }
                } catch (e: Exception) {
                    settingsError("External receiver diagnostic probe failed port=$port", e, DiagTags.RECEIVER)
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
                settingsError("External receiver connect failed", e)
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
            saveScope.launch {
                try {
                    settingsRepo.setMockLocationEnabled(false)
                    settingsRepo.setLocationSource(LocationSourceType.INTERNAL)
                    settingsRepo.clearExternalTcp()
                } catch (e: Exception) {
                    settingsError("External receiver disconnect failed", e)
                }
            }
        }

        // Prefill inline inputs with last used values. The host is left blank on first use (no
        // hardcoded device address); with no saved port, use the selected receiver profile's default
        // (Reach = 9001). Nothing is persisted here — settings are only saved when the user connects.
        lifecycleScope.launch {
            val lastHost = settingsRepo.externalTcpHost.first()
            val lastPort = settingsRepo.externalTcpPort.first()
            val profilePort = runCatching { settingsRepo.externalReceiverProfile.first().defaultPort }
                .getOrDefault(SettingsDefaults.externalTcpPort)

            editHost?.setText(lastHost.orEmpty())
            editPort?.setText((lastPort ?: profilePort).toString())
        }

        // Observe stored TCP host/port and auto-diagnose on new device
        categoryScope.launch {
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
        categoryScope.launch {
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
        deviceStatusJob = categoryScope.launch {
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
                val bat = receiverDevices.battery.value

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
                                RtkStatus.FIX            -> "RTK Fix"         to android.R.color.holo_green_dark
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
                                RtkStatus.FIX            -> "Fix"    to R.color.app_success
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

                // Satellites row + chip. Counts only while NMEA is arriving: during a reconnect the
                // last sky snapshot is still held, and showing it would look live (Home card agrees).
                val skyLive = nmeaAge <= 8_000L && sky.totalVisible > 0
                tvSatellites?.let { tv ->
                    when {
                        skyLive -> {
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
                        skyLive -> { chip.text = "${sky.totalUsed}/${sky.totalVisible} Sats"; chip.chipBackgroundColor = chipClr(R.color.app_success); chip.setTextColor(android.graphics.Color.WHITE) }
                        nmeaAge <= 8_000L    -> { chip.text = "0 Sats"; chip.chipBackgroundColor = chipClr(R.color.app_warning); chip.setTextColor(android.graphics.Color.WHITE) }
                        else                 -> { chip.text = "—"; defaultChipBg?.let { chip.chipBackgroundColor = it }; defaultChipTextColor?.let { chip.setTextColor(it) } }
                    }
                }

                // Corrections row (unchanged)
                tvCorrections?.let { tv ->
                    val info = receiverDevices.corrections.value
                    if (info == null) {
                        tv.text = "—"
                        tv.setTextColor(clr(android.R.color.darker_gray))
                    } else if (info.isReceiving) {
                        val ch = info.channel ?: "Connected"
                        val age = info.ageS?.let { " (${it.toInt()}s)" } ?: ""
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
                        val chargeSuffix = if (bat.isCharging) " ⚡" else ""
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
                        val chargeSuffix = if (bat.isCharging) "⚡" else ""
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
            categoryScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        // currentFix, not the fixes replay buffer: re-entering Settings replayed the last
                        // fix and stamped it 'now', briefly showing STREAMING for a dead stream.
                        fixSwitchboard.currentFix.filterNotNull().collect { fix ->
                            if (fix.provider == Provider.INTERNAL) return@collect
                            if (!currentExternalActive()) return@collect
                            lastExternalFixTimeMs = System.currentTimeMillis()
                            lastExternalFixRtkStatus = fix.rtkStatus
                            provisionalConnectedUntil = 0L
                            // Per fix: at most one line a minute.
                            try { refresh(lastExternalFixTimeMs) } catch (e: Exception) {
                                DiagnosticsLogger.every(DiagTags.SETTINGS, "settings.status.refresh", 60_000L, 'W', e) { "External receiver status refresh failed trigger=fix" }
                            }
                        }
                    } catch (e: Exception) {
                        settingsWarn("Settings receiver status fix collection failed", e)
                    }
                }
            }

            // Track raw NMEA activity (external adapter only) to distinguish "no data" from "no fix".
            categoryScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    fixSwitchboard.rawNmea.collect {
                        if (currentExternalActive()) lastExternalRawNmeaTimeMs = System.currentTimeMillis()
                    }
                }
            }
            // Periodic refresh to age out status
            while (isActive) {
                try { refresh(System.currentTimeMillis()) } catch (e: Exception) {
                    DiagnosticsLogger.every(DiagTags.SETTINGS, "settings.status.periodic", 60_000L, 'W', e) { "External receiver status refresh failed trigger=periodic" }
                }
                delay(1_000L)
            }
        }
        textDeviceStatus?.text = ""


        // ── Antenna setup (external receiver) ──
        // Pole height and camera distance are measured to the bottom of the receiver; the receiver
        // model's phase-centre offset is added (AntennaSetup). Pole height → captures, camera → AR.
        val editCameraBelow = view.findViewById<EditText>(R.id.edit_camera_below_receiver)
        val editPhaseCenter = view.findViewById<EditText>(R.id.edit_phase_center_offset)
        val textPhaseCenterSource = view.findViewById<TextView>(R.id.text_phase_center_offset_source)
        val textAntennaSummary = view.findViewById<TextView>(R.id.text_antenna_setup_summary)
        val groupPoleCheck = view.findViewById<View>(R.id.group_pole_height_check)

        fun updateReceiverSettings(transform: (app.surrealar.gnss.settings.GnssReceiverSettings) -> app.surrealar.gnss.settings.GnssReceiverSettings) {
            saveScope.launch {
                try {
                    settingsRepo.setGnssReceiverSettings(transform(settingsRepo.gnssReceiverSettings.first()))
                } catch (e: Exception) {
                    settingsError("Settings save failed section=antennaSetup", e)
                }
            }
        }

        // Live: summary line, the one-time pole-height notice, and the profile's phase-centre offset.
        categoryScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    settingsRepo.gnssReceiverSettings, settingsRepo.externalReceiverProfile,
                ) { receiver, profile -> Triple(receiver, profile, app.surrealar.settings.model.AntennaSetup.resolve(receiver, profile)) }
                    .collect { (receiver, profile, setup) ->
                        groupPoleCheck?.visibility = if (receiver.poleHeightNeedsCheck) View.VISIBLE else View.GONE
                        textPhaseCenterSource?.text = when {
                            setup.offsetFromProfile -> "From the receiver model (${profile.label}). Change only for a different antenna."
                            profile.phaseCenterOffsetM != null -> "Entered by you. ${profile.label}: ${AntennaHeightInput.format(profile.phaseCenterOffsetM)} m."
                            else -> "Not known for ${profile.label}: enter it from the receiver's documentation."
                        }
                        if (editPhaseCenter?.hasFocus() != true) {
                            editPhaseCenter?.setText(AntennaHeightInput.format(setup.phaseCenterOffsetM))
                        }
                        textAntennaSummary?.text = "Captures subtract ${AntennaHeightInput.format(setup.captureOffsetM)} m " +
                            "(pole + offset). AR puts the antenna ${AntennaHeightInput.format(setup.antennaAboveCameraM)} m above the camera."
                    }
            }
        }

        view.findViewById<View>(R.id.btn_pole_height_ok)?.setOnClickListener {
            updateReceiverSettings { it.copy(poleHeightNeedsCheck = false) }
        }

        // Pole height: ground mark → bottom of the receiver.
        editAntennaHeight?.apply {
            categoryScope.launch {
                try {
                    setText(AntennaHeightInput.format(settingsRepo.gnssReceiverSettings.first().poleHeightM))
                } catch (e: Exception) {
                    settingsError("Settings load failed section=poleHeight", e)
                }
            }
            val saveAntenna = {
                val typed = text?.toString()
                saveScope.launch {
                    try {
                        val current = settingsRepo.gnssReceiverSettings.first()
                        val meters = AntennaHeightInput.parseMeters(typed)
                        if (meters == null) {
                            if (this@SettingsFragment.view == null) return@launch
                            // Bad input: keep the stored value, say so, and restore the field.
                            showAntennaHeightError(this@apply)
                            setText(AntennaHeightInput.format(current.poleHeightM))
                        } else {
                            // Changing the value answers the one-time "check your pole height" notice.
                            settingsRepo.setGnssReceiverSettings(current.copy(
                                poleHeightM = meters,
                                poleHeightNeedsCheck = current.poleHeightNeedsCheck && meters == current.poleHeightM,
                            ))
                            if (this@SettingsFragment.view != null) clearAntennaHeightError(this@apply)
                        }
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=poleHeight", e)
                    }
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAntenna() }
            setOnEditorActionListener { _, _, _ -> saveAntenna(); false }
            // Save while typing (debounced), only for a valid height: partial input ("", "1.") is left alone.
            saveWhileTyping(saveScope) { if (AntennaHeightInput.parseMeters(text) != null) saveAntenna() }
        }

        // Tablet camera below the receiver (AR). Signed: negative when the camera is higher.
        editCameraBelow?.apply {
            categoryScope.launch {
                try {
                    setText(AntennaHeightInput.format(settingsRepo.gnssReceiverSettings.first().cameraBelowReceiverM))
                } catch (e: Exception) {
                    settingsError("Settings load failed section=cameraDistance", e)
                }
            }
            val saveCamera = {
                val typed = text?.toString()
                saveScope.launch {
                    try {
                        val current = settingsRepo.gnssReceiverSettings.first()
                        val meters = AntennaHeightInput.parseCameraBelowMeters(typed)
                        if (meters == null) {
                            if (this@SettingsFragment.view == null) return@launch
                            inputLayoutOf(this@apply)?.error = "Enter −3 to 3 m. Previous value kept."
                            setText(AntennaHeightInput.format(current.cameraBelowReceiverM))
                        } else {
                            settingsRepo.setGnssReceiverSettings(current.copy(cameraBelowReceiverM = meters))
                            if (this@SettingsFragment.view != null) clearAntennaHeightError(this@apply)
                        }
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=cameraDistance", e)
                    }
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveCamera() }
            setOnEditorActionListener { _, _, _ -> saveCamera(); false }
            saveWhileTyping(saveScope) { if (AntennaHeightInput.parseCameraBelowMeters(text) != null) saveCamera() }
        }

        // Phase-centre offset: the profile's value unless the user enters a different one.
        editPhaseCenter?.apply {
            val savePhaseCenter = {
                val typed = text?.toString()
                saveScope.launch {
                    try {
                        val current = settingsRepo.gnssReceiverSettings.first()
                        val profile = settingsRepo.externalReceiverProfile.first()
                        val meters = AntennaHeightInput.parsePhaseCenterOffsetMeters(typed)
                        if (meters == null) {
                            if (this@SettingsFragment.view == null) return@launch
                            inputLayoutOf(this@apply)?.error = "Enter 0 to ${AntennaHeightInput.format(app.surrealar.settings.model.AntennaSetup.MAX_PHASE_CENTER_OFFSET_M)} m. Previous value kept."
                            setText(AntennaHeightInput.format(app.surrealar.settings.model.AntennaSetup.resolve(current, profile).phaseCenterOffsetM))
                        } else {
                            // Matching the profile's value clears the override, so a later profile change applies.
                            val override = if (profile.phaseCenterOffsetM != null &&
                                kotlin.math.abs(meters - profile.phaseCenterOffsetM) < 0.0005) null else meters
                            settingsRepo.setGnssReceiverSettings(current.copy(phaseCenterOffsetOverrideM = override))
                            if (this@SettingsFragment.view != null) clearAntennaHeightError(this@apply)
                        }
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=phaseCentreOffset", e)
                    }
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) savePhaseCenter() }
            setOnEditorActionListener { _, _, _ -> savePhaseCenter(); false }
            saveWhileTyping(saveScope) { if (AntennaHeightInput.parsePhaseCenterOffsetMeters(text) != null) savePhaseCenter() }
        }

        // Tablet / internal-GNSS antenna height — applied to internal-GPS captures (pole-mounted tablet).
        editInternalAntennaHeight?.apply {
            categoryScope.launch {
                try {
                    setText(AntennaHeightInput.format(settingsRepo.gnssReceiverSettings.first().internalAntennaHeightM))
                } catch (e: Exception) {
                    settingsError("Settings load failed section=internalAntennaHeight", e)
                }
            }
            val saveInternalAntenna = {
                val typed = text?.toString()
                saveScope.launch {
                    try {
                        val current = settingsRepo.gnssReceiverSettings.first()
                        val meters = AntennaHeightInput.parseMeters(typed)
                        if (meters == null) {
                            if (this@SettingsFragment.view == null) return@launch
                            showAntennaHeightError(this@apply)
                            setText(AntennaHeightInput.format(current.internalAntennaHeightM))
                        } else {
                            settingsRepo.setGnssReceiverSettings(current.copy(internalAntennaHeightM = meters))
                            if (this@SettingsFragment.view != null) clearAntennaHeightError(this@apply)
                        }
                    } catch (e: Exception) {
                        settingsError("Settings save failed section=internalAntennaHeight", e)
                    }
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInternalAntenna() }
            setOnEditorActionListener { _, _, _ -> saveInternalAntenna(); false }
            saveWhileTyping(saveScope) { if (AntennaHeightInput.parseMeters(text) != null) saveInternalAntenna() }
        }

        // ── Advanced: mock location publishing ──
        val switchMock   = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_publish_mock_location)
        val errorBanner  = view.findViewById<LinearLayout>(R.id.layout_mock_location_error)
        val btnDevOpts   = view.findViewById<Button>(R.id.btn_open_developer_options)

        categoryScope.launch {
            try {
                switchMock?.isChecked = settingsRepo.mockLocationEnabled.first()
            } catch (e: Exception) {
                settingsError("Settings load failed section=mockLocation", e)
            }
        }

        switchMock?.setOnCheckedChangeListener { _, checked ->
            saveScope.launch {
                try {
                    settingsRepo.setMockLocationEnabled(checked)
                    if (!checked && this@SettingsFragment.view != null) errorBanner?.visibility = View.GONE
                } catch (e: Exception) {
                    settingsError("Settings save failed section=mockLocation", e)
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
                    settingsWarn("Developer options screen open failed", ex)
                }
            }
        }

        categoryScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    var lastShownError: AndroidMockLocationPublisher.MockLocationError? = null
                    SurRealApplication.mockLocationPublisher.errorEvents.collect { error ->
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
                    settingsError("Mock location error observer failed", e)
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
                // Same rule as the prefill above: the selected profile's default port.
                val profilePort = runCatching { settingsRepo.externalReceiverProfile.first().defaultPort }
                    .getOrDefault(SettingsDefaults.externalTcpPort)
                editPort?.setText(profilePort.toString())
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
                receiverDevices.deviceInfo.value?.let { cached ->
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
                    DiagnosticsLogger.i(
                        DiagTags.GNSS, "External receiver connect cancelled: switched to device GNSS attempt=$tcpConnectAttemptId"
                    )
                    // Invalidate and cancel any in-progress TCP connection attempt
                    ++tcpConnectAttemptId
                    tcpConnectJob?.cancel()
                    tcpConnectJob = null
                    resetExternalReceiverStatus()
                    // Update switchboard provider to internal
                    gnssSourceCoordinator.activateInternalProvider("settings")
                    // Update categories immediately when switching to internal (hide RS2+)
                    refreshCategoriesForSource(LocationSourceType.INTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
                    rs2OptionsLayout?.visibility = View.GONE
                    provisionalConnectedUntil = 0L
                    updateDeviceBox()
                    // Immediately disable mock publishing and update its switch UI
                    switchMock?.isChecked = false
                    switchMock?.isEnabled = false
                    saveScope.launch {
                        runCatching {
                            settingsRepo.setMockLocationEnabled(false)
                            settingsRepo.setLocationSource(LocationSourceType.INTERNAL)
                        }.onFailure { settingsError("Settings save failed section=locationSource", it) }
                    }
                    if (!LocationService.isRunning) LocationService.start(requireContext())
                }
                R.id.radio_es2_tcp -> {
                    DevLog.d("SettingsFragment") { "Radio switched to EXTERNAL_TCP" }
                    // Re-enable mock switch (user must still turn it on explicitly)
                    switchMock?.isEnabled = true
                    // Update categories immediately when switching to external (show RS2+)
                    refreshCategoriesForSource(LocationSourceType.EXTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.EXTERNAL, internalGpsGroup)
                    // Show either the device box (if already selected) or options
                    updateDeviceBox()
                    // Persist source selection asynchronously — does not need to complete
                    // before the connect attempt starts.
                    saveScope.launch {
                        runCatching {
                            settingsRepo.setLocationSource(LocationSourceType.EXTERNAL)
                            settingsRepo.setExternalConnType(ExternalConnectionType.TCP)
                        }.onFailure { settingsError("Settings save failed section=locationSource", it) }
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
                // Expected when the receiver is off / unreachable; the caller logs the probe outcome.
                DevLog.d("SettingsFragment") { "TCP probe connect failed port=$port: ${e.javaClass.simpleName}" }
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
            DevLog.d("SettingsFragment") { "TCP probe error port=$port: ${e.javaClass.simpleName}" }
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
        ReceiverHttpProbe.statusApiReachable(host)
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
                    // No host: the Setting lines already record the saved values.
                    DiagnosticsLogger.i(
                        DiagTags.GNSS, "External receiver settings saved port=$port attempt=$attemptId tcp=$tcpResult http=$httpReachable"
                    )

                    // Final stale-attempt guard before flipping the live provider. If the user
                    // switched back to Internal while the probe/persist was in flight, the attempt
                    // id has advanced — abort so this old attempt can't activate External after the
                    // user already returned to Internal.
                    if (attemptId != tcpConnectAttemptId) {
                        DiagnosticsLogger.i(
                            DiagTags.GNSS, "External receiver connect attempt ignored: superseded before activation attempt=$attemptId"
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
                                settingsWarn("External receiver name lookup failed", e, DiagTags.RECEIVER)
                            }
                        }
                    }
                } else {
                    // Both TCP and HTTP failed — receiver unreachable.
                    DiagnosticsLogger.w(DiagTags.RECEIVER,
                        "External receiver unavailable: connect probe failed tcp=$tcpResult http=$httpReachable port=$port attempt=$attemptId")
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
                    settingsWarn("External receiver connect probe failed port=$port attempt=$attemptId", e, DiagTags.RECEIVER)
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
    /** Writes the official full JSON backup to the document the user chose in the save dialog. */
    private fun writeBackupTo(uri: Uri) {
        // Captured now: the save scope outlives the fragment, so requireContext() may fail later.
        val appContext = requireContext().applicationContext
        saveScope.launch {
            runCatching {
                val appVersion = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                }.getOrNull()
                // Use case loads the data and builds the JSON (and logs its own failure); the
                // fragment owns the URI write, logged here.
                val result = exportBackup(appVersion)
                try {
                    withContext(Dispatchers.IO) {
                        // "wt" truncates: overwriting a longer existing file must not leave its tail behind.
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(result.json.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                } catch (e: Exception) {
                    settingsError("Backup export write failed rows=${result.coordinateCount} dest=SAF", e, DiagTags.EXPORT)
                    throw e
                }
                result.coordinateCount
            }.onSuccess { n ->
                showSettingsMessage("JSON backup exported: $n coordinates", Snackbar.LENGTH_LONG)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    /** Writes all points as CSV in the configured coordinate system, units and height reference. */
    private fun writePointsCsvTo(uri: Uri) {
        val appContext = requireContext().applicationContext
        saveScope.launch {
            runCatching {
                // DataOpLog: start / done rows=n / failed (E, with the exception) lines.
                app.surrealar.util.diagnostics.DataOpLog.run("CSV export", "csv", { n: Int -> n }) {
                    val coordSettings = settingsRepo.coordinateSystemSettings.first()
                    val points = withContext(Dispatchers.IO) { repository.getAllCoordinatesList() }
                    // Per-point datum/projection/geoid conversion is CPU work: keep it off the main thread.
                    val csv = withContext(Dispatchers.Default) {
                        app.surrealar.data.export.PointsCsvExporter.build(points) {
                            coordinateSystemService.convertCoordinate(it, coordSettings)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(csv.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                    points.size
                }
            }.onSuccess { n ->
                showSettingsMessage("Points CSV exported: $n points", Snackbar.LENGTH_LONG)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                showSettingsMessage("Export failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    /** Routes a picked backup document into the import confirmation/merge flow. */
    private fun onImportDocumentPicked(uri: Uri) {
        lifecycleScope.launch {
            try {
                prepareImportCoordinatesWithConfirmation(uri)
            } catch (e: Exception) {
                settingsError("Backup import failed stage=prepare", e, DiagTags.DATA)
                showSettingsMessage("Import failed: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    // --- Import pipeline (full JSON backup; basic JSON arrays are still auto-detected) ---
    private suspend fun prepareImportCoordinatesWithConfirmation(uri: Uri) {
        val existing = withContext(Dispatchers.IO) { repository.getAllCoordinatesList().size }
        if (existing == 0) { launchImportCoordinates(uri, replace = false); return }
        pendingImportUri = uri
        if (!isAdded) return
        // Duplicate-ID policy: merge overwrites matching IDs (and reports the count); replace clears
        // first. The user must confirm before any existing data is overwritten.
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.import_select_file_title))
            .setMessage(getString(R.string.import_merge_replace_message))
            .setPositiveButton(R.string.import_merge) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, false) } }
            .setNeutralButton(R.string.import_replace) { _, _ -> pendingImportUri?.let { launchImportCoordinates(it, true) } }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    /**
     * Second confirmation for a Replace whose backup has invalid rows, now that the counts are known.
     * Cancel (or a dismissed dialog / gone screen) keeps the existing points.
     */
    private suspend fun confirmPartialReplace(
        plan: app.surrealar.data.backup.BackupImportPlan, existingCount: Int,
    ): PartialReplaceDecision {
        DiagnosticsLogger.w(DiagTags.DATA, "Backup replace needs confirmation valid=${plan.insertCount} " +
            "skipped=${plan.skippedCount} existing=$existingCount")
        return withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext PartialReplaceDecision.CANCEL
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                fun answer(d: PartialReplaceDecision) {
                    if (cont.isActive) cont.resumeWith(Result.success(d))
                }
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Backup has invalid entries")
                    .setMessage("Only ${plan.insertCount} of ${plan.insertCount + plan.skippedCount} entries in this backup " +
                        "are valid (${plan.skippedCount} would be skipped).\n\nReplace deletes all $existingCount " +
                        "coordinates on this device and keeps only the ${plan.insertCount} valid ones. This cannot be undone.")
                    .setPositiveButton("Merge instead") { _, _ -> answer(PartialReplaceDecision.MERGE_INSTEAD) }
                    .setNeutralButton("Replace anyway") { _, _ -> answer(PartialReplaceDecision.REPLACE) }
                    .setNegativeButton(R.string.import_cancel) { _, _ -> answer(PartialReplaceDecision.CANCEL) }
                    .setOnCancelListener { answer(PartialReplaceDecision.CANCEL) }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }.also { DiagnosticsLogger.i(DiagTags.DATA, "Backup replace confirmation answered decision=$it") }
    }

    private fun launchImportCoordinates(uri: Uri, replace: Boolean) {
        if (coordinatesImportJob?.isActive == true) return
        // Save scope: leaving Settings must not cancel the import mid-write. Cancel still works
        // through the job reference.
        val appContext = requireContext().applicationContext
        coordinatesImportJob = saveScope.launch { importCoordinates(appContext, uri, replace) }
    }

    private suspend fun importCoordinates(appContext: Context, uri: Uri, replace: Boolean) {
        showImportProgress(true, 0, "Scanning…")
        importProcessed = 0; importTotal = 0
        runCatching {
            // Fragment owns the URI read; the use case parses, plans (duplicates/missing models),
            // and writes via the repositories.
            val raw = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { inp ->
                        BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
                    } ?: error("Unable to open input stream")
                }
            } catch (e: Exception) {
                // The use case logs parse/write failures itself (DataOpLog); the file read is logged here.
                settingsError("Backup import failed stage=readFile", e, DiagTags.DATA)
                throw e
            }
            showImportProgress(true, 70, "Writing…")
            importBackup(raw, replace) { plan, existingCount -> confirmPartialReplace(plan, existingCount) }
        }.onSuccess { plan ->
            showImportProgress(false, 100, when {
                plan.cancelled -> "Canceled"
                plan.isNoOp -> "Nothing to import"
                else -> "Completed"
            })
            // Counts only: the lists hold point names.
            if (plan.skippedInvalid.isNotEmpty() || plan.missingModelRefs.isNotEmpty()) {
                DiagnosticsLogger.w(DiagTags.DATA, "Import skipped invalid rows skipped=${plan.skippedInvalid.size} missingModels=${plan.missingModelRefs.size}")
            }
            showSettingsMessage(plan.summaryMessage(), Snackbar.LENGTH_LONG)
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
            settingsWarn("Import progress display failed", e)
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
            settingsWarn("Import cancel failed", e)
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
                RtkStatus.FIX -> "RTK Fix"
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
                        app.surrealar.domain.model.TestStatus.PASSED -> "✓"
                        app.surrealar.domain.model.TestStatus.FAILED -> "✗"
                        app.surrealar.domain.model.TestStatus.WARNING -> "⚠"
                        app.surrealar.domain.model.TestStatus.UNKNOWN -> "?"
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
                try { populateSelfTestsGrid(layoutSelfTestsGrid, deviceInfo.selfTests) } catch (e: Exception) { settingsWarn("External receiver self-test display failed", e) }
            } else {
                layoutSelfTests?.visibility = View.GONE
            }
        } catch (e: Exception) {
            settingsError("External receiver info display failed", e)
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
                            settingsWarn("External receiver self-test row failed test=\"${test.name}\"", e)
                        }
                    }
                    if (rowTests.size < testsPerRow) {
                        rowLayout.addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, (testsPerRow - rowTests.size).toFloat())
                        })
                    }
                    container.addView(rowLayout)
                } catch (e: Exception) {
                    settingsWarn("External receiver self-test row failed", e)
                }
            }
        } catch (e: Exception) {
            settingsError("External receiver self-test display failed", e)
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
                        settingsWarn("External receiver self-test detail dialog failed", e)
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
    private fun convertToEmlidDeviceInfo(reachInfo: io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo): EmlidDeviceInfo {
        return EmlidDeviceInfo(
            deviceName = reachInfo.name ?: reachInfo.host ?: "Unknown Device",
            ipAddress = reachInfo.host ?: "Unknown IP",
            selfTests = emptyList(), // ReceiverDeviceInfo doesn't include self tests
            firmwareVersion = reachInfo.firmware,
            serialNumber = reachInfo.serial,
            model = reachInfo.model,
            uptime = reachInfo.uptimeS?.let { "${it}s" }, // Convert seconds to string
            temperature = null, // Not available in ReceiverDeviceInfo
            batteryLevel = null // Battery is shown separately (ReceiverBattery)
        )
    }
}
