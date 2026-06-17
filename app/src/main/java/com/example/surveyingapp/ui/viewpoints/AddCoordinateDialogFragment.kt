package com.example.surveyingapp.ui.viewpoints

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.EmbeddedModelLocation
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.ModelLocationConfidence
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.ui.models.ModelPickerActivity
import com.example.surveyingapp.util.GlbGeoreferenceDetector
import com.example.surveyingapp.util.UtmConverter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume

/** Default coordinate marker color (app primary blue). Not user-selectable. */
private const val DEFAULT_COORDINATE_COLOR = 0xFF155DA8.toInt()

@AndroidEntryPoint
class AddCoordinateDialogFragment(
    private val highAccuracy: Boolean = true,
    private val dbModels: List<Model> = emptyList(),
    private val onPointAdded: (Coordinate) -> Unit
) : DialogFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    private var editTextRef: EditText? = null
    private var locationTextRef: TextView? = null
    private var iconButtonRef: MaterialButton? = null
    private var selectedIconKey: String? = null

    // Captured position — may be overridden by "Use Model Location"
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var altitude: Double = 0.0

    // Quality/metadata from fix
    private var providerStr: String = "fused"
    private var rtkStatusStr: String? = null
    private var satsUsedVal: Int? = null
    private var satsVisibleVal: Int? = null
    private var hdopVal: Double? = null
    private var vDopVal: Double? = null
    private var pDopVal: Double? = null
    private var hAccVal: Double? = null
    private var vAccVal: Double? = null
    private var correctionSourceStr: String? = null
    private var correctionAgeSeconds: Double? = null
    private var correctionStationIdStr: String? = null
    private var speedMpsVal: Double? = null
    private var courseDegVal: Double? = null
    private var timestampSourceStr: String? = null
    private var multipathIndexVal: Double? = null
    private var altitudeMslVal: Double? = null
    private var geoidSeparationVal: Double? = null
    private var crsEpsgVal: Int? = 4326
    private var captureMethodStr: String? = null
    private var stdLatVal: Double? = null
    private var stdLonVal: Double? = null
    private var stdAltVal: Double? = null
    private var capturedTimestampMs: Long = System.currentTimeMillis()

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_ID)
                ?: return@registerForActivityResult
            val name = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_NAME)
            val thumbnailPath = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_THUMBNAIL_PATH)
            onModelSelected(id, name, thumbnailPath)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_add_point, null)

        val nameEdit = view.findViewById<EditText>(R.id.edit_point_name)
        val noteEdit = view.findViewById<EditText>(R.id.edit_point_note)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        val iconButton = view.findViewById<MaterialButton>(R.id.button_icon)

        editTextRef = nameEdit
        locationTextRef = locationText
        iconButtonRef = iconButton

        // Prefill name from settings
        lifecycleScope.launch {
            val coordSettings = SurveyingApp.settingsRepo.coordinateDisplaySettings.first()
            val prefix = coordSettings.defaultNamePrefix
            val proposedName = if (coordSettings.autoIncrementNames) {
                val count = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext()).coordinateDao().count()
                }
                "$prefix ${count + 1}"
            } else {
                prefix
            }
            editTextRef?.setText(proposedName)
        }

        iconButton.setOnClickListener {
            modelPickerLauncher.launch(
                ModelPickerActivity.newIntent(requireContext(), "Choose a Model")
            )
        }

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

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.add_coordinate_title))
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameEdit.text.toString().ifBlank { "Unnamed Coordinate" }
                val icon = selectedIconKey ?: ""
                val utm = try { UtmConverter.latLonToUtm(latitude, longitude) } catch (_: Exception) { null }

                val point = Coordinate(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    timestamp = capturedTimestampMs,
                    icon = icon,
                    color = DEFAULT_COORDINATE_COLOR,
                    provider = providerStr,
                    rtkStatus = rtkStatusStr,
                    satsUsed = satsUsedVal,
                    satsVisible = satsVisibleVal,
                    hdop = hdopVal,
                    vDop = vDopVal,
                    pDop = pDopVal,
                    horizontalAccuracyM = hAccVal,
                    verticalAccuracyM = vAccVal,
                    correctionSource = correctionSourceStr,
                    correctionAgeS = correctionAgeSeconds,
                    correctionStationId = correctionStationIdStr,
                    speedMps = speedMpsVal,
                    courseDeg = courseDegVal,
                    timestampSource = timestampSourceStr,
                    multipathIndex = multipathIndexVal,
                    altitudeMsl = altitudeMslVal,
                    geoidSeparationM = geoidSeparationVal,
                    crsEpsg = crsEpsgVal,
                    easting = utm?.easting,
                    northing = utm?.northing,
                    utmZone = utm?.utmZone,
                    note = noteEdit.text?.toString()?.trim()?.ifBlank { null },
                    captureMethod = captureMethodStr,
                    stdLatM = stdLatVal,
                    stdLonM = stdLonVal,
                    stdAltM = stdAltVal
                )
                onPointAdded(point)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    // ── Model selection + embedded-location detection ──────────────────────────

    private fun onModelSelected(modelId: String, name: String?, thumbnailPath: String?) {
        lifecycleScope.launch {
            val model = dbModels.firstOrNull { it.id == modelId }
            val embedded = resolveEmbeddedLocation(model)

            if (embedded != null) {
                showModelLocationDialog(embedded, modelId, name, thumbnailPath)
            } else {
                applySelectedModel(modelId, name, thumbnailPath)
            }
        }
    }

    /**
     * Resolves a model's embedded geographic origin: prefers the value captured at import
     * (georeferenced GLBs are reprojected on import, erasing the in-file signal), and falls
     * back to on-the-fly detection for models imported before that was stored.
     */
    private suspend fun resolveEmbeddedLocation(model: Model?): EmbeddedModelLocation? {
        model ?: return null
        if (model.embeddedLatitude != null && model.embeddedLongitude != null) {
            return EmbeddedModelLocation(
                latitude = model.embeddedLatitude,
                longitude = model.embeddedLongitude,
                altitudeMeters = model.embeddedAltitudeM,
                confidence = ModelLocationConfidence.HIGH,
                source = "GLB_POSITION_WGS_LIKE"
            )
        }
        val fp = model.filePath
        if (fp.isBlank()) return null
        return withContext(Dispatchers.IO) { GlbGeoreferenceDetector.detect(File(fp)) }
    }

    private fun showModelLocationDialog(
        embedded: EmbeddedModelLocation,
        modelId: String,
        modelName: String?,
        thumbnailPath: String?
    ) {
        val altStr = embedded.altitudeMeters?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"
        val confidenceNote = when (embedded.confidence) {
            ModelLocationConfidence.HIGH   -> ""
            ModelLocationConfidence.MEDIUM -> "\n(Confidence: medium)"
            ModelLocationConfidence.LOW    -> "\n(Confidence: low)"
        }
        val message = String.format(
            Locale.US,
            "This model appears to contain an embedded location:\n\n" +
                "Latitude:  %.7f\nLongitude: %.7f\nAltitude:  %s%s\n\n" +
                "How would you like to place it?",
            embedded.latitude, embedded.longitude, altStr, confidenceNote
        )

        if (!isAdded || activity == null) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Model Location Detected")
            .setMessage(message)
            .setPositiveButton("Use Model Location") { _, _ ->
                latitude = embedded.latitude
                longitude = embedded.longitude
                altitude = embedded.altitudeMeters ?: altitude
                providerStr = "model"
                captureMethodStr = "model_embedded"
                // Show updated location in the dialog
                locationTextRef?.text = String.format(
                    Locale.US, "From model: %.6f, %.6f, %.2fm", latitude, longitude, altitude
                )
                applySelectedModel(modelId, modelName, thumbnailPath)
            }
            .setNegativeButton("Use Current Coordinate") { _, _ ->
                applySelectedModel(modelId, modelName, thumbnailPath)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun applySelectedModel(modelId: String, name: String?, thumbnailPath: String?) {
        selectedIconKey = "model:$modelId"
        val button = iconButtonRef ?: return
        button.text = name ?: "Selected model"

        if (thumbnailPath.isNullOrBlank()) {
            button.icon = null
            return
        }

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val file = File(thumbnailPath)
                    if (file.exists()) BitmapFactory.decodeFile(thumbnailPath) else null
                } catch (e: Exception) {
                    Log.w("AddCoordinateDialog", "Failed to decode thumbnail: $thumbnailPath", e)
                    null
                }
            }
            if (bmp != null) {
                iconButtonRef?.iconTint = null
                iconButtonRef?.icon = bmp.toDrawable(resources)
            }
        }
    }

    // ── Location fetching ──────────────────────────────────────────────────────

    private suspend fun fetchInternalOneShot(locationText: TextView) {
        captureMethodStr = "internal_gps"
        val fineGranted = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationText.text = getString(R.string.location_permission_required)
            return
        }
        val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        val cts = CancellationTokenSource()
        val priority = if (fineGranted && highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val result = withTimeoutOrNull(6_000L) {
            @Suppress("MissingPermission")
            fused.getCurrentLocation(priority, cts.token).awaitSafe()
        }
        if (result != null) {
            latitude = result.latitude; longitude = result.longitude; altitude = result.altitude
            providerStr = "fused"
            hAccVal = if (result.hasAccuracy()) result.accuracy.toDouble() else null
            vAccVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && result.hasVerticalAccuracy()) result.verticalAccuracyMeters.toDouble() else null
            val mode = if (highAccuracy && fineGranted) "INTERNAL-HIGH" else "INTERNAL"
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, mode)
            capturedTimestampMs = System.currentTimeMillis()
            return
        }
        val last = try { @Suppress("MissingPermission") fused.lastLocation.awaitSafe() } catch (_: Exception) { null }
        if (last != null) {
            latitude = last.latitude; longitude = last.longitude; altitude = last.altitude
            providerStr = "fused"
            hAccVal = if (last.hasAccuracy()) last.accuracy.toDouble() else null
            vAccVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && last.hasVerticalAccuracy()) last.verticalAccuracyMeters.toDouble() else null
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, "LAST-KNOWN") + " (fallback)"
            capturedTimestampMs = System.currentTimeMillis()
            return
        }
        latitude = 37.4219999; longitude = -122.0840575; altitude = 0.0; providerStr = "fused"
        locationText.text = String.format(Locale.US, "%.6f, %.6f, %.2fm (emulator fallback)", latitude, longitude, altitude)
        capturedTimestampMs = System.currentTimeMillis()
    }

    private suspend fun fetchExternalOneShot(locationText: TextView) {
        captureMethodStr = "external_gnss"
        val fix = withTimeoutOrNull(12_000L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    fixSwitchboard.fixes.first { it.provider != Provider.INTERNAL }
                }.getOrNull()
            }
        }
        if (fix != null) {
            latitude = fix.latDeg; longitude = fix.lonDeg; altitude = fix.altEllipsoidalM ?: 0.0
            providerStr = when (fix.provider) {
                Provider.INTERNAL -> "fused"
                else -> "external"
            }
            rtkStatusStr = fix.rtkStatus.name
            satsUsedVal = fix.satsUsed; satsVisibleVal = fix.satsVisible
            hdopVal = fix.hDop; vDopVal = fix.vDop; pDopVal = fix.pDop
            hAccVal = fix.hAccM; vAccVal = fix.vAccM
            correctionAgeSeconds = fix.diffAgeS
            correctionStationIdStr = fix.correctionStationId
            speedMpsVal = fix.speedMps; courseDegVal = fix.courseDeg
            timestampSourceStr = fix.timestampSource.name
            multipathIndexVal = fix.multipathIndex
            altitudeMslVal = fix.altMslM; geoidSeparationVal = fix.geoidSeparationM
            stdLatVal = fix.stdDevNorthM; stdLonVal = fix.stdDevEastM; stdAltVal = fix.stdDevUpM
            crsEpsgVal = 4326
            capturedTimestampMs = fix.timeUtc.toEpochMilli()
            val mode = rtkStatusStr ?: "SINGLE"
            locationText.text = getString(R.string.location_label_with_mode, latitude, longitude, altitude, mode)
        } else {
            locationText.text = getString(R.string.location_unavailable)
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitSafe(): T? =
        kotlinx.coroutines.suspendCancellableCoroutine<T?> { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { if (cont.isActive) cont.resume(null) }
            addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        editTextRef?.clearFocus()
        editTextRef = null
        iconButtonRef = null
        locationTextRef = null
    }

    override fun onDetach() {
        super.onDetach()
        editTextRef = null
    }
}
