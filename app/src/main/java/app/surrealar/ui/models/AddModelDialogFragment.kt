package app.surrealar.ui.models

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import app.surrealar.R
import app.surrealar.data.files.ModelFileCleaner
import app.surrealar.util.DiagnosticsLogger
import java.io.File

/**
 * Dialog for importing a new 3D model: collects a name/file and creates the model via the repository.
 */
class AddModelDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_DEFAULT_NAME = "default_name"
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_FILE_SIZE = "file_size"

        /** Shown when Add is tapped on a dialog recreated after rotation or process death (nothing can save it). */
        internal const val INTERRUPTED_MESSAGE =
            "Model import was interrupted (screen rotated or app restarted). Please import the file again."

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

    /** True once the model was handed to the caller to save; otherwise the copied file is removed. */
    private var saved = false

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Called for Cancel, back, outside-tap and after Add. Not called on a configuration
        // change (the dialog is recreated), so the copy survives rotation.
        if (!saved) discardCopiedFile()
    }

    private fun discardCopiedFile() {
        val path = arguments?.getString(ARG_FILE_PATH) ?: return
        val appContext = context?.applicationContext ?: return
        val modelsRoot = File(appContext.filesDir, ModelFileCleaner.MODELS_DIR_NAME)
        Thread({
            val deleted = ModelFileCleaner.deleteModelFile(path, modelsRoot)
            if (deleted) DiagnosticsLogger.i("MODEL", "Model import cancelled: copied file removed")
        }, "model-import-discard").start()
    }

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

                // The callback is lost if the dialog was recreated (e.g. rotation); then nothing
                // saves the model and the copy is discarded in onDismiss like a cancel.
                val callback = onConfirmCallback
                if (name.isNotEmpty() && callback != null) {
                    saved = true
                    callback.invoke(name, description)
                } else if (callback == null) {
                    // Say so instead of silently doing nothing: the staged copy is removed in onDismiss (or, after
                    // process death, by the start-up orphan sweep once older than its grace period).
                    DiagnosticsLogger.w("MODEL", "Model import interrupted: dialog recreated without its save callback")
                    android.widget.Toast.makeText(requireContext(), INTERRUPTED_MESSAGE, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
