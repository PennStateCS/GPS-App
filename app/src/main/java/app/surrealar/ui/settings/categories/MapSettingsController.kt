package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.domain.repository.MapSettingsRepository
import app.surrealar.domain.repository.StakeoutSettingsRepository
import app.surrealar.ui.settings.common.settingsError
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings → Map: map display defaults (MapSettings) and stakeout defaults (StakeoutSettings). */
internal class MapSettingsController(
    private val fragment: Fragment,
    private val mapSettingsRepo: MapSettingsRepository,
    private val stakeoutSettingsRepo: StakeoutSettingsRepository,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_map, container, false)
        try {
            // ── Map defaults (MapSettings) ──────────────────────────────────────────
            val spinnerType   = view.findViewById<AutoCompleteTextView>(R.id.spinner_map_type)
            val spinnerGrid   = view.findViewById<AutoCompleteTextView>(R.id.spinner_grid_mode)
            val spinnerLabel  = view.findViewById<AutoCompleteTextView>(R.id.spinner_label_mode)
            val switchTools   = view.findViewById<MaterialSwitch>(R.id.switch_map_tools_open)
            val switchDrawer  = view.findViewById<MaterialSwitch>(R.id.switch_drawer_expanded)
            val switchMyLoc   = view.findViewById<MaterialSwitch>(R.id.switch_show_my_location)

            val typeOptions = listOf(
                "Normal" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL,
                "Satellite" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_SATELLITE,
                "Hybrid" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_HYBRID,
                "Terrain" to com.google.android.gms.maps.GoogleMap.MAP_TYPE_TERRAIN,
            )
            val gridOptions = app.surrealar.ui.rendermap.MapGridMode.entries.map { it.label to it }
            val labelOptions = app.surrealar.ui.rendermap.PointLabelMode.entries.map { it.label to it }
            spinnerType?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, typeOptions.map { it.first }))
            spinnerGrid?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, gridOptions.map { it.first }))
            spinnerLabel?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, labelOptions.map { it.first }))

            var currentMap = app.surrealar.settings.SettingsDefaults.map
            fun persistMap(updated: app.surrealar.ui.rendermap.MapSettings) {
                currentMap = updated
                saveScope.launch { runCatching { mapSettingsRepo.setMapSettings(updated) }.onFailure { settingsError("Settings save failed section=map", it) } }
            }

            // ── Stakeout defaults (StakeoutSettings) ────────────────────────────────
            val editTolerance = view.findViewById<EditText>(R.id.edit_stakeout_tolerance)
            val editWarnAcc   = view.findViewById<EditText>(R.id.edit_stakeout_warning_accuracy)
            val switchHaptics = view.findViewById<MaterialSwitch>(R.id.switch_stakeout_haptics)
            val switchAudio   = view.findViewById<MaterialSwitch>(R.id.switch_stakeout_audio)
            val switchCompass = view.findViewById<MaterialSwitch>(R.id.switch_stakeout_compass)
            val switchKeepOn  = view.findViewById<MaterialSwitch>(R.id.switch_stakeout_keep_screen_on)
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
                saveScope.launch { runCatching { stakeoutSettingsRepo.setStakeoutSettings(updated) }.onFailure { settingsError("Settings save failed section=stakeout", it) } }
            }

            categoryScope.launch {
                try {
                    currentMap = mapSettingsRepo.mapSettings.first()
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

                    currentStakeout = stakeoutSettingsRepo.stakeoutSettings.first()
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
}
