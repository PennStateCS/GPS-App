package app.surrealar.ui.settings.categories

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.surrealar.BuildConfig
import app.surrealar.R
import app.surrealar.ui.settings.DiagnosticExportUi
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.showSettingsMessage
import app.surrealar.util.BuildInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → About: build information, the diagnostic report export button and Mark Problem. Only
 * presents UI; the export and the incident marker are [DiagnosticExportUi]'s.
 */
internal class AboutSettingsController(
    private val fragment: Fragment,
    /** The export path ([DiagnosticExportUi.exportAndShare]); a parameter so tests can observe the click. */
    private val exportAndShare: suspend (Context) -> Int = DiagnosticExportUi::exportAndShare,
) : SettingsCategoryController {

    override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
        val view = inflater.inflate(R.layout.content_settings_about, container, false)
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

            val btnExport = view.findViewById<MaterialButton>(
                R.id.btn_export_diagnostic
            )
            btnExport?.setOnClickListener {
                btnExport.isEnabled = false
                btnExport.setText(R.string.diag_export_button_busy)
                categoryScope.launch {
                    val ctx = fragment.requireContext()
                    // ExportOutcome → share, or a friendly message per failure reason (never exception text).
                    val message = exportAndShare(ctx)
                    btnExport.isEnabled = true
                    btnExport.setText(R.string.diag_export_button)
                    fragment.showSettingsMessage(fragment.getString(message),
                        if (message == R.string.diag_export_ready) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG)
                }
            }

            view.findViewById<View>(R.id.btn_mark_problem)?.setOnClickListener {
                val appContext = fragment.requireContext().applicationContext
                DiagnosticExportUi.showMarkProblemDialog(fragment.requireContext()) { note ->
                    categoryScope.launch {
                        val marker = DiagnosticExportUi.markProblem(appContext, note)
                        if (!fragment.isAdded) return@launch
                        fragment.showSettingsMessage(
                            if (marker == null) fragment.getString(R.string.diag_mark_failed)
                            else fragment.getString(R.string.diag_mark_done, DiagnosticExportUi.markTime(marker.epochMs)),
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
}
