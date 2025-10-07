package com.example.surveyingapp.ui.viewpoints

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.di.HasGnssGraph
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.util.GeoProjection
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.Locale
import kotlin.coroutines.resume

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

    // Reference to EditText for proper cleanup
    private var editTextRef: EditText? = null

    // Variables to store the GPS coordinates
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var altitude: Double = 0.0

    // Extra quality/metadata captured from external RS2+/NMEA fix
    private var providerStr: String = "fused"
    private var rtkStatusStr: String? = null
    private var satsUsedVal: Int? = null
    private var hdopVal: Double? = null
    private var hAccVal: Double? = null
    private var vAccVal: Double? = null
    private var correctionSourceStr: String? = null
    private var correctionAgeSeconds: Double? = null
    private var altitudeMslVal: Double? = null
    private var geoidSeparationVal: Double? = null
    private var crsEpsgVal: Int? = 4326

    // Std deviations from GST
    private var stdLatVal: Double? = null
    private var stdLonVal: Double? = null
    private var stdAltVal: Double? = null

    // Timestamp to persist with the point
    private var capturedTimestampMs: Long = System.currentTimeMillis()

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

        // Store reference to EditText for proper cleanup
        editTextRef = nameEdit

        // Set up icon choices with custom adapter (updated icons)
        val icons = listOf(
            "ic_pin", "ic_home", "ic_star", "ic_circle", "ic_square", "ic_triangle", "ic_diamond"
        )
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

        // Begin one-shot location acquisition based on settings
        locationText.text = getString(R.string.fetching_location)
        lifecycleScope.launch {
            val sourceSetting = runCatching { SurveyingApp.settingsRepo.locationSource.first() }
                .getOrDefault(LocationSourceType.INTERNAL)
            if (sourceSetting == LocationSourceType.INTERNAL) {
                fetchInternalOneShot(locationText)
            } else {
                fetchExternalOneShot(locationText)
            }
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

                // Compute UTM projection from current lat/lon
                val utm = try { GeoProjection.wgs84ToUtm(latitude, longitude) } catch (_: Exception) { null }
                val eastingVal = utm?.easting
                val northingVal = utm?.northing
                val utmZoneVal = utm?.zoneString

                val point = Coordinate(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude, // ellipsoidal
                    timestamp = capturedTimestampMs,
                    icon = icon,
                    color = color,
                    provider = providerStr,
                    rtkStatus = rtkStatusStr,
                    satsUsed = satsUsedVal,
                    hdop = hdopVal,
                    horizontalAccuracyM = hAccVal,
                    verticalAccuracyM = vAccVal,
                    correctionSource = correctionSourceStr,
                    correctionAgeS = correctionAgeSeconds,
                    altitudeMsl = altitudeMslVal,
                    geoidSeparationM = geoidSeparationVal,
                    crsEpsg = crsEpsgVal,
                    easting = eastingVal,
                    northing = northingVal,
                    utmZone = utmZoneVal,
                    stdLatM = stdLatVal,
                    stdLonM = stdLonVal,
                    stdAltM = stdAltVal
                )

                // Call the callback function to return the new coordinate
                onPointAdded(point)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private suspend fun fetchInternalOneShot(locationText: TextView) {
        val fineGranted = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationText.text = getString(R.string.location_permission_required)
            return
        }
        val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        val cts = CancellationTokenSource()
        val priority = when {
            fineGranted && highAccuracy -> Priority.PRIORITY_HIGH_ACCURACY
            fineGranted -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val result = withTimeoutOrNull(6_000L) { // shorter timeout to move to fallback quickly on emulator
            @Suppress("MissingPermission")
            fused.getCurrentLocation(priority, cts.token).awaitSafe()
        }
        if (result != null) {
            latitude = result.latitude
            longitude = result.longitude
            altitude = result.altitude
            providerStr = "fused"
            hAccVal = if (result.hasAccuracy()) result.accuracy.toDouble() else null
            vAccVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && result.hasVerticalAccuracy()) result.verticalAccuracyMeters.toDouble() else null
            val mode = if (highAccuracy && fineGranted) "INTERNAL-HIGH" else "INTERNAL"
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, mode)
            capturedTimestampMs = System.currentTimeMillis()
            return
        }
        // Try last known location
        val last = try {
            @Suppress("MissingPermission")
            fused.lastLocation.awaitSafe()
        } catch (_: Exception) { null }
        if (last != null) {
            latitude = last.latitude
            longitude = last.longitude
            altitude = last.altitude
            providerStr = "fused"
            hAccVal = if (last.hasAccuracy()) last.accuracy.toDouble() else null
            vAccVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && last.hasVerticalAccuracy()) last.verticalAccuracyMeters.toDouble() else null
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, "LAST-KNOWN") + " (fallback)"
            capturedTimestampMs = System.currentTimeMillis()
            return
        }
        // Final emulator fallback: use a stable default (Googleplex) so user can edit name and save quickly
        latitude = 37.4219999
        longitude = -122.0840575
        altitude = 0.0
        providerStr = "fused"
        locationText.text = String.format(Locale.US, "%.6f, %.6f, %.2fm (emulator fallback)", latitude, longitude, altitude)
        capturedTimestampMs = System.currentTimeMillis()
    }

    private suspend fun fetchExternalOneShot(locationText: TextView) {
        // Obtain a single external fix via the shared GNSS graph (switchboard)
        val fix = withTimeoutOrNull(12_000L) {
            val host = activity as? HasGnssGraph
            val flow = host?.gnssGraph?.bus?.fixes
            if (flow == null) null else withContext(Dispatchers.IO) {
                runCatching { flow.first() }.getOrNull()
            }
        }
        if (fix != null) {
            latitude = fix.latDeg
            longitude = fix.lonDeg
            altitude = fix.altEllipsoidalM ?: 0.0
            // Map provider enum to display/storage string (collapse external variants)
            providerStr = when (fix.provider) {
                Provider.INTERNAL -> "fused"
                Provider.RS2_EXTERNAL, Provider.RS2_BT, Provider.RS2_TCP, Provider.OTHER -> "external"
            }
            rtkStatusStr = fix.rtkStatus.name
            satsUsedVal = fix.satsUsed
            hdopVal = fix.hDop
            hAccVal = fix.hAccM
            vAccVal = fix.vAccM
            correctionAgeSeconds = fix.diffAgeS
            altitudeMslVal = fix.altMslM
            geoidSeparationVal = fix.geoidSeparationM
            crsEpsgVal = 4326 // WGS84
            capturedTimestampMs = fix.timeUtc.toEpochMilli()

            val mode = rtkStatusStr ?: "SINGLE"
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, mode)
        } else {
            locationText.text = getString(R.string.location_unavailable)
        }
    }

    // Small helper to await a Task<Location?> safely without adding full dependency
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitSafe(): T? =
        kotlinx.coroutines.suspendCancellableCoroutine<T?> { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { if (cont.isActive) cont.resume(null) }
            addOnCanceledListener { if (cont.isActive) cont.resume(null) }
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
            textView.text = iconName.removePrefix("ic_menu_").removePrefix("ic_")
                .replace('_', ' ') // allow future multi-word
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

            return view
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear EditText focus and references to prevent IME callback errors
        editTextRef?.clearFocus()
        editTextRef = null
    }

    override fun onDetach() {
        super.onDetach()
        // Additional cleanup for IME callbacks
        editTextRef = null
    }
}
