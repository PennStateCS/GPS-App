package com.example.surveyingapp.ui.rendermap

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
import com.example.surveyingapp.data.Coordinate
import java.util.Locale

class CoordinateInfoBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"
        private const val ARG_ALT = "arg_alt"
        private const val ARG_ICON = "arg_icon"
        private const val ARG_COLOR = "arg_color"

        fun newInstance(point: Coordinate): CoordinateInfoBottomSheet {
            return CoordinateInfoBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, point.id)
                    putString(ARG_NAME, point.name)
                    putDouble(ARG_LAT, point.latitude)
                    putDouble(ARG_LON, point.longitude)
                    putDouble(ARG_ALT, point.altitude)
                    putString(ARG_ICON, point.icon)
                    putInt(ARG_COLOR, point.color)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_coordinate_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val iconView: ImageView = view.findViewById(R.id.bs_icon)
        val titleView: TextView = view.findViewById(R.id.bs_title)
        val coordsView: TextView = view.findViewById(R.id.bs_coords)
        val altView: TextView = view.findViewById(R.id.bs_altitude)

        val name = arguments?.getString(ARG_NAME) ?: "(Unnamed)"
        val lat = arguments?.getDouble(ARG_LAT) ?: 0.0
        val lon = arguments?.getDouble(ARG_LON) ?: 0.0
        val alt = arguments?.getDouble(ARG_ALT) ?: 0.0
        val iconName = arguments?.getString(ARG_ICON) ?: "ic_menu_camera"
        val color = arguments?.getInt(ARG_COLOR) ?: 0xFF2196F3.toInt()

        titleView.text = name
        coordsView.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        altView.text = String.format(Locale.US, "Altitude: %.2f m", alt)

        val resId = resources.getIdentifier(iconName, "drawable", requireContext().packageName)
        val drawable = if (resId != 0) ContextCompat.getDrawable(requireContext(), resId) else ContextCompat.getDrawable(requireContext(), R.drawable.ic_menu_camera)
        drawable?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        iconView.setImageDrawable(drawable)
    }
}

