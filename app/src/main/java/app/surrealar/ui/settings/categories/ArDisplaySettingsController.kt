package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.domain.repository.ArDisplaySettingsRepository
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.ui.openinar.OpenInARViewModel
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.saveWhileTyping
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings → AR Display: altitude mode, distance filter, labels/arrows/debug switches, model scale. */
internal class ArDisplaySettingsController(
    private val fragment: Fragment,
    private val settingsRepo: ArDisplaySettingsRepository,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_ar_display, container, false)
        try {
            val spinnerAltitude = view.findViewById<AutoCompleteTextView>(R.id.spinner_altitude_mode)
            val spinnerFilter   = view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter)
            val switchLabels     = view.findViewById<MaterialSwitch>(R.id.switch_ar_show_labels)
            val switchArrows     = view.findViewById<MaterialSwitch>(R.id.switch_ar_show_offscreen_arrows)
            val switchDebug      = view.findViewById<MaterialSwitch>(R.id.switch_ar_debug_overlay)
            val switchDebugTools = view.findViewById<MaterialSwitch>(R.id.switch_ar_debug_tools)
            val editScale        = view.findViewById<EditText>(R.id.edit_ar_model_scale)
            view.findViewById<Button>(R.id.btn_save_ar_display)?.visibility = View.GONE

            val altitudeModes   = listOf("Automatic (recommended)" to "AUTO", "Stored Altitude" to "STORED", "Terrain Altitude" to "TERRAIN")
            val tilScale = editScale?.parent?.parent as? TextInputLayout

            spinnerAltitude?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, altitudeModes.map { it.first }))
            spinnerFilter?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, ArDistanceFilterOptions.labels))

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
                    distanceFilterIndex = distanceIdx ?: ArDistanceFilterOptions.indexOfLabel(spinnerFilter?.text?.toString()),
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
                    spinnerFilter?.setText(ArDistanceFilterOptions.labelFor(s.distanceFilterIndex), false)
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
                // The dropdown lists ArDistanceFilterOptions.labels, so its position IS the step index.
                saveAll(distanceIdx = pos.coerceIn(0, ArDistanceFilterOptions.labels.lastIndex))
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
}

/**
 * The AR distance-filter dropdown. The stored `distanceFilterIndex` is a POSITION in
 * [OpenInARViewModel.DISTANCE_FILTER_STEPS] (null = all, then metres), which the AR screen reads and
 * its range chips also use; the labels are derived from that list so the dropdown order cannot drift
 * from it. Out-of-range stored values show as the nearest end (as the repository clamps them).
 */
internal object ArDistanceFilterOptions {
    val steps: List<Double?> get() = OpenInARViewModel.DISTANCE_FILTER_STEPS

    /** One label per step, same order: "All distances", "50 m", "100 m", "500 m". */
    val labels: List<String> = OpenInARViewModel.DISTANCE_FILTER_STEPS.map { m -> if (m == null) "All distances" else "${m.toInt()} m" }

    /** Label shown for a stored step index (clamped to the list). */
    fun labelFor(storedIndex: Int): String = labels[storedIndex.coerceIn(0, labels.lastIndex)]

    /** Step index for a shown label; unknown text = 0 (all distances), as before. */
    fun indexOfLabel(label: String?): Int = labels.indexOf(label).coerceAtLeast(0)
}
