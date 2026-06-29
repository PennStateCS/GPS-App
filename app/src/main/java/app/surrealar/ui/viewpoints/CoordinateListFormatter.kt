package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.linkedModelId
import java.util.Locale

/**
 * Pure text building for the coordinate list rows (left pane). Extracted from
 * [SimpleCoordinatesAdapter] so the row wording is unit-testable. No Android views/Context.
 */
object CoordinateListFormatter {

    /**
     * Second-line summary: source · fix · horizontal accuracy, including only the parts that are
     * known, e.g. "RS2+ · Fixed · H ±0.03 m", "Model linked · Fixed · H ±0.03 m", or "Internal GPS".
     * [modelName] is the linked model's display name (null falls back to "Model linked").
     */
    fun summaryLine(c: Coordinate, modelName: String?): String {
        val parts = mutableListOf<String>()
        sourceLabel(c, modelName)?.let { parts += it }
        CoordinateDetailFormatter.fixShortLabel(c.rtkStatus)?.let { parts += it }
        c.horizontalAccuracyM?.let { parts += CoordinateDetailFormatter.accuracyBadgeText(it) }
        return parts.joinToString(" · ")
    }

    /** Third-line position, e.g. "40.123456, -74.123456". */
    fun latLonLine(c: Coordinate): String =
        String.format(Locale.US, "%.6f, %.6f", c.latitude, c.longitude)

    /** Source label: model name when linked, the external device/"RS2+", or the capture method. */
    private fun sourceLabel(c: Coordinate, modelName: String?): String? {
        if (c.linkedModelId != null) {
            return modelName?.takeIf { it.isNotBlank() } ?: "Model linked"
        }
        val method = c.captureMethod?.lowercase(Locale.US)
        if (c.provider.lowercase(Locale.US).contains("rs2") || method == "external_gnss" || method == "rtk_receiver") {
            return c.sourceDevice?.takeIf { it.isNotBlank() } ?: "RS2+"
        }
        return when (method) {
            "internal_gps"   -> "Internal GPS"
            "averaged"       -> "Averaged"
            "manual"         -> "Manual"
            "imported"       -> "Imported"
            "map_tap"        -> "Map tap"
            "model_embedded" -> "Model"
            else             -> null
        }
    }
}
