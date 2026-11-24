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
     * known, e.g. "RS2+ · Fixed · H ±0.03 m" or "Internal GPS". For a model-linked point the source
     * device still comes first; [modelName] (or "Model linked") is used only when no source is known.
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

    /**
     * Height part of the position line: the chosen reference, or — when that is unavailable (e.g. no
     * receiver orthometric height) — the ellipsoidal height from [ellipsoidal], e.g. "397.000 m h". "—" only when
     * neither exists.
     */
    fun rowHeightText(
        chosen: app.surrealar.coords.ConvertedPosition,
        ellipsoidal: () -> app.surrealar.coords.ConvertedPosition?,
    ): String {
        if (chosen.height != null) return app.surrealar.coords.CoordinateFormat.height(chosen)
        val e = runCatching { ellipsoidal() }.getOrNull()?.takeIf { it.height != null }
        return e?.let { app.surrealar.coords.CoordinateFormat.height(it) } ?: "—"
    }

    /**
     * Source label: the external receiver's label or the capture method. A linked model is already
     * shown by the row icon, so its name is used only when nothing else is known about the source.
     */
    private fun sourceLabel(c: Coordinate, modelName: String?): String? =
        captureSourceLabel(c)
            ?: if (c.linkedModelId != null) modelName?.takeIf { it.isNotBlank() } ?: "Model linked" else null

    private fun captureSourceLabel(c: Coordinate): String? {
        val method = c.captureMethod?.lowercase(Locale.US)
        if (app.surrealar.data.local.db.ProviderTokens.isExternal(c.provider) || method == "external_gnss" || method == "rtk_receiver") {
            return c.sourceDevice?.takeIf { it.isNotBlank() } ?: "External GNSS"
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
