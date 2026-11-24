package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.domain.repository.GnssCaptureSettingsRepository
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.saveWhileTyping
import com.google.android.material.textfield.TextInputLayout
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings → Capture: capture preset and the Custom averaging rules (GnssCaptureSettings). */
internal class CaptureSettingsController(
    private val fragment: Fragment,
    private val settingsRepo: GnssCaptureSettingsRepository,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_gnss_capture, container, false)
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
            val tilPrecision = editPrecision?.parent?.parent as? TextInputLayout
            val presets = io.github.pennstatecs.geokit.gnss.capture.CapturePreset.entries
            spinnerPreset?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, presets.map { it.label }))
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
                    (f?.parent?.parent as? TextInputLayout)?.let { til ->
                        til.isEnabled = custom
                        til.alpha = if (custom) 1f else 0.5f
                    }
                }
            }

            val tilMinDur  = editMinDur?.parent?.parent  as? TextInputLayout
            val tilMaxDur  = editMaxDur?.parent?.parent  as? TextInputLayout
            val tilSamples = editSamples?.parent?.parent as? TextInputLayout
            val tilFixAge  = editFixAge?.parent?.parent  as? TextInputLayout
            val tilDiffAge = editDiffAge?.parent?.parent as? TextInputLayout

            val rtkOptions = listOf(
                "RTK FIX only"         to RtkStatus.FIX,
                "RTK FLOAT or better"  to RtkStatus.FLOAT,
                "DGPS or better"       to RtkStatus.DGPS,
                "Single GPS or better" to RtkStatus.SINGLE
            )
            spinner?.setAdapter(ArrayAdapter(fragment.requireContext(), R.layout.list_item_dropdown_settings, rtkOptions.map { it.first }))

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
}
