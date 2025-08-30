package com.example.surveyingapp.ui.viewpoints

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.TextView

class CoordinateDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        fun newInstance(id: String): CoordinateDetailFragment = CoordinateDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    private lateinit var viewModel: CoordinatesViewModel

    private var textName: TextView? = null
    private var textCoords: TextView? = null
    private var textTime: TextView? = null
    private var textProvider: TextView? = null
    private var textRtk: TextView? = null
    private var textHdop: TextView? = null
    private var textEmpty: TextView? = null
    private var textAltitudes: TextView? = null
    private var textAccuracy: TextView? = null
    private var textSats: TextView? = null
    private var textCorrection: TextView? = null
    private var textProjection: TextView? = null
    private var textStdDev: TextView? = null
    private var textAveraging: TextView? = null
    private var textNote: TextView? = null
    private var badgeRtk: TextView? = null
    private var badgeAccuracy: TextView? = null
    private var rowBadges: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_coordinate_detail, container, false)
        textName = v.findViewById(R.id.text_name)
        textCoords = v.findViewById(R.id.text_coords)
        textTime = v.findViewById(R.id.text_time)
        textProvider = v.findViewById(R.id.text_provider)
        textRtk = v.findViewById(R.id.text_rtk)
        textHdop = v.findViewById(R.id.text_hdop)
        textEmpty = v.findViewById(R.id.text_empty)
        textAltitudes = v.findViewById(R.id.text_altitudes)
        textAccuracy = v.findViewById(R.id.text_accuracy)
        textSats = v.findViewById(R.id.text_sats)
        textCorrection = v.findViewById(R.id.text_correction)
        textProjection = v.findViewById(R.id.text_projection)
        textStdDev = v.findViewById(R.id.text_stddev)
        textAveraging = v.findViewById(R.id.text_averaging)
        textNote = v.findViewById(R.id.text_note)
        badgeRtk = v.findViewById(R.id.badge_rtk)
        badgeAccuracy = v.findViewById(R.id.badge_accuracy)
        rowBadges = v.findViewById(R.id.row_badges)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)

        val id = arguments?.getString(ARG_ID)
        if (id.isNullOrBlank()) {
            showEmpty()
            return
        }
        lifecycleScope.launch {
            val coord = viewModel.getById(id)
            if (coord == null) {
                showEmpty()
            } else {
                bindCoordinate(coord)
            }
        }
    }

    private fun showEmpty() {
        textEmpty?.visibility = View.VISIBLE
        listOf(
            textName, textCoords, textTime, textProvider, textRtk, textHdop,
            textAltitudes, textAccuracy, textSats, textCorrection, textProjection,
            textStdDev, textAveraging, textNote, badgeRtk, badgeAccuracy, rowBadges
        ).forEach { it?.visibility = View.GONE }
    }

    private fun bindCoordinate(c: com.example.surveyingapp.data.Coordinate) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        textName?.text = c.name
        textCoords?.text = String.format(Locale.US, "Lat: %.6f\nLon: %.6f\nAlt (ellip): %.2f m", c.latitude, c.longitude, c.altitude)
        textTime?.text = "Time: ${fmt.format(Date(c.timestamp))}"
        textProvider?.text = "Provider: ${c.provider}"
        textRtk?.text = "RTK: ${c.rtkStatus ?: "--"}"
        textHdop?.text = "HDOP: ${c.hdop?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}"

        // Altitudes block
        val altParts = mutableListOf<String>()
        c.altitudeMsl?.let { altParts += String.format(Locale.US, "MSL: %.2f m", it) }
        c.geoidSeparationM?.let { altParts += String.format(Locale.US, "Geoid sep: %.2f m", it) }
        textAltitudes?.apply {
            if (altParts.isNotEmpty()) {
                text = "Altitudes: ${altParts.joinToString(" · ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Accuracy block
        val accParts = mutableListOf<String>()
        c.horizontalAccuracyM?.let { accParts += String.format(Locale.US, "H: %.2f m", it) }
        c.verticalAccuracyM?.let { accParts += String.format(Locale.US, "V: %.2f m", it) }
        textAccuracy?.apply {
            if (accParts.isNotEmpty()) {
                text = "Accuracy: ${accParts.joinToString(" / ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Satellites
        textSats?.apply {
            val sats = c.satsUsed
            if (sats != null) {
                text = "Satellites used: $sats"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Correction info
        val corrParts = mutableListOf<String>()
        c.correctionSource?.let { if (it.isNotBlank()) corrParts += it }
        c.correctionAgeS?.let { corrParts += String.format(Locale.US, "age %.1fs", it) }
        textCorrection?.apply {
            if (corrParts.isNotEmpty()) {
                text = "Correction: ${corrParts.joinToString(" · ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Projection info
        val projParts = mutableListOf<String>()
        if (c.easting != null && c.northing != null) {
            projParts += String.format(Locale.US, "E: %.2f", c.easting)
            projParts += String.format(Locale.US, "N: %.2f", c.northing)
        }
        c.utmZone?.let { if (it.isNotBlank()) projParts += it }
        c.crsEpsg?.let { projParts += "EPSG:$it" }
        textProjection?.apply {
            if (projParts.isNotEmpty()) {
                text = "Projection: ${projParts.joinToString(" · ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Standard deviations
        val stdParts = mutableListOf<String>()
        c.stdLatM?.let { stdParts += String.format(Locale.US, "Lat ±%.2f m", it) }
        c.stdLonM?.let { stdParts += String.format(Locale.US, "Lon ±%.2f m", it) }
        c.stdAltM?.let { stdParts += String.format(Locale.US, "Alt ±%.2f m", it) }
        textStdDev?.apply {
            if (stdParts.isNotEmpty()) {
                text = "Std Dev: ${stdParts.joinToString(", ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Averaging info
        val avgParts = mutableListOf<String>()
        c.averagedSamples?.let { avgParts += "$it samples" }
        c.averageDurationMs?.let { avgParts += String.format(Locale.US, "%.1fs", it / 1000.0) }
        textAveraging?.apply {
            if (avgParts.isNotEmpty()) {
                text = "Averaging: ${avgParts.joinToString(", ")}"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Note
        textNote?.apply {
            val noteVal = c.note?.trim()
            if (!noteVal.isNullOrEmpty()) {
                text = "Note: $noteVal"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        // Badges
        applyRtkBadge(c)
        applyAccuracyBadge(c)
        // Hide badge row if both hidden
        rowBadges?.visibility = if ((badgeRtk?.visibility == View.VISIBLE) || (badgeAccuracy?.visibility == View.VISIBLE)) View.VISIBLE else View.GONE

        textEmpty?.visibility = View.GONE
        listOf(
            textName, textCoords, textTime, textProvider, textRtk, textHdop
        ).forEach { it?.visibility = View.VISIBLE }
    }

    private fun applyRtkBadge(c: com.example.surveyingapp.data.Coordinate) {
        val status = c.rtkStatus
        val tv = badgeRtk ?: return
        if (status.isNullOrBlank()) { tv.visibility = View.GONE; return }
        tv.text = status
        val color = when (status.uppercase(Locale.US)) {
            "FIX" -> 0xFF2E7D32.toInt()    // green
            "FLOAT" -> 0xFFEF6C00.toInt()  // orange
            "DGPS" -> 0xFF1976D2.toInt()   // blue
            "SINGLE" -> 0xFF607D8B.toInt() // blue gray
            else -> 0xFFC62828.toInt()      // red / invalid
        }
        tv.background?.let {
            tv.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
        tv.visibility = View.VISIBLE
    }

    private fun applyAccuracyBadge(c: com.example.surveyingapp.data.Coordinate) {
        val tv = badgeAccuracy ?: return
        val hAcc = c.horizontalAccuracyM ?: c.hdop?.let { // derive rough horizontal from HDOP * 0.6 (base UERE assumption)
            it * 0.6
        }
        if (hAcc == null) { tv.visibility = View.GONE; return }
        val textVal = if (hAcc < 10) String.format(Locale.US, "%.2fm", hAcc) else String.format(Locale.US, "%.0fm", hAcc)
        tv.text = textVal
        val (color, label) = when {
            hAcc <= 0.05 -> 0xFF2E7D32.toInt() to "HP"        // High Precision
            hAcc <= 0.10 -> 0xFF00897B.toInt() to "HQ"        // High Quality
            hAcc <= 0.30 -> 0xFFF9A825.toInt() to "MD"        // Moderate
            hAcc <= 1.0  -> 0xFFEF6C00.toInt() to "LO"        // Low
            else -> 0xFFC62828.toInt() to "PO"                // Poor
        }
        tv.text = "$label $textVal"
        tv.background?.let {
            tv.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
        tv.visibility = View.VISIBLE
    }
}
