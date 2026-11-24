package app.surrealar.ui.rendermap

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.hasLinkedModel
import java.util.Locale

/**
 * Text for saved-point capture metadata on the map (drawer rows and the selected-point card). Pure:
 * only the saved point metadata is used, never live receiver status.
 */
internal object CoordinateText {

    /** "Source: RS2+ Float"-style summary from saved capture metadata; null when nothing useful. */
    fun captureSourceText(coord: Coordinate): String? {
        val device = friendlySource(coord.provider, coord.sourceDevice)
        val fix = coord.rtkStatus?.takeIf { it.isNotBlank() }?.let { friendlyFix(it) }
        val parts = listOfNotNull(device, fix)
        return if (parts.isEmpty()) null else "Source: " + parts.joinToString(" ")
    }

    fun friendlySource(provider: String?, sourceDevice: String?): String? = when {
        provider == null -> null
        // External receiver: the profile's short label ("RS2+", "RS4", …) when known.
        app.surrealar.data.local.db.ProviderTokens.isExternal(provider) ->
            app.surrealar.settings.model.ExternalReceiverProfile.fromLabel(sourceDevice)?.shortLabel ?: "External"
        provider.contains("internal", ignoreCase = true) -> "Internal"
        else -> null   // "fused"/unknown: don't claim a source we can't verify
    }

    fun friendlyFix(rtk: String): String = when (rtk.uppercase(Locale.US)) {
        "FIX" -> "Fix"
        "FLOAT" -> "Float"
        "DGPS" -> "DGPS"
        "SINGLE" -> "Single"
        "NONE", "INVALID" -> "No fix"
        else -> rtk
    }

    /**
     * Compact, surveyor-focused metadata line for a drawer row: "Elev 432.7 m · Float · RS2+ · Model".
     * [heightText] is the point's height in the configured system. Uses only the saved point
     * metadata; omits parts that aren't available. Empty → adapter shows lat/lon.
     */
    fun rowMeta(p: Coordinate, heightText: String): String {
        val parts = mutableListOf<String>()
        parts += "Elev $heightText"
        p.rtkStatus?.takeIf { it.isNotBlank() }?.let { parts += friendlyFix(it) }
        friendlySource(p.provider, p.sourceDevice)?.let { parts += it }
        if (p.hasLinkedModel) parts += "Model"
        return parts.joinToString(" · ")
    }
}
