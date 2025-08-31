package com.example.surveyingapp.ui.rendermap

// BottomSheetDialogFragment that displays details for a single Coordinate.
// Expects a Coordinate passed via arguments (see newInstance) and renders
// its name, lat/lon (6 decimal places), altitude, and a tinted icon.
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.surveyingapp.R
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.surveyingapp.domain.model.Coordinate
import java.util.Locale

class CoordinateInfoBottomSheet : BottomSheetDialogFragment() {

    companion object {
        // Argument keys for bundling the coordinate fields.
        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"
        private const val ARG_ALT = "arg_alt"
        private const val ARG_ICON = "arg_icon"
        private const val ARG_COLOR = "arg_color"

        // Factory method to create the sheet with all needed data serialized into arguments.
        fun newInstance(coordinate: Coordinate): CoordinateInfoBottomSheet {
            val fragment = CoordinateInfoBottomSheet()
            val bundle = Bundle().apply {
                putString(ARG_ID, coordinate.id)
                putString(ARG_NAME, coordinate.name)
                putDouble(ARG_LAT, coordinate.latitude)
                putDouble(ARG_LON, coordinate.longitude)
                putDouble(ARG_ALT, coordinate.altitude)
                putString(ARG_ICON, coordinate.icon)
                putInt(ARG_COLOR, coordinate.color)
            }
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate simple static layout for the bottom sheet.
        return inflater.inflate(R.layout.bottom_sheet_coordinate_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Bind views.
        val iconView: ImageView = view.findViewById(R.id.bs_icon)
        val titleView: TextView = view.findViewById(R.id.bs_title)
        val coordsView: TextView = view.findViewById(R.id.bs_coords)
        val altView: TextView = view.findViewById(R.id.bs_altitude)

        // Extract arguments with safe fallbacks (useful if something missing).
        val name = arguments?.getString(ARG_NAME) ?: "(Unnamed)"
        val lat = arguments?.getDouble(ARG_LAT) ?: 0.0
        val lon = arguments?.getDouble(ARG_LON) ?: 0.0
        val alt = arguments?.getDouble(ARG_ALT) ?: 0.0
        val iconNameRaw = arguments?.getString(ARG_ICON)
        val iconName = when (iconNameRaw) {
            null, "", "ic_menu_camera" -> "ic_pin"
            "ic_menu_gallery" -> "ic_star"
            "ic_menu_slideshow" -> "ic_home"
            else -> iconNameRaw
        } ?: "ic_pin"
        val color = arguments?.getInt(ARG_COLOR) ?: 0xFF2196F3.toInt() // Default blue

        // Populate text fields.
        titleView.text = name
        coordsView.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        altView.text = String.format(Locale.US, "Altitude: %.2f m", alt)

        // Dynamically resolve icon resource name; fallback to a default if not found.
        val resId = resources.getIdentifier(iconName, "drawable", requireContext().packageName)
        val drawable = (if (resId != 0) ContextCompat.getDrawable(requireContext(), resId)
                        else ContextCompat.getDrawable(requireContext(), R.drawable.ic_pin))
            ?.mutate()

        // Apply tint based on the coordinate's color and set the drawable.
        drawable?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        iconView.setImageDrawable(drawable)
    }
}
