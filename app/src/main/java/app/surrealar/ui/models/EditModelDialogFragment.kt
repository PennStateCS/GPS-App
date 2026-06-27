package app.surrealar.ui.models

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import app.surrealar.R
import app.surrealar.domain.model.Model

/**
 * Dialog for editing an existing model's metadata (e.g. name) via the repository.
 */
class EditModelDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_MODEL_ID = "model_id"
        private const val ARG_MODEL_NAME = "model_name"
        private const val ARG_MODEL_DESCRIPTION = "model_description"

        fun newInstance(
            model: Model,
            onConfirm: (modelId: String, name: String, description: String?) -> Unit
        ): EditModelDialogFragment {
            val fragment = EditModelDialogFragment()
            fragment.onConfirmCallback = onConfirm
            val args = Bundle().apply {
                putString(ARG_MODEL_ID, model.id)
                putString(ARG_MODEL_NAME, model.name)
                putString(ARG_MODEL_DESCRIPTION, model.description)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var onConfirmCallback: ((String, String, String?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_model, null)

        val editName = view.findViewById<EditText>(R.id.edit_model_name)
        val editDescription = view.findViewById<EditText>(R.id.edit_model_description)

        // Set current values
        arguments?.let { args ->
            editName.setText(args.getString(ARG_MODEL_NAME, ""))
            editDescription.setText(args.getString(ARG_MODEL_DESCRIPTION, ""))
            editName.selectAll()
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Edit 3D Model")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text.toString().trim()
                val description = editDescription.text.toString().trim().takeIf { it.isNotEmpty() }
                val modelId = arguments?.getString(ARG_MODEL_ID) ?: return@setPositiveButton

                if (name.isNotEmpty()) {
                    onConfirmCallback?.invoke(modelId, name, description)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
