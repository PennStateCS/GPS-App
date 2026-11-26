package app.surrealar.ui.models

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.ui.models.readiness.ReadinessHost
import app.surrealar.ui.models.readiness.ReadinessStyle
import app.surrealar.ui.models.readiness.ReadinessUiState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AR readiness checklist for the model viewer: verdict chip + summary, one row per finding with an
 * optional one-tap fix, and the old diagnostics numbers as a small "Details" footer.
 *
 * Stateless: it renders [ReadinessHost.readinessState] from the hosting activity and forwards fix taps
 * to [ReadinessHost.onReadinessFix], so it survives recreation and refreshes after a fix.
 */
class ModelDiagnosticsDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ModelReadinessDialog"
        fun newInstance(): ModelDiagnosticsDialogFragment = ModelDiagnosticsDialogFragment()
    }

    private var root: View? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_model_diagnostic, null)
        root = view
        view.findViewById<Button>(R.id.model_diag_ok).setOnClickListener { dismiss() }

        val host = activity as? ReadinessHost
        if (host != null) {
            bind(view, host.readinessState.value)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    host.readinessState.collect { state -> root?.let { bind(it, state) } }
                }
            }
        }

        return AlertDialog.Builder(requireContext()).setView(view).create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        root = null
    }

    private fun bind(view: View, state: ReadinessUiState) {
        val ctx = view.context
        val verdict = view.findViewById<TextView>(R.id.readiness_verdict)
        val summary = view.findViewById<TextView>(R.id.readiness_summary)
        val report = state.report

        view.findViewById<ProgressBar>(R.id.readiness_progress).visibility =
            if (state.loading || state.applying) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.readiness_no_model).visibility =
            if (!state.hasModel && !state.loading) View.VISIBLE else View.GONE

        if (report == null) {
            verdict.text = getString(R.string.readiness_checking)   // neutral tint from the layout
            summary.visibility = View.GONE
        } else {
            verdict.text = ReadinessStyle.verdictTitle(report.verdict)
            // Filled chip: semantic container + on-container (white on the status colour failed in dark mode).
            val tone = ReadinessStyle.verdictTone(report.verdict)
            verdict.backgroundTintList = ColorStateList.valueOf(tone.container(verdict))
            verdict.setTextColor(tone.onContainer(verdict))
            summary.text = report.summary
            summary.visibility = if (report.summary.isBlank()) View.GONE else View.VISIBLE
        }

        val list = view.findViewById<LinearLayout>(R.id.readiness_checks)
        list.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        report?.checks.orEmpty().forEach { check ->
            val row = inflater.inflate(R.layout.item_readiness_check, list, false)
            row.findViewById<ImageView>(R.id.check_icon).apply {
                setImageResource(ReadinessStyle.statusIcon(check.status))
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, ReadinessStyle.statusColor(check.status)))
                contentDescription = check.status.name
            }
            row.findViewById<TextView>(R.id.check_title).text = check.title
            row.findViewById<TextView>(R.id.check_detail).text = check.detail

            val fixText = row.findViewById<TextView>(R.id.check_fix)
            val fixButton = row.findViewById<Button>(R.id.check_fix_button)
            val buttonLabel = ReadinessStyle.fixButton(check.fixAction)
            fixText.text = check.fix
            fixText.visibility = if (check.fix.isNullOrBlank()) View.GONE else View.VISIBLE
            if (buttonLabel != null) {
                fixButton.text = buttonLabel
                fixButton.visibility = View.VISIBLE
                fixButton.isEnabled = !state.applying
                fixButton.setOnClickListener { (activity as? ReadinessHost)?.onReadinessFix(check) }
            } else {
                fixButton.visibility = View.GONE
            }
            row.findViewById<View>(R.id.check_fix_row).visibility =
                if (fixText.visibility == View.VISIBLE || buttonLabel != null) View.VISIBLE else View.GONE
            list.addView(row)
        }

        val details = state.details
        view.findViewById<View>(R.id.readiness_details).visibility = if (details != null) View.VISIBLE else View.GONE
        if (details != null) bindDetails(view, details)
    }

    private fun bindDetails(view: View, d: ModelDiagnostics) {
        view.findViewById<TextView>(R.id.model_diag_file_size).text = getString(R.string.model_diag_file_size, formatFileSize(d.fileSizeBytes))
        view.findViewById<TextView>(R.id.model_diag_dimensions).text = getString(R.string.model_diag_dimensions, formatDimensions(d.widthMeters, d.depthMeters, d.heightMeters))
        view.findViewById<TextView>(R.id.model_diag_origin).text = getString(R.string.model_diag_origin, d.originDescription)
        view.findViewById<TextView>(R.id.model_diag_origin_offset).text = getString(R.string.model_diag_origin_offset, formatMeters(d.originOffsetMeters))
        view.findViewById<TextView>(R.id.model_diag_vertical_placement).text = getString(R.string.model_diag_vertical_placement, d.verticalPlacement)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun formatDimensions(width: Float, depth: Float, height: Float): String =
        String.format(Locale.US, "%.2f m wide × %.2f m deep × %.2f m tall", width, depth, height)

    private fun formatMeters(meters: Float): String =
        String.format(Locale.US, "%.2f m", meters)
}
