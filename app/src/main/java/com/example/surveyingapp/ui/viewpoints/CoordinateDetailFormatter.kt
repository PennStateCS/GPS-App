package com.example.surveyingapp.ui.viewpoints

import java.util.Locale

/**
 * Pure display formatting and label mapping for the coordinate detail screen.
 *
 * Extracted from [CoordinateDetailFragment] so the user-visible strings are unit-testable and
 * defined in one place. Contains NO Android views/Context — only `String`/number formatting.
 * All wording is preserved exactly from the previous in-fragment implementation; changing any of
 * these strings is a UI-wording change.
 */
object CoordinateDetailFormatter {

    fun fmt6(v: Double): String = String.format(Locale.US, "%.6f", v)
    fun fmtM2(v: Double): String = String.format(Locale.US, "%.2f m", v)
    fun fmtM3(v: Double): String = String.format(Locale.US, "%.3f m", v)
    fun fmtDop(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun rtkLabel(s: String?): String = when (s?.uppercase(Locale.US)) {
        "FIX"    -> "Fixed (RTK)"
        "FLOAT"  -> "Float (RTK)"
        "DGPS"   -> "DGPS"
        "SINGLE" -> "Single"
        else     -> s ?: "--"
    }

    fun captureMethodLabel(m: String?): String? = when (m?.lowercase(Locale.US)) {
        "internal_gps"   -> "Internal GPS"
        "external_gnss"  -> "External GNSS"
        "rtk_receiver"   -> "RTK Receiver"
        "model_embedded" -> "Model embedded location"
        "map_tap"        -> "Map tap"
        "manual"         -> "Manual entry"
        "imported"       -> "Imported"
        "averaged"       -> "Averaged"
        null, ""         -> null
        else             -> m
    }

    fun providerLabel(p: String?): String? = when (p?.lowercase(Locale.US)) {
        "fused"        -> "Internal GPS (fused)"
        "rs2-tcp"      -> "External GNSS (TCP)"
        "rs2-bt"       -> "External GNSS (Bluetooth)"
        "rs2-external" -> "External GNSS"
        "model"        -> "Model"
        null, "", "other" -> null
        else           -> p
    }

    fun fmtDistance(m: Double): String = when {
        m < 1000.0 -> String.format(Locale.US, "%.1f m", m)
        else       -> String.format(Locale.US, "%.2f km", m / 1000.0)
    }

    fun cardinalDir(deg: Double): String = when {
        deg < 22.5 || deg >= 337.5 -> "N"
        deg < 67.5  -> "NE"
        deg < 112.5 -> "E"
        deg < 157.5 -> "SE"
        deg < 202.5 -> "S"
        deg < 247.5 -> "SW"
        deg < 292.5 -> "W"
        else        -> "NW"
    }

    /** Horizontal-accuracy badge text, e.g. "H ±0.42 m". Precision tightens for small values. */
    fun accuracyBadgeText(hAccM: Double): String = when {
        hAccM < 1.0  -> String.format(Locale.US, "H ±%.2f m", hAccM)
        hAccM < 10.0 -> String.format(Locale.US, "H ±%.1f m", hAccM)
        else         -> String.format(Locale.US, "H ±%.0f m", hAccM)
    }
}
