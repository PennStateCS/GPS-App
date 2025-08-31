package com.example.surveyingapp.ui.viewpoints

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
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
import com.example.surveyingapp.domain.model.Coordinate
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.example.surveyingapp.ui.viewpoints.EditCoordinateDialogFragment

class CoordinateDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        fun newInstance(id: String): CoordinateDetailFragment = CoordinateDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    private lateinit var viewModel: CoordinatesViewModel
    private var currentId: String? = null

    private var textName: TextView? = null
    private var textLatitude: TextView? = null
    private var textLongitude: TextView? = null
    private var textAltitude: TextView? = null
    private var textTime: TextView? = null
    private var textProvider: TextView? = null
    private var textRtk: TextView? = null
    private var textHdop: TextView? = null
    private var textEmpty: TextView? = null
    private var textAccuracy: TextView? = null
    private var textSats: TextView? = null
    private var textCorrection: TextView? = null
    private var textProjection: TextView? = null
    private var textStdDev: TextView? = null
    private var textAveraging: TextView? = null
    private var badgeRtk: TextView? = null
    private var badgeAccuracy: TextView? = null
    private var rowBadges: View? = null
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var lastCoordinate: Coordinate? = null
    private var textDate: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_coordinate_detail, container, false)
        textName = v.findViewById(R.id.text_name)
        textLatitude = v.findViewById(R.id.text_latitude)
        textLongitude = v.findViewById(R.id.text_longitude)
        textAltitude = v.findViewById(R.id.text_altitude)
        textTime = v.findViewById(R.id.text_time)
        textProvider = v.findViewById(R.id.text_provider)
        textRtk = v.findViewById(R.id.text_rtk)
        textHdop = v.findViewById(R.id.text_hdop)
        textEmpty = v.findViewById(R.id.text_empty)
        textAccuracy = v.findViewById(R.id.text_accuracy)
        textSats = v.findViewById(R.id.text_sats)
        textCorrection = v.findViewById(R.id.text_correction)
        textProjection = v.findViewById(R.id.text_projection)
        textStdDev = v.findViewById(R.id.text_stddev)
        textAveraging = v.findViewById(R.id.text_averaging)
        badgeRtk = v.findViewById(R.id.badge_rtk)
        badgeAccuracy = v.findViewById(R.id.badge_accuracy)
        rowBadges = v.findViewById(R.id.row_badges)
        mapView = v.findViewById(R.id.mapView)
        textDate = v.findViewById(R.id.text_date)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(OnMapReadyCallback { map ->
            googleMap = map
            lastCoordinate?.let { updateMapMarker(it) }
        })
        return v
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }
    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        mapView?.onDestroy()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    private fun updateMapMarker(c: Coordinate) {
        googleMap?.let { map ->
            val latLng = LatLng(c.latitude, c.longitude)
            map.clear()
            val opts = MarkerOptions().position(latLng).title(c.name)
            buildMarkerDescriptor(c.icon, c.color)?.let { opts.icon(it) }
            map.addMarker(opts)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
        }
    }

    private fun buildMarkerDescriptor(iconName: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (iconName.isNullOrBlank()) return null
        val resId = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = (32 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        try { d.mutate().setColorFilter(colorInt, PorterDuff.Mode.SRC_IN) } catch (_: Exception) {}
        d.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)
        view.findViewById<View>(R.id.btn_edit_coordinate)?.setOnClickListener { launchEditDialog() }

        currentId = arguments?.getString(ARG_ID)
        loadCurrentId()
    }

    private fun loadCurrentId() {
        val id = currentId
        Log.d("CoordinateDetailFragment", "loadCurrentId called with id=$id")
        if (id.isNullOrBlank()) {
            Log.w("CoordinateDetailFragment", "ID is null or blank, showing empty")
            showEmpty(); return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d("CoordinateDetailFragment", "Getting coordinate from ViewModel for id=$id")
            val coord = viewModel.getById(id)
            if (coord == null) {
                Log.w("CoordinateDetailFragment", "Coordinate not found for id=$id")
                showEmpty()
            } else {
                Log.d("CoordinateDetailFragment", "Found coordinate: ${coord.name}")
                bindCoordinate(coord)
            }
        }
    }

    fun updateId(newId: String) {
        Log.d("CoordinateDetailFragment", "updateId called: old=$currentId new=$newId")
        if (newId == currentId) return
        currentId = newId
        loadCurrentId()
    }

    private fun showEmpty() {
        // Show not found message but keep table placeholders visible
        textEmpty?.visibility = View.VISIBLE
        textName?.text = "--"
        textLatitude?.text = "--"
        textLongitude?.text = "--"
        textAltitude?.text = "--"
        textTime?.text = "--"
        textProvider?.text = "--"
        textRtk?.text = "--"
        textHdop?.text = "--"
        textAccuracy?.text = "--"
        textSats?.text = "--"
        textCorrection?.text = "--"
        textProjection?.text = "--"
        textStdDev?.text = "--"
        textAveraging?.text = "--"
        textDate?.text = "--"
        // Keep everything visible (remove hiding logic)
    }

    private fun bindCoordinate(c: Coordinate) {
        lastCoordinate = c
        updateMapMarker(c)
        val dateObj = Date(c.timestamp)
        val numericDate = SimpleDateFormat("MM/dd/yyyy", Locale.US).format(dateObj)
        val time12 = SimpleDateFormat("h:mm:ss a", Locale.US).format(dateObj).lowercase(Locale.US)
        textEmpty?.visibility = View.GONE
        textName?.text = c.name.ifBlank { "--" }
        // Set date and time separately
        textDate?.text = numericDate
        textTime?.text = time12
        textLatitude?.text = String.format(Locale.US, "%.6f", c.latitude)
        textLongitude?.text = String.format(Locale.US, "%.6f", c.longitude)
        textAltitude?.text = String.format(Locale.US, "%.2f m", c.altitude)
        textProvider?.text = c.provider.ifBlank { "--" }
        textRtk?.text = c.rtkStatus?.ifBlank { "--" } ?: "--"
        textHdop?.text = c.hdop?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

        // Accuracy (values only H/V)
        val accVals = mutableListOf<String>()
        c.horizontalAccuracyM?.let { accVals += String.format(Locale.US, "%.2f m", it) }
        c.verticalAccuracyM?.let { accVals += String.format(Locale.US, "%.2f m", it) }
        textAccuracy?.text = if (accVals.isNotEmpty()) accVals.joinToString(" / ") else "--"

        // Satellites
        textSats?.text = c.satsUsed?.toString() ?: "--"

        // Correction (source · age) values only (age in s)
        val corrVals = mutableListOf<String>()
        c.correctionSource?.let { if (it.isNotBlank()) corrVals += it }
        c.correctionAgeS?.let { corrVals += String.format(Locale.US, "%.1fs", it) }
        textCorrection?.text = if (corrVals.isNotEmpty()) corrVals.joinToString(" · ") else "--"

        // Projection (E · N · Zone · EPSG) values only (drop prefixes and 'EPSG:')
        val projVals = mutableListOf<String>()
        if (c.easting != null) projVals += String.format(Locale.US, "%.2f", c.easting)
        if (c.northing != null) projVals += String.format(Locale.US, "%.2f", c.northing)
        c.utmZone?.let { if (it.isNotBlank()) projVals += it }
        c.crsEpsg?.let { projVals += "$it" }
        textProjection?.text = if (projVals.isNotEmpty()) projVals.joinToString(" · ") else "--"

        // Standard deviations (Lat · Lon · Alt) values only with ± symbol
        val stdVals = mutableListOf<String>()
        c.stdLatM?.let { stdVals += String.format(Locale.US, "±%.2f m", it) }
        c.stdLonM?.let { stdVals += String.format(Locale.US, "±%.2f m", it) }
        c.stdAltM?.let { stdVals += String.format(Locale.US, "±%.2f m", it) }
        textStdDev?.text = if (stdVals.isNotEmpty()) stdVals.joinToString(" · ") else "--"

        // Averaging (samples · duration)
        val avgVals = mutableListOf<String>()
        c.averagedSamples?.let { avgVals += "$it samples" }
        c.averageDurationMs?.let { avgVals += String.format(Locale.US, "%.1fs", it / 1000.0) }
        textAveraging?.text = if (avgVals.isNotEmpty()) avgVals.joinToString(" · ") else "--"

        applyRtkBadge(c)
        applyAccuracyBadge(c)
        rowBadges?.visibility = if ((badgeRtk?.visibility == View.VISIBLE) || (badgeAccuracy?.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    }

    private fun applyRtkBadge(c: Coordinate) {
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

    private fun applyAccuracyBadge(c: Coordinate) {
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

    fun launchEditDialog() {
        val current = lastCoordinate ?: return
        EditCoordinateDialogFragment(current) { updated ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.updateCoordinate(updated)
                lastCoordinate = updated
                bindCoordinate(updated)
            }
        }.show(parentFragmentManager, "edit_coordinate_dialog")
    }
}
