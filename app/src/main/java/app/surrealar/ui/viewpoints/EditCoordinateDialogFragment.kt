package app.surrealar.ui.viewpoints

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.domain.model.CaptureMethod
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.CoordinateModelLink
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.model.EmbeddedModelLocation
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.ModelLocationConfidence
import app.surrealar.ui.models.ModelPickerActivity
import app.surrealar.util.GlbGeoreferenceDetector
import app.surrealar.util.UtmConverter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Dialog for editing a saved coordinate's editable fields (e.g. name/note/model link) via the
 * repository. Does not alter the measured position.
 */
class EditCoordinateDialogFragment(
    private val coordinate: Coordinate,
    private val dbModels: List<Model> = emptyList(),
    private val onCoordinateEdited: (Coordinate) -> Unit
) : DialogFragment() {

    private var iconButtonRef: MaterialButton? = null
    private var editTextRef: EditText? = null
    private var placementSectionRef: View? = null

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
        // Read-only saved-location summary (this dialog does not edit position).
        val locationText = view.findViewById<TextView>(R.id.text_location)
        locationText.text = CoordinateDetailFormatter.locationSummary(coordinate)

        editTextRef = nameEdit
        iconButtonRef = iconButton
        nameEdit.setText(coordinate.name)
        noteEdit.setText(coordinate.note ?: "")

        iconButton.setOnClickListener {
            modelPickerLauncher.launch(
                ModelPickerActivity.newIntent(requireContext(), "Choose a Model")
            )
        }

        // ── Model placement section (collapsed; shown only when a model is linked) ──
        val placementSection = view.findViewById<View>(R.id.section_model_placement)
        val placementHeader = view.findViewById<TextView>(R.id.header_model_placement)
        val placementBody = view.findViewById<View>(R.id.body_model_placement)
        val editScale = view.findViewById<EditText>(R.id.edit_model_scale)
        val editYaw = view.findViewById<EditText>(R.id.edit_model_yaw)
        val editPitch = view.findViewById<EditText>(R.id.edit_model_pitch)
        val editRoll = view.findViewById<EditText>(R.id.edit_model_roll)
        val editVOff = view.findViewById<EditText>(R.id.edit_model_voffset)
        val editOffX = view.findViewById<EditText>(R.id.edit_model_offx)
        val editOffY = view.findViewById<EditText>(R.id.edit_model_offy)
        val editOffZ = view.findViewById<EditText>(R.id.edit_model_offz)
        placementSectionRef = placementSection

        fun prefill(e: EditText?, v: Double?) { if (v != null) e?.setText(v.toString()) }
        prefill(editScale, coordinate.modelScale); prefill(editYaw, coordinate.modelYawDeg)
        prefill(editPitch, coordinate.modelPitchDeg); prefill(editRoll, coordinate.modelRollDeg)
        prefill(editVOff, coordinate.modelVerticalOffsetM)
        prefill(editOffX, coordinate.modelOriginOffsetXM); prefill(editOffY, coordinate.modelOriginOffsetYM)
        prefill(editOffZ, coordinate.modelOriginOffsetZM)

        placementSection?.visibility = if (coordinate.linkedModelId != null) View.VISIBLE else View.GONE
        placementHeader?.setOnClickListener {
            val expand = placementBody?.visibility != View.VISIBLE
            placementBody?.visibility = if (expand) View.VISIBLE else View.GONE
            placementHeader.text = (if (expand) "▾ " else "▸ ") + "Model placement"
        }

        // Restore existing model icon on the button (new modelId column or legacy icon)
        coordinate.linkedModelId
            ?.let { id -> dbModels.firstOrNull { it.id == id } }
            ?.let { model -> applySelectedModel(model.id, model.name, model.thumbnailFilePath) }

        fun parseField(e: EditText?): Double? =
            e?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

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

                // Split the selected icon into the explicit model/built-in fields via the shared
                // compatibility helper (keeps the legacy icon column for backward compatibility).
                val linkedModelId = CoordinateModelLink.resolveModelId(null, icon)
                val builtinIconKey = CoordinateModelLink.resolveIconKey(null, null, icon)

                // Placement only applies when a model is linked; otherwise it is cleared.
                // Invalid values are dropped (→ null = use default) and the user is warned:
                // scale must be finite and > 0; angles/offsets must be finite.
                val warnings = mutableListOf<String>()
                fun scaleOf(e: EditText?): Double? = parseField(e)?.let {
                    if (it.isFinite() && it > 0.0) it else { warnings += "scale must be greater than 0"; null }
                }
                fun finiteOf(e: EditText?, label: String): Double? = parseField(e)?.let {
                    if (it.isFinite()) it else { warnings += "$label must be a number"; null }
                }
                val newScale = if (linkedModelId != null) scaleOf(editScale) else null
                val newYaw   = if (linkedModelId != null) finiteOf(editYaw, "yaw") else null
                val newPitch = if (linkedModelId != null) finiteOf(editPitch, "pitch") else null
                val newRoll  = if (linkedModelId != null) finiteOf(editRoll, "roll") else null
                val newVOff  = if (linkedModelId != null) finiteOf(editVOff, "vertical offset") else null
                val newOffX  = if (linkedModelId != null) finiteOf(editOffX, "origin offset X") else null
                val newOffY  = if (linkedModelId != null) finiteOf(editOffY, "origin offset Y") else null
                val newOffZ  = if (linkedModelId != null) finiteOf(editOffZ, "origin offset Z") else null
                if (warnings.isNotEmpty()) {
                    Toast.makeText(requireContext(), "Ignored invalid placement: ${warnings.joinToString()}", Toast.LENGTH_LONG).show()
                }

                val placementChanged = newScale != coordinate.modelScale || newYaw != coordinate.modelYawDeg ||
                    newPitch != coordinate.modelPitchDeg || newRoll != coordinate.modelRollDeg ||
                    newVOff != coordinate.modelVerticalOffsetM || newOffX != coordinate.modelOriginOffsetXM ||
                    newOffY != coordinate.modelOriginOffsetYM || newOffZ != coordinate.modelOriginOffsetZM

                val changed = name != coordinate.name
                    || icon != coordinate.icon
                    || note != coordinate.note
                    || lat != coordinate.latitude
                    || lon != coordinate.longitude
                    || alt != coordinate.altitude
                    || placementChanged

                if (changed) {
                    val locationMoved = lat != coordinate.latitude || lon != coordinate.longitude
                    // Recompute the projected UTM snapshot when the position moved (e.g. "Use
                    // Model Location"); otherwise keep the existing values to avoid needless churn.
                    val base = coordinate.copy(
                        name = name, icon = icon, note = note,
                        modelId = linkedModelId, iconKey = builtinIconKey,
                        updatedAt = System.currentTimeMillis(),
                        modelScale = newScale, modelYawDeg = newYaw,
                        modelPitchDeg = newPitch, modelRollDeg = newRoll,
                        modelVerticalOffsetM = newVOff,
                        modelOriginOffsetXM = newOffX, modelOriginOffsetYM = newOffY, modelOriginOffsetZM = newOffZ
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
                            captureMethod = CaptureMethod.MODEL_EMBEDDED.storageValue,
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
        selectedIconKey = CoordinateModelLink.toLegacyIcon(modelId)
        // A model is now linked → reveal the placement section.
        placementSectionRef?.visibility = View.VISIBLE
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
        placementSectionRef = null
    }

    override fun onDetach() {
        super.onDetach()
        editTextRef = null
    }
}
