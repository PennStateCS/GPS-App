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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /**
     * Settings → Coordinates → Coordinate System: dropdowns for system, State Plane zone (all 118
     * SPCS83 zones, filterable), datum, units, height and external receiver datum, each saved
     * immediately, plus a live preview of the current position in the chosen system. State Plane
     * forces NAD83(2011), so the datum dropdown is disabled then.
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
        val switchPreview = view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview)
        val previewStatus = view.findViewById<android.widget.TextView>(R.id.text_coord_nsrs2022_status)
        // Zone list from geokit's CRS registry (operational SPCS83 entries; stored values stay EPSG ints).
        val zones = SpcsZoneHints.searchZones("")
        val zoneByLabel = zones.associateBy { it.label }
        val defaultZoneHint = "All ${zones.size} SPCS83 zones. Type a state name to filter." +
            (runCatching { SpcsZoneHints.spcs2022Note() }.getOrNull()?.let { " $it" } ?: "")
        var suggestedZoneEpsg: Int? = null
        var suggestion: io.github.pennstatecs.geokit.projection.SpcsZoneSuggestion? = null

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

        val hs = app.surrealar.settings.model.HorizontalSystem.entries
        val ds = app.surrealar.settings.model.OutputDatum.entries
        val us = app.surrealar.settings.model.LinearUnit.entries
        // Height choices: beta ones (GEOID2022) only while the NSRS 2022 beta preview is on.
        var vs = app.surrealar.settings.model.CoordinateSystemSettings().selectableVerticals
        val rs = app.surrealar.settings.model.ReceiverDatum.entries
        fun adapter(labels: List<String>) = ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, labels)
        spinnerH.setAdapter(adapter(hs.map { it.label }))
        spinnerD?.setAdapter(adapter(ds.map { it.label }))
        spinnerU?.setAdapter(adapter(us.map { it.label }))
        spinnerV?.setAdapter(adapter(vs.map { it.label }))
        spinnerR?.setAdapter(adapter(rs.map { it.label }))
        // Editable so typing filters the zones through CrsRegistry.search ("tex", "north", "3701").
        spinnerZ?.setAdapter(SearchFilterAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings,
            zones.map { it.label }) { q -> SpcsZoneHints.searchZones(q).map { it.label } })

        var current = app.surrealar.settings.model.CoordinateSystemSettings()
        var bindingPreview = false
        // Product status from geokit's registries (beta / unavailable + reason), computed once per screen.
        val previewStatusText by lazy {
            runCatching { app.surrealar.coords.Nsrs2022Capabilities.statusText() }
                .onFailure { settingsError("NSRS 2022 status failed", it) }
                .getOrDefault("NSRS 2022 product status unavailable.")
        }

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
            val selectable = s.selectableVerticals
            if (selectable != vs) {
                vs = selectable
                spinnerV?.setAdapter(adapter(vs.map { it.label }))
            }
            // A beta height saved while the preview is off shows the default it is replaced by (stored value kept).
            spinnerV?.setText(s.effectiveVertical.label, false)
            val note = app.surrealar.coords.Nsrs2022Capabilities.verticalNote(s)
            verticalNote?.text = note.orEmpty()
            verticalNote?.visibility = if (note == null) View.GONE else View.VISIBLE
            bindingPreview = true
            switchPreview?.isChecked = s.nsrs2022Preview
            bindingPreview = false
            previewStatus?.visibility = if (s.nsrs2022Preview) View.VISIBLE else View.GONE
            if (s.nsrs2022Preview) previewStatus?.text = previewStatusText
            spinnerR?.setText(s.externalReceiverDatum.label, false)
            val zone = io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(s.spcsZoneEpsg)
            zoneRow?.visibility = if (s.horizontal.isStatePlane) View.VISIBLE else View.GONE
            if (spinnerZ?.hasFocus() != true) spinnerZ?.setText(zone?.label.orEmpty(), false)
            // Hint: suggestion notice, or a warning when the live position is outside the chosen zone.
            val fix = fixSwitchboard.currentFix.value
            zoneHint?.text = when {
                zone != null && suggestedZoneEpsg == zone.epsg -> SpcsZoneHints.suggestionHint(suggestion)
                zone != null && fix != null && s.horizontal.isStatePlane ->
                    SpcsZoneHints.mismatchHint(zone, checkZone(zone, fix), locateZone(fix))
                        ?: defaultZoneHint
                else -> defaultZoneHint
            }
            // State Plane is defined on NAD83(2011): the datum choice does not apply.
            spinnerD?.isEnabled = !s.horizontal.isStatePlane
            (spinnerD?.parent?.parent as? TextInputLayout)?.alpha =
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
            // Switching to State Plane: if the saved zone certainly doesn't fit the live position, suggest
            // one (county-defined zone limits where they exist, else area-of-use boxes). The user can still pick.
            val fix = fixSwitchboard.currentFix.value
            if (h.isStatePlane && fix != null) {
                val found = locateZone(fix)
                val saved = io.github.pennstatecs.geokit.projection.SpcsZones.byEpsg(current.spcsZoneEpsg)
                SpcsZoneHints.proposal(saved, saved?.let { checkZone(it, fix) }, found)?.let {
                    next = next.copy(spcsZoneEpsg = it.epsg); suggestedZoneEpsg = it.epsg; suggestion = found
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
        // Turning the preview off never rewrites the saved height choice (see CoordinateSystemSettings.effectiveVertical).
        switchPreview?.setOnCheckedChangeListener { _, on ->
            if (!bindingPreview && on != current.nsrs2022Preview) save(current.copy(nsrs2022Preview = on))
        }

        categoryScope.launch {
            runCatching { bind(settingsRepo.coordinateSystemSettings.first()) }
                .onFailure { settingsError("Coordinate system load failed", it) }
        }
        // Keep the preview live while the page is open.
        categoryScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.currentFix.collect { showPreview() }
            }
        }
    }
}
