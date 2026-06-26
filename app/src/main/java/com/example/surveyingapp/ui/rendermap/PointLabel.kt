package com.example.surveyingapp.ui.rendermap

import java.util.Locale

/**
 * Point-label display mode for saved map markers, plus the pure (Android-free) text/format logic.
 * Cycles Off → Name → Elevation → Distance → Off. The actual on-map rendering lives in
 * [RenderMapFragment]; this is unit-testable.
 */
enum class PointLabelMode(val label: String, val prefKey: String) {
    OFF("Off", "off"),
    NAME("Name", "name"),
    ELEVATION("Elevation", "elevation"),
    DISTANCE("Distance", "distance");

    fun next(): PointLabelMode = when (this) {
        OFF -> NAME
        NAME -> ELEVATION
        ELEVATION -> DISTANCE
        DISTANCE -> OFF
    }

    companion object {
        val DEFAULT = OFF
        /** Resolves a persisted token (new prefKey or legacy enum name); unknown/null → [DEFAULT]. */
        fun fromPrefKey(value: String?): PointLabelMode =
            entries.firstOrNull { it.prefKey.equals(value, true) || it.name.equals(value, true) } ?: DEFAULT
    }
}

object PointLabel {

    private const val FALLBACK_NAME = "Point"

    /**
     * The label text for a point, or null when nothing should be shown (OFF mode).
     * NAME → name only. ELEVATION → name + "Elev X m" (name only if elevation absent).
     * DISTANCE → name + distance (name only if no live position — less cluttered than a placeholder).
     */
    fun labelText(
        mode: PointLabelMode,
        name: String,
        elevationMeters: Double?,
        distanceMeters: Double?,
    ): String? {
        val displayName = name.ifBlank { FALLBACK_NAME }
        return when (mode) {
            PointLabelMode.OFF -> null
            PointLabelMode.NAME -> displayName
            PointLabelMode.ELEVATION ->
                if (elevationMeters != null) "$displayName\n${formatElevation(elevationMeters)}" else displayName
            PointLabelMode.DISTANCE ->
                if (distanceMeters != null) "$displayName\n${formatDistance(distanceMeters)}" else displayName
        }
    }

    fun formatElevation(meters: Double): String =
        String.format(Locale.getDefault(), "Elev %.2f m", meters)

    fun formatDistance(meters: Double): String = when {
        meters < 1.0 -> String.format(Locale.getDefault(), "%.2f m", meters)
        meters < 10.0 -> String.format(Locale.getDefault(), "%.1f m", meters)
        meters < 1000.0 -> String.format(Locale.getDefault(), "%.0f m", meters)
        else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
    }

    /** Toolbar button second line: just the mode label ("Off"/"Name"/"Elevation"/"Distance"). */
    fun buttonLabel(mode: PointLabelMode): String = mode.label

    /** Accessibility description: current state + what the next tap will do. */
    fun contentDescription(mode: PointLabelMode): String = when (mode) {
        PointLabelMode.OFF -> "Point labels off. Tap to show point names."
        PointLabelMode.NAME -> "Point labels show names. Tap to show names and elevation."
        PointLabelMode.ELEVATION -> "Point labels show elevation. Tap to show names and distance."
        PointLabelMode.DISTANCE -> "Point labels show distance. Tap to turn labels off."
    }
}
