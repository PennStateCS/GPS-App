package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.saveWhileTyping
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Settings → Coordinates: the Coordinate System card (system, State Plane zone, datum, units, height,
 * receiver datum, the "Modernized NSRS 2022 (beta preview)" opt-in, live preview) and the coordinate
 * display preferences (accuracy indicators, name prefix, auto-increment).
 */
internal class CoordinatesSettingsController(
    private val fragment: Fragment,
    private val settingsRepo: CoordinateDisplaySettingsRepository,
    private val fixSwitchboard: FixSwitchboard,
    private val coordinateSystemService: CoordinateSystemService,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
    /**
     * CRS registry for the SPCS2022 catalog and zone entries, by preview state (Phase 4 WP X: geokit's default
     * with the preview OFF, the NSRS 2022 beta registry with it ON; tests inject a stub).
     */
    private val crsRegistry: (preview: Boolean) -> io.github.pennstatecs.geokit.crs.CrsRegistry =
        { app.surrealar.coords.Nsrs2022Registries.crs(it) },
    /** NSRS 2022 product list for a registry (frames: the registry's own frame registry; tests inject stubs). */
    private val capabilities: (io.github.pennstatecs.geokit.crs.CrsRegistry) -> List<app.surrealar.coords.Nsrs2022Capability> =
        { app.surrealar.coords.Nsrs2022Capabilities.all(frames = it.frameRegistry, crs = it) },
    /** Where the SPCS2022 catalog is parsed: never the main thread. */
    private val statusDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_coordinate_display, container, false)
        try { setupCoordinateSystemCard(view, categoryScope) } catch (e: Exception) { settingsError("Settings section setup failed section=coordinateSystem", e) }
        try {
            val switchAccuracy  = view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators)
            val editPrefix      = view.findViewById<EditText>(R.id.edit_coordinate_name_prefix)
            val switchAutoInc   = view.findViewById<MaterialSwitch>(R.id.switch_auto_increment_names)


            val tilPrefix = editPrefix?.parent?.parent as? TextInputLayout

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

            // Only the switch that changed is written, on top of the stored settings: a switch flipped before
            // the first bind never writes the other switch's unbound default.
            fun saveSwitch(edit: (app.surrealar.settings.model.CoordinateDisplaySettings) -> app.surrealar.settings.model.CoordinateDisplaySettings) {
                saveScope.launch {
                    try {
                        val s = settingsRepo.coordinateDisplaySettings.first()
                        settingsRepo.setCoordinateDisplaySettings(edit(s))
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
                if (!isBinding) saveSwitch { it.copy(showAccuracyIndicators = v) }
            }
            switchAutoInc?.setOnCheckedChangeListener { _, v ->
                if (!isBinding) saveSwitch { it.copy(autoIncrementNames = v) }
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

    /**
     * NSRS 2022 product status and SPCS2022 zone entries for one preview state's registry, computed once per
     * screen and state off the main thread.
     */
    internal class NsrsStatus(
        val capabilities: List<app.surrealar.coords.Nsrs2022Capability>,
        val spcs2022Note: String?,
        /** Every operational SPCS2022 zone entry (preview ON: the beta registry's 953 zones). */
        val spcs2022Zones: List<ZoneOption>,
        val registry: io.github.pennstatecs.geokit.crs.CrsRegistry,
    )

    /**
     * Settings → Coordinates → Coordinate System: dropdowns for system, State Plane zone (all 118
     * SPCS83 zones, filterable), datum, units, height and external receiver datum, each saved
     * immediately, plus a live preview of the current position in the chosen system. State Plane
     * forces NAD83(2011), so the datum dropdown is disabled then.
     *
     * Saves change only the field the user touched, applied to the latest stored settings (never to the
     * screen's defaults before the first bind), one at a time. The SPCS2022 catalog (product status, zone
     * entries) is parsed off the main thread and shown when ready.
     */
    private fun setupCoordinateSystemCard(view: View, categoryScope: CoroutineScope) {
        val spinnerH = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal) ?: return
        val spinnerD = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum)
        val spinnerU = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_unit)
        val spinnerV = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_vertical)
        val spinnerR = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_rx_datum)
        val preview = view.findViewById<android.widget.TextView>(R.id.text_coord_preview)
        val zoneRow = view.findViewById<View>(R.id.row_coord_spcs_zone)
        val spinnerZ = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_spcs_zone)
        val zoneHint = view.findViewById<android.widget.TextView>(R.id.text_coord_zone_hint)
        val verticalNote = view.findViewById<android.widget.TextView>(R.id.text_coord_vertical_note)
        val horizontalNote = view.findViewById<android.widget.TextView>(R.id.text_coord_horizontal_note)
        val datumNote = view.findViewById<android.widget.TextView>(R.id.text_coord_datum_note)
        val switchPreview = view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview)
        val previewStatus = view.findViewById<android.widget.TextView>(R.id.text_coord_nsrs2022_status)
        val previewGroups = view.findViewById<android.widget.LinearLayout>(R.id.container_coord_nsrs2022_groups)
        // Zone list from geokit's CRS registry (operational SPCS83 entries; stored values stay EPSG ints).
        val zones = SpcsZoneHints.searchZones("")
        val zoneByLabel = zones.associateBy { it.label }
        val baseZoneHint = "All ${zones.size} SPCS83 zones. Type a state name to filter."
        var defaultZoneHint = baseZoneHint
        var suggestedZoneEpsg: Int? = null
        var suggestion: io.github.pennstatecs.geokit.projection.SpcsZoneSuggestion? = null
        var zoneNotice: String? = null
        // SPCS2022 (beta) zones possible at the live position, computed off the main thread when SPCS2022 is chosen.
        var spcs2022Hint: String? = null

        // Zone suggestion for the live fix. The fix is WGS84; the zone limits are NAD83 county lines, and
        // the ~1-2 m difference is far below their generalisation. Failure (e.g. missing resource) = no hint.
        fun locateZone(fix: io.github.pennstatecs.geokit.gnss.model.Fix): io.github.pennstatecs.geokit.projection.SpcsZoneSuggestion? =
            runCatching { io.github.pennstatecs.geokit.projection.SpcsZoneLocator.SPCS83.locate(fix.latDeg, fix.lonDeg) }
                .onFailure { settingsError("SPCS zone suggestion failed", it) }
                .getOrNull()

        // Is the live fix in [zone]? Same zone limits as the suggestion (geokit SpcsZoneLocator.check).
        fun checkZone(zone: io.github.pennstatecs.geokit.projection.SpcsZone,
                      fix: io.github.pennstatecs.geokit.gnss.model.Fix): io.github.pennstatecs.geokit.projection.SpcsZoneCheck? =
            runCatching { io.github.pennstatecs.geokit.projection.SpcsZoneLocator.SPCS83.check(zone, fix.latDeg, fix.lonDeg) }
                .onFailure { settingsError("SPCS zone check failed", it) }
                .getOrNull()

        // Systems and datums: beta ones (SPCS2022, the 2022 frames) only while the NSRS 2022 beta preview is on.
        var hs = app.surrealar.settings.model.CoordinateSystemSettings().selectableHorizontals
        var ds = app.surrealar.settings.model.CoordinateSystemSettings().selectableDatums
        val us = app.surrealar.settings.model.LinearUnit.entries
        // Height choices: beta ones (GEOID2022) only while the NSRS 2022 beta preview is on.
        var vs = app.surrealar.settings.model.CoordinateSystemSettings().selectableVerticals
        val rs = app.surrealar.settings.model.ReceiverDatum.entries
        fun adapter(labels: List<String>) = ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, labels)
        spinnerH.setAdapter(adapter(hs.map { it.label }))
        spinnerD?.setAdapter(adapter(ds.map { it.label }))
        spinnerU?.setAdapter(adapter(us.map { it.label }))
        spinnerV?.setAdapter(adapter(vs.map { it.menuLabel }))
        spinnerR?.setAdapter(adapter(rs.map { it.label }))

        var current = app.surrealar.settings.model.CoordinateSystemSettings()
        var bindingPreview = false
        // Product status from geokit's registries, per preview state: absent until computed off the main thread.
        val statuses = java.util.concurrent.ConcurrentHashMap<Boolean, NsrsStatus>()
        val statusFailed = java.util.concurrent.ConcurrentHashMap<Boolean, Boolean>()
        val status: () -> NsrsStatus? = { statuses[current.nsrs2022Preview] }

        // The system shown: a beta system saved while the preview is off shows the default it is replaced by.
        fun shownHorizontal(s: app.surrealar.settings.model.CoordinateSystemSettings) =
            if (s.horizontal.isBeta && !s.nsrs2022Preview) app.surrealar.settings.model.HorizontalSystem.GEOGRAPHIC else s.horizontal

        // The chosen SPCS2022 zone's entry (null until the status is computed, or for an unknown id).
        fun chosenSpcs2022(s: app.surrealar.settings.model.CoordinateSystemSettings): ZoneOption? =
            s.spcs2022Zone?.let { id -> status()?.spcs2022Zones?.firstOrNull { it.definition?.id.equals(id, ignoreCase = true) } }

        fun zoneText(s: app.surrealar.settings.model.CoordinateSystemSettings): String =
            if (shownHorizontal(s) == app.surrealar.settings.model.HorizontalSystem.SPCS2022) chosenSpcs2022(s)?.label ?: s.spcs2022Zone.orEmpty()
            else io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(s.spcsZoneEpsg)?.label.orEmpty()

        // Editable so typing filters the zones through CrsRegistry.search ("tex", "north", "3701"). SPCS2022
        // entries join only with the preview ON and once the registry reports operational zones (the beta registry).
        // The filter runs on the adapter's worker thread; the SPCS2022 catalog is already parsed by then.
        spinnerZ?.setAdapter(SearchFilterAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings,
            zones.map { it.label }) { q ->
            val st = status()
            val extra = if (current.nsrs2022Preview && st != null && st.spcs2022Zones.isNotEmpty())
                ZoneSelectorOptions.spcs2022(q, preview = true, registry = st.registry).map { it.label } else emptyList()
            SpcsZoneHints.searchZones(q).map { it.label } + extra
        })

        fun showPreview() {
            val fix = fixSwitchboard.currentFix.value
            preview?.text = if (fix == null) "Current position: waiting for GNSS" else runCatching {
                val p = coordinateSystemService.convertFix(fix, current)
                val elevation = runCatching { coordinateSystemService.elevationOfFix(fix, current) }.getOrNull()
                "Current position: ${app.surrealar.coords.CoordinateFormat.horizontal(p)} · " +
                    "${app.surrealar.coords.CoordinateFormat.heightOrLoading(p)}\n${app.surrealar.coords.CoordinateFormat.systemSummary(p)}" +
                    (elevation?.let { "\n" + app.surrealar.coords.ElevationFormat.previewLine(it) } ?: "")
            }.getOrElse { "Current position: conversion unavailable (${it.message})" }
        }

        var renderedGroupsFor: Pair<Boolean, NsrsStatus?>? = null
        fun renderStatus() {
            val on = current.nsrs2022Preview
            val st = status()
            if (renderedGroupsFor == (on to st)) return
            renderedGroupsFor = on to st
            previewGroups?.removeAllViews()
            when {
                !on -> {
                    previewStatus?.visibility = View.GONE
                    previewGroups?.visibility = View.GONE
                }
                st == null -> {
                    previewStatus?.visibility = View.VISIBLE
                    previewStatus?.text = if (statusFailed[on] == true) STATUS_FAILED else STATUS_LOADING
                    previewGroups?.visibility = View.GONE
                }
                else -> {
                    previewStatus?.visibility = View.GONE
                    previewGroups?.visibility = View.VISIBLE
                    previewGroups?.let { renderGroups(it, app.surrealar.coords.CoordinateSystemGroups.build(true, st.capabilities)) }
                }
            }
        }

        var ensureStatus: (Boolean) -> Unit = {}

        fun bind(s: app.surrealar.settings.model.CoordinateSystemSettings) {
            current = s
            ensureStatus(s.nsrs2022Preview)
            if (s.selectableHorizontals != hs) {
                hs = s.selectableHorizontals
                spinnerH.setAdapter(adapter(hs.map { it.label }))
            }
            if (s.selectableDatums != ds) {
                ds = s.selectableDatums
                spinnerD?.setAdapter(adapter(ds.map { it.label }))
            }
            val shownH = shownHorizontal(s)
            val spcs2022 = shownH == app.surrealar.settings.model.HorizontalSystem.SPCS2022
            spinnerH.setText(shownH.label, false)
            val hNote = app.surrealar.coords.Nsrs2022OutputNotes.horizontalNote(s, chosenSpcs2022(s)?.definition)
            horizontalNote?.text = hNote.orEmpty()
            horizontalNote?.visibility = if (hNote == null) View.GONE else View.VISIBLE
            // SPCS2022: the zone's own 2022 frame (shown, not chosen); otherwise the datum used.
            spinnerD?.setText(if (spcs2022) chosenSpcs2022(s)?.frameLabel?.let { "$it (zone frame, beta)" } ?: SPCS2022_ZONE_FRAME
                else s.effectiveDatum.label, false)
            val dNote = if (spcs2022) null else app.surrealar.coords.Nsrs2022OutputNotes.datumNote(s)
            datumNote?.text = dNote.orEmpty()
            datumNote?.visibility = if (dNote == null) View.GONE else View.VISIBLE
            spinnerU?.setText(s.unit.label, false)
            val selectable = s.selectableVerticals
            if (selectable != vs) {
                vs = selectable
                spinnerV?.setAdapter(adapter(vs.map { it.menuLabel }))
            }
            // A beta height saved while the preview is off shows the default it is replaced by (stored value kept).
            spinnerV?.setText(s.effectiveVertical.menuLabel, false)
            val note = app.surrealar.coords.Nsrs2022Capabilities.verticalNote(s)
            verticalNote?.text = note.orEmpty()
            verticalNote?.visibility = if (note == null) View.GONE else View.VISIBLE
            bindingPreview = true
            switchPreview?.isChecked = s.nsrs2022Preview
            bindingPreview = false
            renderStatus()
            spinnerR?.setText(s.externalReceiverDatum.label, false)
            val zone = io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(s.spcsZoneEpsg)
            zoneRow?.visibility = if (s.horizontal.isStatePlane || spcs2022) View.VISIBLE else View.GONE
            if (spinnerZ?.hasFocus() != true) spinnerZ?.setText(zoneText(s), false)
            // Hint: suggestion notice, or a warning when the live position is outside the chosen zone.
            val fix = fixSwitchboard.currentFix.value
            zoneHint?.text = zoneNotice ?: when {
                spcs2022 -> spcs2022Hint ?: (status()?.spcs2022Zones?.size?.takeIf { it > 0 }
                    ?.let { "All $it SPCS2022 beta zones (NGS). Type a state, zone name or code to filter." } ?: defaultZoneHint)
                zone != null && suggestedZoneEpsg == zone.epsg -> SpcsZoneHints.suggestionHint(suggestion)
                zone != null && fix != null && s.horizontal.isStatePlane ->
                    SpcsZoneHints.mismatchHint(zone, checkZone(zone, fix), locateZone(fix))
                        ?: defaultZoneHint
                else -> defaultZoneHint
            }
            // State Plane is defined on NAD83(2011), SPCS2022 on the zone's 2022 frame: the datum choice does not apply.
            val fixedFrame = s.horizontal.isStatePlane || spcs2022
            spinnerD?.isEnabled = !fixedFrame
            (spinnerD?.parent?.parent as? TextInputLayout)?.alpha = if (fixedFrame) 0.5f else 1f
            showPreview()
        }

        // SPCS2022 zones possible at the live position (off the main thread: polygons and catalog), as a hint only.
        fun suggestSpcs2022() {
            val fix = fixSwitchboard.currentFix.value ?: return
            val registry = statuses[true]?.registry ?: return
            categoryScope.launch {
                val hint = runCatching {
                    withContext(statusDispatcher) {
                        val (options, loc) = ZoneSelectorOptions.locate(fix.latDeg, fix.lonDeg, registry)
                        ZoneSelectorOptions.suggestionHint(options, loc)
                    }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; settingsError("SPCS2022 zone suggestion failed", it) }
                    .getOrNull()
                spcs2022Hint = hint
                bind(current)
            }
        }

        // Saves only the change [edit] makes, applied to the latest stored settings (so a pick made before the
        // first bind never writes this screen's defaults, e.g. the preview OFF), one save at a time.
        val saveMutex = kotlinx.coroutines.sync.Mutex()
        fun save(edit: (app.surrealar.settings.model.CoordinateSystemSettings) -> app.surrealar.settings.model.CoordinateSystemSettings) {
            zoneNotice = null
            bind(edit(current))
            saveScope.launch {
                runCatching {
                    saveMutex.withLock {
                        val latest = settingsRepo.coordinateSystemSettings.first()
                        settingsRepo.setCoordinateSystemSettings(edit(latest))
                    }
                }.onFailure { settingsError("Coordinate system save failed", it) }
            }
        }

        spinnerH.setOnItemClickListener { _, _, pos, _ ->
            val h = hs.getOrNull(pos) ?: return@setOnItemClickListener
            var proposed: Int? = null
            // Switching to State Plane: if the saved zone certainly doesn't fit the live position, suggest
            // one (county-defined zone limits where they exist, else area-of-use boxes). The user can still pick.
            val fix = fixSwitchboard.currentFix.value
            if (h.isStatePlane && fix != null) {
                val found = locateZone(fix)
                val saved = io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(current.spcsZoneEpsg)
                SpcsZoneHints.proposal(saved, saved?.let { checkZone(it, fix) }, found)?.let {
                    proposed = it.epsg; suggestedZoneEpsg = it.epsg; suggestion = found
                }
            }
            val zoneEpsg = proposed
            save { s -> if (zoneEpsg != null) s.copy(horizontal = h, spcsZoneEpsg = zoneEpsg) else s.copy(horizontal = h) }
            // SPCS2022: name the zones possible here (never picked automatically: layered zones overlap legally).
            if (h == app.surrealar.settings.model.HorizontalSystem.SPCS2022) suggestSpcs2022()
        }
        spinnerZ?.setOnItemClickListener { parent, _, pos, _ ->
            // Positions refer to the FILTERED list: resolve by label, not index.
            val label = parent.getItemAtPosition(pos) as? String
            val z = zoneByLabel[label]
            if (z == null) {
                // An SPCS2022 entry (listed only with the preview ON, when the registry reports it operational).
                val option = status()?.spcs2022Zones?.firstOrNull { it.label == label } ?: return@setOnItemClickListener
                spinnerZ.clearFocus()
                val id = option.definition?.id
                if (!option.selectable || id == null) {
                    zoneNotice = "${option.zoneName} (SPCS2022, ${option.frameLabel}, beta): $SPCS2022_NOT_SELECTABLE"
                    bind(current)
                    return@setOnItemClickListener
                }
                save { it.copy(horizontal = app.surrealar.settings.model.HorizontalSystem.SPCS2022, spcs2022Zone = id) }
                return@setOnItemClickListener
            }
            suggestedZoneEpsg = null
            spinnerZ.clearFocus()
            // An SPCS83 zone picked while SPCS2022 is the system switches back to State Plane (SPCS83).
            save {
                if (it.horizontal == app.surrealar.settings.model.HorizontalSystem.SPCS2022)
                    it.copy(horizontal = app.surrealar.settings.model.HorizontalSystem.STATE_PLANE, spcsZoneEpsg = z.epsg)
                else it.copy(spcsZoneEpsg = z.epsg)
            }
        }
        // Leaving the field without picking restores the saved zone's name.
        spinnerZ?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) spinnerZ.setText(zoneText(current), false)
        }
        spinnerD?.setOnItemClickListener { _, _, pos, _ -> ds.getOrNull(pos)?.let { d -> save { it.copy(datum = d) } } }
        spinnerU?.setOnItemClickListener { _, _, pos, _ -> us.getOrNull(pos)?.let { u -> save { it.copy(unit = u) } } }
        spinnerV?.setOnItemClickListener { _, _, pos, _ -> vs.getOrNull(pos)?.let { v -> save { it.copy(vertical = v) } } }
        spinnerR?.setOnItemClickListener { _, _, pos, _ -> rs.getOrNull(pos)?.let { r -> save { it.copy(externalReceiverDatum = r) } } }
        // Turning the preview off never rewrites the saved height choice (see CoordinateSystemSettings.effectiveVertical).
        switchPreview?.setOnCheckedChangeListener { _, on ->
            if (!bindingPreview && on != current.nsrs2022Preview) save { it.copy(nsrs2022Preview = on) }
        }

        // Stored settings: bound on load and again after every save (the merged value), while the page is open.
        categoryScope.launch {
            try {
                settingsRepo.coordinateSystemSettings.distinctUntilChanged().collect { bind(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsError("Coordinate system load failed", e)
            }
        }
        // SPCS2022 catalog (953 rows), 2022 frames and GEOID2022 status for the preview state's registry: computed
        // off the main thread (once per state and screen), shown when ready.
        val statusStarted = HashSet<Boolean>()
        ensureStatus = { preview ->
            if (statusStarted.add(preview)) categoryScope.launch {
                val t0 = System.nanoTime()
                val st = try {
                    withContext(statusDispatcher) { computeStatus(preview) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    settingsError("NSRS 2022 status failed", e); null
                }
                app.surrealar.util.DiagnosticsLogger.d(app.surrealar.util.diagnostics.DiagTags.DATA) {
                    "NSRS 2022 status computed off-main preview=$preview ok=${st != null} durationMs=${(System.nanoTime() - t0) / 1_000_000}"
                }
                statusFailed[preview] = st == null
                if (st != null) statuses[preview] = st
                if (preview == current.nsrs2022Preview) {
                    defaultZoneHint = st?.spcs2022Note?.let { "$baseZoneHint $it" } ?: baseZoneHint
                    renderedGroupsFor = null
                    bind(current)
                    if (preview && shownHorizontal(current) == app.surrealar.settings.model.HorizontalSystem.SPCS2022 &&
                        current.spcs2022Zone == null && spcs2022Hint == null) suggestSpcs2022()
                }
            }
        }
        ensureStatus(current.nsrs2022Preview)
        // Keep the preview live while the page is open; also refresh it when a height model finished loading.
        categoryScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { fixSwitchboard.currentFix.collect { showPreview() } }
                launch { coordinateSystemService.heightDataVersion.collect { showPreview() } }
            }
        }
    }

    /** Off the main thread: product status, the SPCS2022 note and zone entries from [crsRegistry] for [preview]. */
    private fun computeStatus(preview: Boolean): NsrsStatus {
        val registry = crsRegistry(preview)
        return NsrsStatus(
            capabilities = capabilities(registry),
            spcs2022Note = SpcsZoneHints.spcs2022Note(registry),
            spcs2022Zones = ZoneSelectorOptions.spcs2022("", preview = true, registry = registry),
            registry = registry,
        )
    }

    /**
     * The three product groups (verified / verified NGS beta / unsupported) as Material 3 rows: a title and
     * subtitle per group; per item its name and state (beta badged), a concise one-line reason, and, when the
     * registry has more to say, an info dialog with the full text.
     */
    private fun renderGroups(
        container: android.widget.LinearLayout,
        groups: List<Pair<app.surrealar.coords.CoordinateSystemGroup, List<app.surrealar.coords.CoordinateSystemGroupItem>>>,
    ) {
        val ctx = container.context
        fun attr(id: Int): Int = android.util.TypedValue().let { tv -> if (ctx.theme.resolveAttribute(id, tv, true)) tv.resourceId else 0 }
        fun text(appearance: Int, value: CharSequence, topDp: Int = 0) = android.widget.TextView(ctx).apply {
            attr(appearance).takeIf { it != 0 }?.let { androidx.core.widget.TextViewCompat.setTextAppearance(this, it) }
            text = value
            setPadding(0, (topDp * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val betaColor = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorTertiary, "colorTertiary")
        for ((group, items) in groups) {
            container.addView(text(com.google.android.material.R.attr.textAppearanceTitleSmall, group.title, topDp = 8))
            container.addView(text(com.google.android.material.R.attr.textAppearanceBodySmall, group.subtitle).apply { alpha = 0.7f })
            for (item in items) {
                val title = android.text.SpannableStringBuilder().apply {
                    append(item.name, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(": ").append(item.value)
                    if (item.beta) {
                        append("  ")
                        append(BETA_BADGE, android.text.style.ForegroundColorSpan(betaColor), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (item.fullReason != null) append("  ⓘ")
                }
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (6 * resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                    if (!item.selectable) alpha = 0.75f
                    addView(text(com.google.android.material.R.attr.textAppearanceBodyMedium, title))
                    item.shortReason?.let { addView(text(com.google.android.material.R.attr.textAppearanceBodySmall, "Why: $it").apply { alpha = 0.8f }) }
                    contentDescription = listOfNotNull("${item.name}: ${item.value}", if (item.beta) "beta" else null,
                        if (!item.selectable) "not selectable" else null, item.shortReason?.let { "why: $it" },
                        if (item.fullReason != null) "double-tap for details" else null).joinToString(", ")
                }
                item.fullReason?.let { full ->
                    row.isClickable = true
                    row.isFocusable = true
                    attr(android.R.attr.selectableItemBackground).takeIf { it != 0 }?.let { row.setBackgroundResource(it) }
                    row.setOnClickListener {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                            .setTitle(item.name)
                            .setMessage(full)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                container.addView(row)
            }
        }
    }

    companion object {
        const val STATUS_LOADING = "Loading NSRS 2022 product status…"
        const val STATUS_FAILED = "NSRS 2022 product status unavailable."
        const val BETA_BADGE = "β BETA"
        const val SPCS2022_NOT_SELECTABLE =
            "listed from GeoKit, but this app version cannot store or output SPCS2022 coordinates yet."
        /** Datum field text for SPCS2022 before the zone entry is known. */
        const val SPCS2022_ZONE_FRAME = "Zone's 2022 frame (beta)"
    }
}
