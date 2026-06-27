package app.surrealar.ui.openinar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import app.surrealar.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet that displays details for a tapped geospatial pin.
 */
class PinInspectBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG          = "PinInspect"
        private const val KEY_NAME     = "name"
        private const val KEY_LAT      = "lat"
        private const val KEY_LON      = "lon"
        private const val KEY_ALT      = "alt"
        private const val KEY_H_ACC    = "h_acc"
        private const val KEY_RTK      = "rtk"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_TIMESTAMP= "timestamp"

        fun show(
            fm: FragmentManager,
            name: String,
            lat: Double,
            lon: Double,
            alt: Double,
            hAccM: Double?,
            rtkStatus: String?,
            provider: String,
            modelId: String?,
            timestamp: Long
        ) {
            // Dismiss any existing instance before showing a new one
            (fm.findFragmentByTag(TAG) as? PinInspectBottomSheet)?.dismiss()

            PinInspectBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(KEY_NAME, name)
                    putDouble(KEY_LAT, lat)
                    putDouble(KEY_LON, lon)
                    putDouble(KEY_ALT, alt)
                    putDouble(KEY_H_ACC, hAccM ?: -1.0)
                    putString(KEY_RTK, rtkStatus ?: "")
                    putString(KEY_PROVIDER, provider)
                    putString(KEY_MODEL_ID, modelId ?: "")
                    putLong(KEY_TIMESTAMP, timestamp)
                }
            }.show(fm, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_pin_inspect, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val name      = args.getString(KEY_NAME, "Unknown")
        val lat       = args.getDouble(KEY_LAT)
        val lon       = args.getDouble(KEY_LON)
        val alt       = args.getDouble(KEY_ALT)
        val hAcc      = args.getDouble(KEY_H_ACC).takeIf { it >= 0 }
        val rtk       = args.getString(KEY_RTK, "").takeIf { it.isNotEmpty() }
        val provider  = args.getString(KEY_PROVIDER, "Unknown")
        val modelId   = args.getString(KEY_MODEL_ID, "").takeIf { it.isNotEmpty() }
        val timestamp = args.getLong(KEY_TIMESTAMP)

        view.findViewById<TextView>(R.id.pinName).text = name

        val dateStr = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
            .format(Date(timestamp))
        view.findViewById<TextView>(R.id.pinTimestamp).text = dateStr

        view.findViewById<TextView>(R.id.pinLatLon).text =
            "%.6f,  %.6f".format(lat, lon)

        view.findViewById<TextView>(R.id.pinAlt).text =
            "Altitude:  %.3f m (ellipsoidal)".format(alt)

        view.findViewById<TextView>(R.id.pinAccuracy).text =
            if (hAcc != null) "H. Accuracy:  ±%.2f m".format(hAcc) else "H. Accuracy:  —"

        val rtkView = view.findViewById<TextView>(R.id.pinRtk)
        if (rtk != null) {
            rtkView.text = "RTK:  $rtk"
            rtkView.setTextColor(
                when (rtk.uppercase()) {
                    "FIX"    -> 0xFF4CAF50.toInt()
                    "FLOAT"  -> 0xFF2196F3.toInt()
                    "DGPS"   -> 0xFFFF9800.toInt()
                    "SINGLE" -> 0xFFFF5722.toInt()
                    else     -> 0xFF9E9E9E.toInt()
                }
            )
            rtkView.isVisible = true
        }

        view.findViewById<TextView>(R.id.pinProvider).text = "Source:  $provider"

        val modelView = view.findViewById<TextView>(R.id.pinModel)
        if (modelId != null) {
            modelView.text = "◆ Model:  $modelId"
            modelView.isVisible = true
        }
    }
}

