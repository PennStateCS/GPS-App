package app.surrealar.ui.models

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import app.surrealar.R

class AddModelDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_DEFAULT_NAME = "default_name"
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_FILE_SIZE = "file_size"

        fun newInstance(
            defaultName: String,
            fileName: String,
            filePath: String,
            fileSize: Long,
            onConfirm: (name: String, description: String?) -> Unit
        ): AddModelDialogFragment {
            val fragment = AddModelDialogFragment()
            fragment.onConfirmCallback = onConfirm
            val args = Bundle().apply {
                putString(ARG_DEFAULT_NAME, defaultName)
                putString(ARG_FILE_NAME, fileName)
                putString(ARG_FILE_PATH, filePath)
                putLong(ARG_FILE_SIZE, fileSize)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var onConfirmCallback: ((String, String?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_model, null)

        val editName = view.findViewById<EditText>(R.id.edit_model_name)
        val editDescription = view.findViewById<EditText>(R.id.edit_model_description)

        // Set default name
        arguments?.getString(ARG_DEFAULT_NAME)?.let { defaultName ->
            editName.setText(defaultName)
            editName.selectAll()
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Add 3D Model")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = editName.text.toString().trim()
                val description = editDescription.text.toString().trim().takeIf { it.isNotEmpty() }

                if (name.isNotEmpty()) {
                    onConfirmCallback?.invoke(name, description)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
