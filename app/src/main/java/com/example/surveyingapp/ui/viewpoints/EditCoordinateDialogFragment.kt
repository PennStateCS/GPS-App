package com.example.surveyingapp.ui.viewpoints

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.Model

class EditCoordinateDialogFragment(
    private val coordinate: Coordinate,
    private val dbModels: List<Model> = emptyList(),
    private val onCoordinateEdited: (Coordinate) -> Unit
) : DialogFragment() {

    private var iconSpinnerRef: Spinner? = null
    private var selectedIconIndex: Int = 0
    private var editTextRef: EditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)
        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val iconSpinner = view.findViewById<Spinner>(R.id.spinner_icon)
        val colorSpinner = view.findViewById<Spinner>(R.id.spinner_color)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        locationText.visibility = View.GONE // Hide location for edit

        editTextRef = nameEdit
        nameEdit.setText(coordinate.name)

        // Build icon list matching AddCoordinateDialogFragment exactly
        val builtInNames = listOf("transformer", "hydrant", "sign", "lightpole", "shrub", "building")
        val iconItems: List<IconItem> = builtInNames.map { IconItem.BuiltIn(it) } +
                dbModels.map { IconItem.DbModel(it) }

        iconSpinner.adapter = AddCoordinateDialogFragment.IconSpinnerAdapter(requireContext(), iconItems)
        selectedIconIndex = iconItems.indexOfFirst { it.iconKey() == coordinate.icon }.takeIf { it >= 0 } ?: 0
        iconSpinner.setSelection(selectedIconIndex)
        iconSpinnerRef = iconSpinner

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
                val icon = iconItems[iconSpinner.selectedItemPosition].iconKey()
                val color = colors[colorSpinner.selectedItemPosition].second
                if (name != coordinate.name || icon != coordinate.icon || color != coordinate.color) {
                    val updated = coordinate.copy(name = name, icon = icon, color = color)
                    onCoordinateEdited(updated)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Re-apply selection after the dialog window is attached and laid out,
        // so the collapsed spinner view correctly reflects the coordinate's current icon.
        iconSpinnerRef?.setSelection(selectedIconIndex)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editTextRef?.clearFocus()
        editTextRef = null
    }

    override fun onDetach() {
        super.onDetach()
        editTextRef = null
    }
}
