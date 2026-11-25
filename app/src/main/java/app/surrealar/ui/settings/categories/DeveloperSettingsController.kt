package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.domain.repository.DeveloperSettingsRepository
import app.surrealar.settings.model.DeveloperSettings
import app.surrealar.ui.settings.common.settingsError
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings → Developer Tools: the developer tools switch. */
internal class DeveloperSettingsController(
    private val fragment: Fragment,
    private val settingsRepo: DeveloperSettingsRepository,
    /** Outlives the screen so a change made as the user leaves still saves. */
    private val saveScope: CoroutineScope,
    /** GNSS Diagnostics → Raw NMEA Recording (app-scoped recorder). */
    rawNmeaRecorder: app.surrealar.gnss.diagnostics.raw.RawNmeaRecorder,
    rawNmeaIo: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : SettingsCategoryController {

    private val rawNmea = RawNmeaRecordingSection(fragment, rawNmeaRecorder, settingsRepo, saveScope, rawNmeaIo)

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_developer, container, false)
        try {
            val switchDevTools = view.findViewById<MaterialSwitch>(R.id.switch_dev_tools)
            if (switchDevTools != null) {
                // categoryScope: binds this view only while it is shown (on the fragment's lifecycleScope
                // the load could land on the switch after the category was left).
                categoryScope.launch {
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
        try {
            rawNmea.bind(view, categoryScope)
        } catch (e: Exception) {
            settingsError("Settings section setup failed section=rawNmeaRecording", e)
        }
        return view
    }

    /** Leaving the Developer page ends a running raw NMEA recording (not on rotation). */
    override fun onDestroyView() {
        rawNmea.onDestroyView()
    }
}
