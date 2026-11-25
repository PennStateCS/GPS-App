package app.surrealar.ui.settings.categories

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.SurRealApplication
import app.surrealar.domain.model.EmlidDeviceInfo
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.SelfTest
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.AndroidMockLocationPublisher
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.selection.ConnectRequest
import app.surrealar.receiver.selection.ConnectStart
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.ReceiverProbe
import app.surrealar.receiver.selection.SocketReceiverProbe
import app.surrealar.receiver.selection.TcpProbeResult
import app.surrealar.service.LocationService
import app.surrealar.settings.SettingsDefaults
import app.surrealar.ui.settings.AntennaHeightInput
import app.surrealar.ui.settings.SelfTestDisplay
import app.surrealar.ui.settings.common.inputLayoutOf
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.settingsWarn
import app.surrealar.ui.settings.common.showSettingsMessage
import app.surrealar.ui.settings.saveWhileTyping
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.ReachNameResolver
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.snackbar.Snackbar
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

/**
 * Settings → Receiver: GNSS source (device GNSS / external receiver), external receiver profile,
 * TCP connect / disconnect, live receiver status, device info and self tests, antenna setup and mock
 * location publishing.
 *
 * Moved verbatim from SettingsFragment. The fragment keeps ONE instance for its lifetime, so the
 * screen state below (selected device, the single auto-reconnect) survives a category switch exactly as
 * it did as fragment fields. Scopes:
 * - category-bound collectors, the 1 s status refresh, and the stored-settings load / prefill (which
 *   bind this view) run on `categoryScope` (stop when the category is left);
 * - Phase 5: connect / disconnect / device-GNSS switches are delegated to the app-scoped
 *   [ReceiverConnectionController] (test before switch: nothing is saved or activated unless the probe
 *   receives NMEA; Emlid HTTP is asked for Emlid profiles only). Its attempts survive this screen; their
 *   outcome is shown on the CURRENT Receiver view only (collected on `categoryScope`);
 * - the diagnostic probe (name lookup only) runs on the fragment's lifecycleScope;
 * - writes run on [saveScope]; UI touched after a write checks that its view is still shown ([isShown]).
 */
internal class ReceiverSettingsController(
    private val fragment: Fragment,
    private val settingsRepo: SettingsRepository,
    private val fixSwitchboard: FixSwitchboard,
    private val gnssSourceCoordinator: GnssSourceCoordinator,
    private val receiverDevices: ReceiverDeviceManager,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
    /** The host's category list refresh when the source changes (SettingsFragment.refreshCategoriesForSource). */
    private val refreshCategoriesForSource: (LocationSourceType) -> Unit,
    /**
     * Test seam for the connect probe (TCP NMEA read; HTTP reachability for Emlid profiles) used when no
     * [receiverConnection] is given; null = the real probes. Lets tests hold an attempt "in flight".
     */
    connectProbeOverride: ReceiverProbe? = null,
    /** RS4 / RS4 Pro tilt status (Phase 4 WP C); null = the tilt rows stay hidden (tests that predate it). */
    private val tiltMonitor: app.surrealar.receiver.tilt.ReceiverTiltMonitor? = null,
    /**
     * Phase 5: the app-scoped receiver selection core (Hilt singleton in production). Null (tests) = one
     * owned by this controller, over [saveScope], with [connectProbeOverride].
     */
    receiverConnection: ReceiverConnectionController? = null,
) : SettingsCategoryController {

    private val connection: ReceiverConnectionController = receiverConnection
        ?: ReceiverConnectionController(settingsRepo, gnssSourceCoordinator, saveScope, connectProbeOverride ?: SocketReceiverProbe)

    companion object {
        // Connection status timing thresholds (ms)
        const val FRESH_FIX_MAX_AGE_MS = 5_000L
        const val STALE_FIX_MAX_AGE_MS = 15_000L
    }

    /** This category's view while it is shown (set after it is built, as SettingsFragment.currentContentView was); null otherwise. */
    private var contentView: View? = null

    // Selected device for TCP connection (class scope)
    private var selectedDevice: Pair<String, Int>? = null
    private var selectedDeviceLabel: String? = null
    private var selectedDeviceName: String? = null
    private var autoReconnectAttempted = false // new flag
    private var provisionalConnectedUntil: Long = 0L // provisional connected status timeout

    // Emlid device information
    private var currentDeviceInfo: EmlidDeviceInfo? = null

    private var deviceStatusJob: Job? = null
    /**
     * Advanced by Disconnect / selecting device GNSS, so an auto-reconnect whose stored-source read was
     * in flight then starts nothing.
     */
    private var sourceChangeGeneration = 0
    /** Last connect attempt whose outcome this screen showed (an outcome is shown once, not on every revisit). */
    private var lastShownAttemptId = 0
    /** True when the receiver's HTTP /info or /status endpoint responded successfully. */
    @Volatile private var receiverHttpReachable = false
    /** Epoch ms of last external fix accepted for status; 0 means none yet. */
    @Volatile private var lastExternalFixTimeMs: Long = 0L
    /** RTK status of the last accepted external fix. */
    @Volatile private var lastExternalFixRtkStatus: RtkStatus? = null
    /** Epoch ms of last raw NMEA sentence from the external adapter; 0 means none. */
    @Volatile private var lastExternalRawNmeaTimeMs: Long = 0L

    override fun onDestroyView() {
        // The views are gone: drop them. categoryScope (and with it deviceStatusJob) is already
        // cancelled by the host; a connect attempt is app-scoped (ReceiverConnectionController) and keeps running.
        contentView = null
        deviceStatusJob?.cancel()
        deviceStatusJob = null
    }

    // UI status for external connection
    private enum class ConnectionUiStatus { CONNECTING, REACHABLE, STREAMING, STALE, DISCONNECTED }

    private fun showAntennaHeightError(field: View) {
        val msg = "Enter a height in metres (0 – ${AntennaHeightInput.MAX_M.toInt()}). Previous value kept."
        val layout = inputLayoutOf(field)
        if (layout != null) layout.error = msg
        else android.widget.Toast.makeText(fragment.requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun clearAntennaHeightError(field: View) {
        inputLayoutOf(field)?.error = null
    }

    /** True while [view] is the Receiver view on screen (async work started for an older view skips its UI). */
    private fun isShown(view: View): Boolean = contentView === view

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
            val root = contentView ?: return
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

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_location, container, false)
        try { setupLocationSourceUi(view, categoryScope) } catch (e: Exception) { settingsError("Settings section setup failed section=locationSource", e) }
        // Set once built, like SettingsFragment.currentContentView: status updates fired while the
        // view is being set up still go nowhere, as before.
        contentView = view
        return view
    }

    // ───────────────────────────── RS4 / RS4 Pro tilt ─────────────────────────
    /**
     * "Compensated NMEA output (Emlid Flow)" selector and the live tilt / position-reference status (Phase 4 WP C).
     * Status only: nothing here changes a position.
     */
    private fun setupTiltUi(view: View, categoryScope: CoroutineScope) {
        val monitor = tiltMonitor ?: return
        val dropdown = view.findViewById<AutoCompleteTextView>(R.id.dropdown_compensated_output)
        val statusText = view.findViewById<TextView>(R.id.text_receiver_tilt_status)
        val options = app.surrealar.settings.model.CompensatedOutputSetting.entries
        dropdown?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, options.map { it.label }))
        categoryScope.launch {
            val current = runCatching { settingsRepo.compensatedNmeaOutput.first() }
                .getOrDefault(app.surrealar.settings.model.CompensatedOutputSetting.DEFAULT)
            dropdown?.setText(current.label, false)
        }
        dropdown?.setOnItemClickListener { _, _, position, _ ->
            val chosen = options.getOrNull(position) ?: return@setOnItemClickListener
            saveScope.launch {
                runCatching { settingsRepo.setCompensatedNmeaOutput(chosen) }
                    .onSuccess { DiagnosticsLogger.i(DiagTags.RECEIVER, "Compensated NMEA output set value=${chosen.prefKey}") }
                    .onFailure { settingsError("Settings save failed section=compensatedOutput", it) }
            }
        }
        categoryScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                monitor.status.collect { st ->
                    statusText?.text = buildString {
                        append(app.surrealar.receiver.tilt.TiltStatusText.stateLine(st))
                        app.surrealar.receiver.tilt.TiltStatusText.warningLine(st)?.let { append("\n").append(it) }
                        append("\n").append(app.surrealar.receiver.tilt.TiltStatusText.referenceLine(st))
                        if (st.compensatedOutput == app.surrealar.settings.model.CompensatedOutputSetting.ENABLED &&
                            !io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference.firmwareAtLeast(
                                st.firmware, io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference.MIN_COMPENSATED_FIRMWARE)) {
                            append("\nFirmware: ${st.firmware ?: "not known yet"} (compensated output needs 34.7 or later).")
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────────── Location Source UI ─────────────────────────
    private fun setupLocationSourceUi(view: View, categoryScope: CoroutineScope) {
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
                ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, profiles.map { it.label })
            )

            val tiltGroup = view.findViewById<View>(R.id.group_receiver_tilt)
            fun applyHint(profile: app.surrealar.settings.model.ExternalReceiverProfile) {
                val baseHelp = "Choose the receiver model so the app can use the right labels, " +
                    "defaults, and diagnostics. Position data still comes from NMEA over TCP."
                profileHint?.text = profile.hint?.let { "$baseHelp\n\n$it" } ?: baseHelp
                // Tilt (compensated output + status) only for receivers with an IMU (RS4 / RS4 Pro).
                tiltGroup?.isVisible = tiltMonitor != null && app.surrealar.receiver.tilt.PositionReferencePolicy.appliesTo(profile)
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
                        if (!isShown(view)) return@launch
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

        setupTiltUi(view, categoryScope)

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
                fragment.showSettingsMessage("Device info not available. Ensure external connection is active.")
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
            fragment.lifecycleScope.launch {
                try {
                    val (result, _) = withContext(Dispatchers.IO) { testRs2Tcp(host, port) }
                    if (selectedDeviceName == null || selectedDeviceName == host) {
                        launch {
                            try {
                                val resolved = ReachNameResolver.resolveReachName(fragment.requireContext(), host)
                                if (!resolved.isNullOrBlank() && fragment.isAdded && currentDeviceInfo == null) {
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
                val focusedView = fragment.activity?.currentFocus
                if (focusedView != null) {
                    val imm = fragment.requireContext().getSystemService(InputMethodManager::class.java)
                    imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
                    focusedView.clearFocus()
                }
                val host = editHost?.text?.toString()?.trim().orEmpty()
                val port = SettingsDefaults.sanitizeTcpPort(editPort?.text?.toString()?.trim()?.toIntOrNull())
                if (host.isNotEmpty()) {
                    selectedDevice = host to port
                    selectedDeviceLabel = fragment.getString(R.string.host_port, host, port)
                    selectedDeviceName = host
                    provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                    updateDeviceBox()
                    runDiagnostic(host, port)
                    connectViaTcpFlow(host, port)
                } else {
                    fragment.showSettingsMessage(fragment.getString(R.string.host_required))
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
            // Cancels any in-flight connect attempt, switches the switchboard to device GNSS immediately
            // (synchronous) and saves mock off + source Internal + cleared host/port (ReceiverConnectionController).
            ++sourceChangeGeneration
            resetExternalReceiverStatus()
            connection.disconnect("settings")
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
        }

        // Phase 5: the outcome of a connect attempt (app-scoped) on THIS view; each outcome is shown once.
        categoryScope.launch {
            connection.state.map { it.attempt }.distinctUntilChanged().collect { attempt ->
                try { showAttempt(attempt) } catch (e: Exception) { settingsWarn("External receiver attempt display failed", e, DiagTags.RECEIVER) }
            }
        }

        // Prefill inline inputs with last used values. The host is left blank on first use (no
        // hardcoded device address); with no saved port, use the selected receiver profile's default
        // (Reach = 9001). Nothing is persisted here — settings are only saved when the user connects.
        // categoryScope: it fills THIS view, so it must not land on it after the category was left.
        categoryScope.launch {
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
                        // Disconnect / selecting device GNSS advance the attempt id; if either happens
                        // while the stored source is being read, don't start a new External attempt.
                        val generationBefore = sourceChangeGeneration
                        launch {
                            val locSrc = settingsRepo.locationSource.first()
                            val connType = settingsRepo.externalConnType.firstOrNull()
                            if (autoReconnectAttempted || generationBefore != sourceChangeGeneration) return@launch
                            if (locSrc == LocationSourceType.EXTERNAL && connType == ExternalConnectionType.TCP) {
                                autoReconnectAttempted = true
                                connectViaTcpFlow(host, port, userInitiated = false)
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
                tv?.setTextColor(ContextCompat.getColor(fragment.requireContext(), colorRes))
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

                val ctx = fragment.context ?: run { updateDeviceBox(); return }
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
                fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            if (!isShown(view)) return@launch
                            // Bad input: keep the stored value, say so, and restore the field.
                            showAntennaHeightError(this@apply)
                            setText(AntennaHeightInput.format(current.poleHeightM))
                        } else {
                            // Changing the value answers the one-time "check your pole height" notice.
                            settingsRepo.setGnssReceiverSettings(current.copy(
                                poleHeightM = meters,
                                poleHeightNeedsCheck = current.poleHeightNeedsCheck && meters == current.poleHeightM,
                            ))
                            if (isShown(view)) clearAntennaHeightError(this@apply)
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
                            if (!isShown(view)) return@launch
                            inputLayoutOf(this@apply)?.error = "Enter −3 to 3 m. Previous value kept."
                            setText(AntennaHeightInput.format(current.cameraBelowReceiverM))
                        } else {
                            settingsRepo.setGnssReceiverSettings(current.copy(cameraBelowReceiverM = meters))
                            if (isShown(view)) clearAntennaHeightError(this@apply)
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
                            if (!isShown(view)) return@launch
                            inputLayoutOf(this@apply)?.error = "Enter 0 to ${AntennaHeightInput.format(app.surrealar.settings.model.AntennaSetup.MAX_PHASE_CENTER_OFFSET_M)} m. Previous value kept."
                            setText(AntennaHeightInput.format(app.surrealar.settings.model.AntennaSetup.resolve(current, profile).phaseCenterOffsetM))
                        } else {
                            // Matching the profile's value clears the override, so a later profile change applies.
                            val override = if (profile.phaseCenterOffsetM != null &&
                                kotlin.math.abs(meters - profile.phaseCenterOffsetM) < 0.0005) null else meters
                            settingsRepo.setGnssReceiverSettings(current.copy(phaseCenterOffsetOverrideM = override))
                            if (isShown(view)) clearAntennaHeightError(this@apply)
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
                            if (!isShown(view)) return@launch
                            showAntennaHeightError(this@apply)
                            setText(AntennaHeightInput.format(current.internalAntennaHeightM))
                        } else {
                            settingsRepo.setGnssReceiverSettings(current.copy(internalAntennaHeightM = meters))
                            if (isShown(view)) clearAntennaHeightError(this@apply)
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
                    if (!checked && isShown(view)) errorBanner?.visibility = View.GONE
                } catch (e: Exception) {
                    settingsError("Settings save failed section=mockLocation", e)
                }
            }
        }

        btnDevOpts?.setOnClickListener {
            try {
                fragment.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                try {
                    fragment.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (ex: Exception) {
                    settingsWarn("Developer options screen open failed", ex)
                }
            }
        }

        categoryScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            fragment.showSettingsMessage(msg, Snackbar.LENGTH_LONG)
                        }
                    }
                } catch (e: Exception) {
                    settingsError("Mock location error observer failed", e)
                }
            }
        }

        var initializing = true
        // categoryScope (it binds THIS view): on the fragment's lifecycleScope it ran on after the
        // category or the fragment view was gone, and its auto-reconnect then called
        // connectViaTcpFlow -> fragment.viewLifecycleOwner, which throws once the fragment view is
        // destroyed. Left before it finished, it runs again (incl. the one auto-reconnect) on the
        // next visit; the attempt itself, once started, still survives a category switch.
        categoryScope.launch {
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
            // The collect observer may fire before contentView is set, causing
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
                connectViaTcpFlow(storedHost, storedPort, userInitiated = false)
            }
            initializing = false
        }

        radioGroup?.setOnCheckedChangeListener { group, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            // RadioGroup.check() (used by Disconnect) also reports the button it is UNchecking, before
            // the new one: that callback ran the External branch (persisted source EXTERNAL, re-enabled
            // mock, could start a connect attempt) just before the Internal branch undid it. Ignore it.
            if (group.findViewById<RadioButton>(checkedId)?.isChecked == false) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.radio_internal -> {
                    // Cancels any in-progress connect attempt, makes device GNSS active now and saves mock off +
                    // source Internal (ReceiverConnectionController, which logs the switch).
                    ++sourceChangeGeneration
                    resetExternalReceiverStatus()
                    connection.selectInternal("settings")
                    // Update categories immediately when switching to internal (hide RS2+)
                    refreshCategoriesForSource(LocationSourceType.INTERNAL)
                    updateLocationSourceVisibility(LocationSourceType.INTERNAL, internalGpsGroup)
                    rs2OptionsLayout?.visibility = View.GONE
                    provisionalConnectedUntil = 0L
                    updateDeviceBox()
                    // Immediately disable mock publishing and update its switch UI
                    switchMock?.isChecked = false
                    switchMock?.isEnabled = false
                    if (!LocationService.isRunning) LocationService.start(fragment.requireContext())
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
                    // Phase 5 (test before switch): with a known receiver, External is saved only when it
                    // streams NMEA (the attempt saves conn type, host/port and source, then activates).
                    // Without one, only the source choice is saved so the entry form shows.
                    val dev = selectedDevice
                    if (dev != null) {
                        selectedDeviceName = selectedDeviceName ?: dev.first
                        updateDeviceBox()
                        connectViaTcpFlow(dev.first, dev.second)
                    } else {
                        connection.selectExternalWithoutReceiver()
                    }
                    if (!LocationService.isRunning) LocationService.start(fragment.requireContext())
                }
            }
        }
    }

    // ───────────────────────────── Source Visibility ───────────────────────────
    private fun updateLocationSourceVisibility(selected: LocationSourceType, internal: LinearLayout?) {
        internal?.visibility = if (selected == LocationSourceType.INTERNAL) View.VISIBLE else View.GONE
    }

    // ─────────────────────────── connect (delegated) ────────────────────────────
    /**
     * Starts a test-before-switch attempt in the app-scoped [connection] (it survives this screen; its outcome
     * is shown by [showAttempt]). [userInitiated] = the user asked for it: a running capture is confirmed
     * first; automatic reconnects never prompt and are skipped instead.
     */
    private fun connectViaTcpFlow(host: String, port: Int, userInitiated: Boolean = true) {
        when (connection.connect(ConnectRequest(host, port))) {
            ConnectStart.NEEDS_CAPTURE_CONFIRMATION -> if (userInitiated) confirmCaptureStop {
                connection.connect(ConnectRequest(host, port), confirmCaptureStop = true)
            }
            ConnectStart.INVALID -> if (userInitiated) fragment.showSettingsMessage(fragment.getString(R.string.host_required))
            ConnectStart.ALREADY_ACTIVE -> provisionalConnectedUntil = 0L
            else -> Unit
        }
    }

    /** "Switching receivers stops the current capture" (Phase 5); [onConfirm] runs on Switch. */
    private fun confirmCaptureStop(onConfirm: () -> Unit) {
        if (!fragment.isAdded) return
        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Stop the current capture?")
            .setMessage("Switching receivers stops the current capture. Nothing measured so far is saved.")
            .setPositiveButton("Switch receiver") { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows [attempt] on the current Receiver view; a finished attempt's outcome is shown once. */
    private fun showAttempt(attempt: ConnectionAttempt) {
        val root = contentView ?: return
        val ctx = fragment.context ?: return
        val statusTv = root.findViewById<TextView>(R.id.text_device_status)
        val subTv = root.findViewById<TextView>(R.id.text_device_status_sub)
        fun show(main: String, sub: String?, colorRes: Int) {
            statusTv?.visibility = View.VISIBLE
            statusTv?.text = main
            statusTv?.setTextColor(ContextCompat.getColor(ctx, colorRes))
            if (sub.isNullOrEmpty()) subTv?.visibility = View.GONE
            else { subTv?.text = sub; subTv?.visibility = View.VISIBLE }
        }
        when (attempt) {
            is ConnectionAttempt.Testing -> if (attempt.id > lastShownAttemptId) {
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                show("Connecting…", null, android.R.color.holo_orange_dark)
            }
            is ConnectionAttempt.Succeeded -> if (attempt.id > lastShownAttemptId) {
                lastShownAttemptId = attempt.id
                receiverHttpReachable = attempt.httpReachable == true
                provisionalConnectedUntil = System.currentTimeMillis() + 8000L
                selectedDevice = attempt.target.host to attempt.target.port
                updateDeviceBox()
                show("Receiver connected", "Receiving NMEA", android.R.color.holo_green_dark)
                resolveNameAfterConnect(attempt.target.host, attempt.target.port, root)
            }
            is ConnectionAttempt.Failed -> if (attempt.id > lastShownAttemptId) {
                lastShownAttemptId = attempt.id
                provisionalConnectedUntil = 0L
                // Nothing was saved: the device box goes back to the saved receiver (if any).
                val saved = connection.state.value.savedTarget
                if (selectedDevice != saved?.let { it.host to it.port }) {
                    selectedDevice = saved?.let { it.host to it.port }
                    selectedDeviceLabel = saved?.toString()
                    selectedDeviceName = saved?.host
                    updateDeviceBox()
                }
                show("Receiver unavailable", attempt.error.message, android.R.color.holo_red_dark)
                fragment.showSettingsMessage(attempt.error.message + " Nothing was changed.", Snackbar.LENGTH_LONG)
            }
            is ConnectionAttempt.Switching, ConnectionAttempt.Idle -> Unit
        }
    }

    /** After a successful connect: the receiver's mDNS name (no HTTP request) as its display name, as before. */
    private fun resolveNameAfterConnect(host: String, port: Int, view: View) {
        if (selectedDeviceName != null && selectedDeviceName != host) return
        fragment.lifecycleScope.launch {
            try {
                val resolved = ReachNameResolver.resolveReachName(fragment.requireContext(), host)
                if (!resolved.isNullOrBlank() && fragment.isAdded && currentDeviceInfo == null && isShown(view)) {
                    selectedDeviceName = resolved
                    saveScope.launch {
                        runCatching { settingsRepo.setExternalTcp(host, port, resolved) }
                            .onFailure { settingsError("Settings save failed section=receiverName", it) }
                    }
                    updateDeviceBox()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsWarn("External receiver name lookup failed", e, DiagTags.RECEIVER)
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

    private fun testRs2Tcp(host: String, port: Int): Pair<TcpProbeResult, ByteArray?> {
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
            if (data.isEmpty()) return TcpProbeResult.CONNECTED_NO_DATA to null
            // Heuristics: NMEA if an ASCII '$' starts a line
            val ascii = runCatching { data.toString(Charsets.US_ASCII) }.getOrNull()
            if (ascii != null) {
                val hasNmea = ascii.lineSequence().any { it.startsWith("$") }
                if (hasNmea) return TcpProbeResult.RECEIVING_NMEA to data
            }
            TcpProbeResult.RECEIVING_RTCM_OR_BIN to data
        } catch (_: Exception) {
            TcpProbeResult.CONNECT_FAILED to null
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

        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Emlid Device Information")
            .setMessage(message.trim())
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy Info") { _, _ ->
                val clipboard = fragment.requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Emlid Device Info", message.trim())
                clipboard.setPrimaryClip(clip)
                fragment.showSettingsMessage("Device info copied to clipboard")
            }
            .show()
    }

    // ───────────────────────────── Device Information Update Functions ─────────────────────────────
    private fun updateDeviceInfoDisplay() {
        try {
            val root = contentView ?: return
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
                try { textSelfTestsSummary?.setTextColor(ContextCompat.getColor(fragment.requireContext(), summaryColor)) } catch (_: Exception) {}
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
            val context = fragment.requireContext()
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

    private fun dpToPx(dp: Int): Int = app.surrealar.ui.settings.common.dpToPx(fragment.requireContext(), dp)

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
