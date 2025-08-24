package com.example.surveyingapp.ui.viewpoints

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Point
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

class EditCoordinateDialogFragment(
    private val coordinate: Point,
    private val onCoordinateEdited: (Point) -> Unit
) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)
        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val iconSpinner = view.findViewById<Spinner>(R.id.spinner_icon)
        val colorSpinner = view.findViewById<Spinner>(R.id.spinner_color)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        locationText.visibility = View.GONE // Hide location for edit

        // Pre-fill values
        nameEdit.setText(coordinate.name)

        // Icon choices
        val icons = listOf("ic_menu_camera", "ic_menu_gallery", "ic_menu_slideshow")
        iconSpinner.adapter = IconSpinnerAdapter(requireContext(), icons)
        iconSpinner.setSelection(icons.indexOf(coordinate.icon).coerceAtLeast(0))

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
                val icon = icons[iconSpinner.selectedItemPosition]
                val color = colors[colorSpinner.selectedItemPosition].second
                val updated = coordinate.copy(name = name, icon = icon, color = color)
                onCoordinateEdited(updated)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    class IconSpinnerAdapter(
        context: android.content.Context,
        private val icons: List<String>
    ) : ArrayAdapter<String>(context, 0, icons) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createIconView(position, convertView, parent)
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createIconView(position, convertView, parent)
        }
        private fun createIconView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val view = convertView ?: inflater.inflate(R.layout.item_icon_spinner, parent, false)
            val imageView = view.findViewById<ImageView>(R.id.image_icon)
            val textView = view.findViewById<TextView>(R.id.text_icon_name)
            val iconName = icons[position]
            val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
            imageView.setImageResource(resId)
            textView.text = iconName.replace("ic_menu_", "").replaceFirstChar { it.uppercase() }
            return view
        }
    }
}

