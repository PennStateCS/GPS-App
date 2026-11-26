package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.SurRealApplication
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.AndroidMockLocationPublisher
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.selection.ApplyProfileResult
import app.surrealar.receiver.selection.ConnectStart
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.ReceiverProbe
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.SocketReceiverProbe
import app.surrealar.ui.settings.AntennaHeightInput
import app.surrealar.ui.settings.common.inputLayoutOf
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.settingsWarn
import app.surrealar.ui.settings.common.showSettingsMessage
import app.surrealar.ui.settings.receiver.ReceiverCardBinder
import app.surrealar.ui.settings.receiver.ReceiverCardPresenter
import app.surrealar.ui.settings.receiver.ReceiverConfirmDialog
import app.surrealar.ui.settings.receiver.ReceiverDetailsSheet
import app.surrealar.ui.settings.receiver.ReceiverPickerSheet
import app.surrealar.ui.settings.receiver.ReceiverUi
import app.surrealar.ui.settings.saveWhileTyping
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Settings → Receiver (Phase 5 layout): the receiver card at the top (location source, receiver name and model with
 * where it comes from, connection state, fix + satellites, battery when reported, profile-mismatch banner,
 * "Change receiver" and "Receiver details"), then receiver setup (RS4 / RS4 Pro compensated NMEA output, pole height)
 * or the tablet antenna height (tablet GNSS), then a collapsible "Advanced" card (camera offset, phase-centre override,
 * mock location publishing, troubleshooting).
 *
 * Connecting, switching and identification all live in the app-scoped [ReceiverConnectionController] (test before
 * switch). Host / port / profile / Connect and the source radio moved to the picker sheet ([ReceiverPickerSheet]) and
 * the manual dialog; this controller only shows the core's state and opens those sheets. Scopes:
 * - category-bound collectors and loads run on `categoryScope` (stop when the category is left);
 * - writes run on [saveScope]; UI touched after a write checks that its view is still shown ([isShown]);
 * - connect attempts are app-scoped and survive this screen; their state shows on the CURRENT view only.
 *
 * The fragment keeps ONE instance for its lifetime, so the single automatic reconnect of the saved receiver (first
 * visit, External saved) happens once per Settings screen, as before.
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

    /** The selection core this screen shows (the sheets resolve the same singleton through ReceiverUiDeps). */
    internal val connection: ReceiverConnectionController = receiverConnection
        ?: ReceiverConnectionController(settingsRepo, gnssSourceCoordinator, saveScope, connectProbeOverride ?: SocketReceiverProbe)

    /** This category's view while it is shown (set after it is built); null otherwise. */
    private var contentView: View? = null

    /** The one automatic reconnect of the saved receiver per controller (as before Phase 5). */
    private var autoReconnectAttempted = false
    /** Set by any receiver action of the user on this screen: an automatic reconnect never overrides it. */
    private var userActed = false
    /** Last source the category list was refreshed for. */
    private var lastSource: LocationSourceType? = null

    companion object {
        /** The "Advanced" card is expanded: remembered for the app session (process), not persisted. */
        @Volatile internal var advancedExpanded: Boolean = false
    }

    override fun onDestroyView() {
        // The views are gone: drop them. categoryScope is already cancelled by the host; a connect attempt is
        // app-scoped (ReceiverConnectionController) and keeps running.
        contentView = null
        lastSource = null
    }

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

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_location, container, false)
        try { setupReceiverCard(view, categoryScope) } catch (e: Exception) { settingsError("Settings section setup failed section=receiverCard", e) }
        try { setupLocationSourceUi(view, categoryScope) } catch (e: Exception) { settingsError("Settings section setup failed section=locationSource", e) }
        try { setupAdvancedSection(view) } catch (e: Exception) { settingsError("Settings section setup failed section=advanced", e) }
        contentView = view
        return view
    }

    // ───────────────────────────── Receiver card ─────────────────────────────
    private fun setupReceiverCard(view: View, categoryScope: CoroutineScope) {
        val card = ReceiverCardBinder(view.findViewById(R.id.receiver_card))
        card.change.setOnClickListener { userActed = true; openSheet({ ReceiverPickerSheet() }, ReceiverPickerSheet.TAG) }
        card.details.setOnClickListener { openSheet({ ReceiverDetailsSheet() }, ReceiverDetailsSheet.TAG) }
        card.retry.setOnClickListener {
            userActed = true
            fragment.lifecycleScope.launch {
                when (connection.reconnectSaved()) {
                    ConnectStart.NEEDS_CAPTURE_CONFIRMATION ->
                        ReceiverConfirmDialog.show(fragment, ReceiverConfirmDialog.Action.RECONNECT_SAVED)
                    // L10: like the automatic reconnect, the receiver path needs the location service.
                    ConnectStart.STARTED -> ReceiverUi.ensureLocationService(fragment.requireContext().applicationContext)
                    else -> Unit
                }
            }
        }
        card.mismatchKeep.setOnClickListener { connection.keepCurrentProfile() }
        card.mismatchUse.setOnClickListener {
            if (connection.applyReportedProfile() == ApplyProfileResult.NEEDS_CAPTURE_CONFIRMATION) {
                ReceiverConfirmDialog.show(fragment, ReceiverConfirmDialog.Action.APPLY_PROFILE,
                    R.string.receiver_profile_confirm_message, R.string.receiver_profile_confirm_change)
            }
        }
        categoryScope.launch {
            combine(connection.state, fixSwitchboard.currentFix, fixSwitchboard.sky,
                receiverDevices.battery, receiverDevices.profileCapabilities) { s, fix, sky, battery, caps ->
                s to ReceiverCardPresenter.present(s, fix, sky, battery, caps)
            }.collect { (s, model) ->
                try {
                    card.bind(model)
                    applySource(view, s)
                } catch (e: Exception) {
                    settingsWarn("Receiver card update failed", e, DiagTags.RECEIVER)
                }
            }
        }
        // Sync the runtime provider with the saved source and reconnect the saved receiver once (automatic: never
        // prompts, never replaces an attempt in progress or a choice the user made on this screen).
        categoryScope.launch {
            val source = try { settingsRepo.locationSource.first() } catch (e: Exception) {
                settingsError("Settings load failed section=locationSource", e); return@launch
            }
            if (source != LocationSourceType.EXTERNAL) {
                gnssSourceCoordinator.activateInternalProvider("settings")
            } else if (!autoReconnectAttempted && !userActed && !connection.state.value.busy) {
                val start = connection.reconnectSaved()
                if (start != ConnectStart.NOTHING_SAVED) autoReconnectAttempted = true
                if (start == ConnectStart.STARTED) ReceiverUi.ensureLocationService(fragment.requireContext().applicationContext)
            }
        }
    }

    /** M5: one sheet per tag; showNow commits synchronously, so a double tap finds the first sheet. */
    private fun openSheet(create: () -> androidx.fragment.app.DialogFragment, tag: String) {
        if (!fragment.isAdded) return
        ReceiverPickerSheet.showOnce(fragment.childFragmentManager, tag, create)
    }

    /** Shows the sections for the selected source (external: tilt + antenna; tablet: tablet antenna height). */
    private fun applySource(view: View, s: ReceiverSelectionState) {
        val external = s.selectedSource == LocationSourceType.EXTERNAL
        view.findViewById<View>(R.id.card_external_receiver_setup)?.isVisible = external
        view.findViewById<View>(R.id.group_advanced_external)?.isVisible = external
        view.findViewById<View>(R.id.group_internal_gps)?.isVisible = !external
        // Tilt (compensated output + status) only for receivers with an IMU (RS4 / RS4 Pro).
        view.findViewById<View>(R.id.group_receiver_tilt)?.isVisible =
            tiltMonitor != null && app.surrealar.receiver.tilt.PositionReferencePolicy.appliesTo(s.profile)
        // The mock-location switch follows the STORED source and setting (L11), not this state, whose defaults
        // (tablet GNSS) show before the selection core has loaded: see setupLocationSourceUi.
        if (lastSource != s.selectedSource) {
            lastSource = s.selectedSource
            refreshCategoriesForSource(s.selectedSource)
        }
    }

    // ───────────────────────────── Advanced (collapsible) ─────────────────────
    /**
     * The "Advanced" card: camera offset and phase-centre override (external receiver), mock location publishing and
     * troubleshooting. Collapsed by default; the state is remembered for the app session ([advancedExpanded]). A mock
     * location error expands it so the banner is never hidden. The RS4 compensated-output setting is never in here.
     */
    private fun setupAdvancedSection(view: View) {
        val toggle = view.findViewById<View>(R.id.btn_receiver_advanced_toggle) ?: return
        toggle.setOnClickListener { setAdvancedExpanded(view, !advancedExpanded) }
        setAdvancedExpanded(view, advancedExpanded)
        view.findViewById<View>(R.id.btn_receiver_open_diagnostics)?.setOnClickListener {
            runCatching {
                androidx.navigation.fragment.NavHostFragment.findNavController(fragment).navigate(R.id.nav_development)
            }.onFailure {
                settingsWarn("Receiver settings: diagnostics page navigation failed", it)
                android.widget.Toast.makeText(fragment.requireContext(), R.string.details_diagnostics_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setAdvancedExpanded(view: View, expanded: Boolean) {
        advancedExpanded = expanded
        view.findViewById<View>(R.id.group_receiver_advanced)?.isVisible = expanded
        view.findViewById<View>(R.id.image_receiver_advanced_chevron)?.rotation = if (expanded) 270f else 90f
        view.findViewById<View>(R.id.btn_receiver_advanced_toggle)?.contentDescription = fragment.getString(
            if (expanded) R.string.receiver_advanced_collapse_cd else R.string.receiver_advanced_expand_cd)
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

    // ───────────────────────────── Antenna / mock location ─────────────────────────
    private fun setupLocationSourceUi(view: View, categoryScope: CoroutineScope) {
        val editAntennaHeight = view.findViewById<EditText>(R.id.edit_antenna_height)
        val editInternalAntennaHeight = view.findViewById<EditText>(R.id.edit_internal_antenna_height)

        setupTiltUi(view, categoryScope)

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

        // L11: the switch shows the stored setting and is usable only while the STORED source is External. It reacts
        // to loaded values only (never to the selection state's not-yet-loaded defaults); programmatic updates do not
        // write back. A stored "on" with tablet GNSS is saved off, as before (mock publishing needs a receiver).
        var updatingMock = false
        categoryScope.launch {
            try {
                combine(settingsRepo.locationSource, settingsRepo.mockLocationEnabled) { source, enabled -> source to enabled }
                    .collect { (source, enabled) ->
                        val external = source == LocationSourceType.EXTERNAL
                        switchMock?.isEnabled = external
                        if (switchMock != null && switchMock.isChecked != enabled) {
                            updatingMock = true
                            try { switchMock.isChecked = enabled } finally { updatingMock = false }
                        }
                        if (!external && enabled) saveScope.launch {
                            try { settingsRepo.setMockLocationEnabled(false) } catch (e: Exception) {
                                settingsError("Settings save failed section=mockLocation", e)
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsError("Settings load failed section=mockLocation", e)
            }
        }

        switchMock?.setOnCheckedChangeListener { _, checked ->
            if (updatingMock) return@setOnCheckedChangeListener
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
                        if (isShown(view)) setAdvancedExpanded(view, true)   // never hide the error in a collapsed section
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
    }
}
