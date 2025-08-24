package com.example.surveyingapp.ui.viewpoints

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Point
import java.util.UUID
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

class AddPointDialogFragment(private val onPointAdded: (Point) -> Unit) : DialogFragment() {
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var altitude: Double = 0.0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)
        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        val iconSpinner = view.findViewById<Spinner>(R.id.spinner_icon)
        val colorSpinner = view.findViewById<Spinner>(R.id.spinner_color)

        // Icon choices
        val icons = listOf("ic_menu_camera", "ic_menu_gallery", "ic_menu_slideshow")
        iconSpinner.adapter = IconSpinnerAdapter(requireContext(), icons)

        // Color choices
        val colors = listOf(
            "Red" to 0xFFE57373.toInt(),
            "Blue" to 0xFF64B5F6.toInt(),
            "Green" to 0xFF81C784.toInt(),
            "Orange" to 0xFFFFB74D.toInt(),
            "Purple" to 0xFFBA68C8.toInt()
        )
        colorSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, colors.map { it.first })

        // Fetch location using Android LocationManager
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                @Suppress("MissingPermission")
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                    altitude = location.altitude
                    locationText.text = getString(R.string.location_label, latitude, longitude, altitude)
                } else {
                    locationText.text = getString(R.string.location_unavailable)
                }
            } catch (e: SecurityException) {
                locationText.text = getString(R.string.location_unavailable)
            }
        } else {
            locationText.text = getString(R.string.location_permission_required)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.add_coordinate_title))
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameEdit.text.toString().ifBlank { "Unnamed Point" }
                val icon = icons[iconSpinner.selectedItemPosition]
                val color = colors[colorSpinner.selectedItemPosition].second
                val point = Point(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    timestamp = System.currentTimeMillis(),
                    icon = icon,
                    color = color
                )
                onPointAdded(point)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    class IconSpinnerAdapter(
        context: Context,
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
