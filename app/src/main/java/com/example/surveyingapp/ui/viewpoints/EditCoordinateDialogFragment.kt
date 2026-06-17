package com.example.surveyingapp.ui.viewpoints

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.EmbeddedModelLocation
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.ModelLocationConfidence
import com.example.surveyingapp.ui.models.ModelPickerActivity
import com.example.surveyingapp.util.GlbGeoreferenceDetector
import com.example.surveyingapp.util.UtmConverter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class EditCoordinateDialogFragment(
    private val coordinate: Coordinate,
    private val dbModels: List<Model> = emptyList(),
    private val onCoordinateEdited: (Coordinate) -> Unit
) : DialogFragment() {

    private var iconButtonRef: MaterialButton? = null
    private var editTextRef: EditText? = null

    // Preserves existing icon unless user picks a new model
    private var selectedIconKey: String = coordinate.icon

    // Pending location override — null means keep existing coordinate location
    private var pendingLatitude: Double? = null
    private var pendingLongitude: Double? = null
    private var pendingAltitude: Double? = null

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
        val iconButton = view.findViewById<MaterialButton>(R.id.button_icon)
        val locationText = view.findViewById<TextView>(R.id.text_location)
        locationText.visibility = View.GONE

        editTextRef = nameEdit
        iconButtonRef = iconButton
        nameEdit.setText(coordinate.name)
        noteEdit.setText(coordinate.note ?: "")

        iconButton.setOnClickListener {
            modelPickerLauncher.launch(
                ModelPickerActivity.newIntent(requireContext(), "Choose a Model")
            )
        }

        // Restore existing model icon on the button
        coordinate.icon
            .takeIf { it.startsWith("model:") }
            ?.removePrefix("model:")
            ?.let { id -> dbModels.firstOrNull { it.id == id } }
            ?.let { model -> applySelectedModel(model.id, model.name, model.thumbnailFilePath) }

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.edit_coordinate))
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEdit.text.toString().ifBlank { coordinate.name }
                val icon = selectedIconKey
                val note = noteEdit.text?.toString()?.trim()?.ifBlank { null }
                val lat = pendingLatitude ?: coordinate.latitude
                val lon = pendingLongitude ?: coordinate.longitude
                val alt = pendingAltitude ?: coordinate.altitude

                val changed = name != coordinate.name
                    || icon != coordinate.icon
                    || note != coordinate.note
                    || lat != coordinate.latitude
                    || lon != coordinate.longitude
                    || alt != coordinate.altitude

                if (changed) {
                    val locationMoved = lat != coordinate.latitude || lon != coordinate.longitude
                    // Recompute the projected UTM snapshot when the position moved (e.g. "Use
                    // Model Location"); otherwise keep the existing values to avoid needless churn.
                    val base = coordinate.copy(
                        name = name, icon = icon, note = note
                    )
                    val updated = if (locationMoved) {
                        val utm = try { UtmConverter.latLonToUtm(lat, lon) } catch (_: Exception) { null }
                        // Position now comes from the model file, so mark provenance accordingly
                        // (mirrors the Add dialog's "Use Model Location" behavior).
                        base.copy(
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            provider = "model",
                            captureMethod = "model_embedded",
                            easting = utm?.easting,
                            northing = utm?.northing,
                            utmZone = utm?.utmZone
                        )
                    } else {
                        base
                    }
                    onCoordinateEdited(updated)
                }
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

    /** Prefers the import-time embedded origin; falls back to detection for older models. */
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
                pendingLatitude = embedded.latitude
                pendingLongitude = embedded.longitude
                pendingAltitude = embedded.altitudeMeters
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
                    Log.w("EditCoordinateDialog", "Failed to decode thumbnail: $thumbnailPath", e)
                    null
                }
            }
            if (bmp != null) {
                iconButtonRef?.iconTint = null
                iconButtonRef?.icon = bmp.toDrawable(resources)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editTextRef?.clearFocus()
        editTextRef = null
        iconButtonRef = null
    }

    override fun onDetach() {
        super.onDetach()
        editTextRef = null
    }
}
