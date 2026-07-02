package com.example.surveyingapp.ui.models

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.surveyingapp.R
import java.util.Locale

class ModelDiagnosticsDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_DIAGNOSTICS = "diagnostics"

        fun newInstance(diagnostics: ModelDiagnostics): ModelDiagnosticsDialogFragment {
            return ModelDiagnosticsDialogFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_DIAGNOSTICS, diagnostics)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_model_diagnostic, null)

        readDiagnostics()?.let { bindDiagnostics(view, it) }

        view.findViewById<Button>(R.id.model_diag_ok).setOnClickListener {
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun bindDiagnostics(view: View, d: ModelDiagnostics) {
        view.findViewById<TextView>(R.id.model_diag_ar_readiness).text = getString(R.string.model_diag_ar_readiness, d.arReadiness)
        view.findViewById<TextView>(R.id.model_diag_file_size).text = getString(R.string.model_diag_file_size, formatFileSize(d.fileSizeBytes))
        view.findViewById<TextView>(R.id.model_diag_dimensions).text = getString(R.string.model_diag_dimensions,formatDimensions(d.widthMeters, d.depthMeters, d.heightMeters))
        view.findViewById<TextView>(R.id.model_diag_origin).text = getString(R.string.model_diag_origin, d.originDescription)
        view.findViewById<TextView>(R.id.model_diag_origin_offset).text = getString(R.string.model_diag_origin_offset, formatMeters(d.originOffsetMeters))
        view.findViewById<TextView>(R.id.model_diag_vertical_placement).text = getString(R.string.model_diag_vertical_placement, d.verticalPlacement)
    }

    private fun readDiagnostics(): ModelDiagnostics? {
        val args = arguments ?: return null

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return args.getSerializable(ARG_DIAGNOSTICS, ModelDiagnostics::class.java)
        }
        else {
            return args.getSerializable(ARG_DIAGNOSTICS) as? ModelDiagnostics
        }
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
