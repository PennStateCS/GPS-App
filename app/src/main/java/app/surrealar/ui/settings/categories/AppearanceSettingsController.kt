package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.domain.repository.AppearanceSettingsRepository
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.showSettingsMessage
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings → Appearance: theme mode, live GNSS status bar, keep screen awake, max brightness. */
internal class AppearanceSettingsController(
    private val host: Fragment,
    private val settingsRepo: AppearanceSettingsRepository,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_appearance, container, false)
        try {
            val spinner           = view.findViewById<AutoCompleteTextView>(R.id.spinner_theme_mode)
            val switchGnssBar     = view.findViewById<MaterialSwitch>(R.id.switch_gnss_status_bar)
            val switchKeepAwake   = view.findViewById<MaterialSwitch>(R.id.switch_keep_screen_awake)
            val switchMaxBright   = view.findViewById<MaterialSwitch>(R.id.switch_max_brightness)

            val options = listOf(
                "System Default" to AppThemeMode.SYSTEM,
                "Light"          to AppThemeMode.LIGHT,
                "Dark"           to AppThemeMode.DARK
            )

            spinner?.setAdapter(
                ArrayAdapter(host.requireContext(), R.layout.list_item_dropdown_settings, options.map { it.first })
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
                        host.showSettingsMessage("Failed to save theme.")
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
}
