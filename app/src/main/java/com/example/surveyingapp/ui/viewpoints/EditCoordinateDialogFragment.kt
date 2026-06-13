package com.example.surveyingapp.ui.viewpoints

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.ui.models.ModelPickerActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditCoordinateDialogFragment(
    private val coordinate: Coordinate,
    private val dbModels: List<Model> = emptyList(),
    private val onCoordinateEdited: (Coordinate) -> Unit
) : DialogFragment() {

    private var iconButtonRef: MaterialButton? = null

    // Defaults to the coordinate's existing icon so saving without re-choosing keeps it.
    private var selectedIconKey: String = coordinate.icon

    // Launches the model picker and applies the chosen model to the icon button.
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_ID)
                ?: return@registerForActivityResult
            val name = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_NAME)
            val thumbnailPath = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_THUMBNAIL_PATH)
            applySelectedModel(id, name, thumbnailPath)
        }
    }

    private var editTextRef: EditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)
        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val iconButton = view.findViewById<MaterialButton>(R.id.button_icon)
        val colorSpinner = view.findViewById<Spinner>(R.id.spinner_color)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        locationText.visibility = View.GONE // Hide location for edit

        editTextRef = nameEdit
        nameEdit.setText(coordinate.name)

        // Icon is chosen through the model picker
        iconButtonRef = iconButton

        iconButton.setOnClickListener {
            modelPickerLauncher.launch(
                ModelPickerActivity.newIntent(requireContext(), "Choose a Model")
            )
        }

        // Get coordinate icon and put it on the button
        // Inline suggestion
        coordinate.icon
            .takeIf { it.startsWith("model:") }
            ?.removePrefix("model:")
            ?.let { id -> dbModels.firstOrNull { it.id == id } }
            ?.let { model -> applySelectedModel(model.id, model.name, model.thumbnailFilePath) }


        // Color choices
        val colors = listOf(
            "Red" to 0xFFE57373.toInt(),
            "Blue" to 0xFF64B5F6.toInt(),
            "Green" to 0xFF81C784.toInt(),
            "Orange" to 0xFFFFB74D.toInt(),
            "Purple" to 0xFFBA68C8.toInt()
        )

        colorSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, colors.map { it.first })
        colorSpinner.setSelection(colors.indexOfFirst { it.second == coordinate.color }.coerceAtLeast(0))

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.edit_coordinate))
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEdit.text.toString().ifBlank { coordinate.name }
                val icon = selectedIconKey
                val color = colors[colorSpinner.selectedItemPosition].second
                if (name != coordinate.name || icon != coordinate.icon || color != coordinate.color) {
                    val updated = coordinate.copy(name = name, icon = icon, color = color)
                    onCoordinateEdited(updated)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun applySelectedModel(modelId: String, name: String?, thumbnailPath: String?) {
        selectedIconKey = "model:$modelId"

        // If the button isn't available, we can't apply the model so return
        val button = iconButtonRef ?: return

        button.text = name ?: "Selected model"

        if (thumbnailPath.isNullOrBlank()) {
            button.icon = null
            return
        }

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val file = File(thumbnailPath)
                    if (file.exists()) BitmapFactory.decodeFile(thumbnailPath) else null
                } catch (e: Exception) {
                    Log.w("EditCoordinateDialog", "Failed to decode thumbnail: $thumbnailPath", e)
                    null
                }
            }

            // Guards against the dialog being torn down while decoding
            if (bmp != null) {
                iconButtonRef?.iconTint = null // Fixes weird default tint Android adds for some reason to Material UI
                iconButtonRef?.icon = bmp.toDrawable(resources)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editTextRef?.clearFocus()
        editTextRef = null
        iconButtonRef = null
    }

    override fun onDetach() {
        super.onDetach()
        editTextRef = null
    }
}
