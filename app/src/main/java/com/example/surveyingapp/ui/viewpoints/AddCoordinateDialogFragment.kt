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
import com.example.surveyingapp.data.Coordinate
import java.util.UUID
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * Dialog Fragment for adding new coordinate points using GPS location.
 *
 * DialogFragment is used for modal dialogs that survive configuration changes.
 * This dialog demonstrates:
 * - GPS location access using LocationManager
 * - Permission handling for location services
 * - Custom spinners with icons
 * - Callback pattern for returning data to parent
 *
 * The constructor takes a callback function that's called when a coordinate is added.
 */
class AddCoordinateDialogFragment(
    private val highAccuracy: Boolean = true,
    private val onPointAdded: (Coordinate) -> Unit
) : DialogFragment() {

    // Variables to store the GPS coordinates
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var altitude: Double = 0.0

    /**
     * Creates and configures the dialog.
     * This is where we set up the UI and handle GPS location fetching.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)

        // Get references to the UI elements
        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        val iconSpinner = view.findViewById<Spinner>(R.id.spinner_icon)
        val colorSpinner = view.findViewById<Spinner>(R.id.spinner_color)

        // Set up icon choices with custom adapter
        val icons = listOf("ic_menu_camera", "ic_menu_gallery", "ic_menu_slideshow")
        iconSpinner.adapter = IconSpinnerAdapter(requireContext(), icons)

        // Set up color choices with predefined colors
        val colors = listOf(
            "Red" to 0xFFE57373.toInt(),
            "Blue" to 0xFF64B5F6.toInt(),
            "Green" to 0xFF81C784.toInt(),
            "Orange" to 0xFFFFB74D.toInt(),
            "Purple" to 0xFFBA68C8.toInt()
        )
        colorSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, colors.map { it.first })

        // Fetch current location (provider order depends on highAccuracy preference)
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val primaryProvider = if (highAccuracy) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val secondaryProvider = if (highAccuracy) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            try {
                @Suppress("MissingPermission")
                val location = locationManager.getLastKnownLocation(primaryProvider)
                    ?: locationManager.getLastKnownLocation(secondaryProvider)

                if (location != null) {
                    // Successfully got location - store the coordinates
                    latitude = location.latitude
                    longitude = location.longitude
                    altitude = location.altitude
                    val mode = if (highAccuracy) "HIGH" else "BALANCED"
                    locationText.text = getString(R.string.location_label, latitude, longitude, altitude) + " ($mode)"
                } else {
                    // No location available
                    locationText.text = getString(R.string.location_unavailable)
                }
            } catch (e: SecurityException) {
                // Permission was revoked between check and usage
                locationText.text = getString(R.string.location_unavailable)
            }
        } else {
            // No location permissions granted
            locationText.text = getString(R.string.location_permission_required)
        }

        // Build and return the AlertDialog
        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.add_coordinate_title))
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                // Create a new Coordinate object with user input
                val name = nameEdit.text.toString().ifBlank { "Unnamed Coordinate" }
                val icon = icons[iconSpinner.selectedItemPosition]
                val color = colors[colorSpinner.selectedItemPosition].second

                val point = Coordinate(
                    id = UUID.randomUUID().toString(),  // Generate unique ID
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    timestamp = System.currentTimeMillis(),  // Current time
                    icon = icon,
                    color = color
                )

                // Call the callback function to return the new coordinate
                onPointAdded(point)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    /**
     * Custom adapter for the icon spinner that displays icons with text.
     *
     * This demonstrates how to create custom adapters for Spinners.
     * It shows both an icon image and the icon name in each dropdown item.
     */
    class IconSpinnerAdapter(
        context: Context,
        private val icons: List<String>
    ) : ArrayAdapter<String>(context, 0, icons) {

        // View shown when spinner is closed (selected item)
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createIconView(position, convertView, parent)
        }

        // View shown in the dropdown list
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createIconView(position, convertView, parent)
        }

        /**
         * Creates a view for a single icon item.
         * Uses view recycling for performance (convertView).
         */
        private fun createIconView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            val view = convertView ?: inflater.inflate(R.layout.item_icon_spinner, parent, false)

            val imageView = view.findViewById<ImageView>(R.id.image_icon)
            val textView = view.findViewById<TextView>(R.id.text_icon_name)

            val iconName = icons[position]

            // Load the icon by name using resource reflection
            val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
            imageView.setImageResource(resId)

            // Create a user-friendly name from the icon name
            textView.text = iconName.replace("ic_menu_", "").replaceFirstChar { it.uppercase() }

            return view
        }
    }
}
