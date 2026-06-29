package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
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

    // ── Calculated display values (derived from saved data; never stored) ───────────────

    /**
     * Averaged-capture sample rate, e.g. "2.5 fixes/sec". Null when inputs are missing or the
     * duration is non-positive (avoids divide-by-zero).
     */
    fun captureRateText(samples: Int?, durationMs: Long?): String? {
        if (samples == null || durationMs == null || durationMs <= 0L) return null
        val rate = samples / (durationMs / 1000.0)
        return String.format(Locale.US, "%.1f fixes/sec", rate)
    }

    /**
     * Satellite usage summary: "18 used / 24 visible", or "18 used" / "24 visible" when only one is
     * known. Null when neither is available.
     */
    fun satelliteSummaryText(satsUsed: Int?, satsVisible: Int?): String? = when {
        satsUsed != null && satsVisible != null -> "$satsUsed used / $satsVisible visible"
        satsUsed != null  -> "$satsUsed used"
        satsVisible != null -> "$satsVisible visible"
        else -> null
    }

    /**
     * Differential/RTK correction freshness, e.g. "Fresh, 1.2 s old" for recent corrections or
     * "12.5 s old" for older ones. Null when missing or negative.
     */
    fun correctionFreshnessText(correctionAgeS: Double?): String? {
        if (correctionAgeS == null || correctionAgeS < 0.0) return null
        val age = String.format(Locale.US, "%.1f s old", correctionAgeS)
        return if (correctionAgeS <= FRESH_CORRECTION_AGE_S) "Fresh, $age" else age
    }

    /**
     * One-line survey-quality summary from fix status (primary) and accuracy (fallback), e.g.
     * "RTK fixed · survey grade", "RTK float · sub-meter", or "Approximate". Null when nothing is known.
     */
    fun surveyQualitySummaryText(rtkStatus: String?, horizontalAccuracyM: Double?, hdop: Double?): String? {
        if (rtkStatus.isNullOrBlank() && horizontalAccuracyM == null && hdop == null) return null
        return when (rtkStatus?.uppercase(Locale.US)) {
            "FIX"    -> "RTK fixed · survey grade"
            "FLOAT"  -> "RTK float · sub-meter"
            "DGPS"   -> "DGPS · meter-level"
            "SINGLE" -> "Single · approximate"
            else -> when {
                horizontalAccuracyM != null && horizontalAccuracyM < 0.05 -> "Survey grade"
                horizontalAccuracyM != null && horizontalAccuracyM < 0.5  -> "Sub-meter"
                horizontalAccuracyM != null && horizontalAccuracyM < 2.0  -> "Meter-level"
                else -> "Approximate"
            }
        }
    }

    /**
     * Compact model-placement summary, including only the fields that are present, e.g.
     * "scale 1.50× · yaw 90° · v-offset 1.50 m · origin (0.10, 0.20, 0.30) m". Null when no placement
     * override is set.
     */
    fun modelPlacementSummaryText(
        scale: Double?, yawDeg: Double?, pitchDeg: Double?, rollDeg: Double?,
        verticalOffsetM: Double?,
        originOffsetXM: Double?, originOffsetYM: Double?, originOffsetZM: Double?
    ): String? {
        val parts = buildList {
            scale?.let { add(String.format(Locale.US, "scale %.2f×", it)) }
            yawDeg?.let { add(String.format(Locale.US, "yaw %.0f°", it)) }
            pitchDeg?.let { add(String.format(Locale.US, "pitch %.0f°", it)) }
            rollDeg?.let { add(String.format(Locale.US, "roll %.0f°", it)) }
            verticalOffsetM?.let { add(String.format(Locale.US, "v-offset %.2f m", it)) }
            if (originOffsetXM != null || originOffsetYM != null || originOffsetZM != null) {
                add(String.format(
                    Locale.US, "origin (%.2f, %.2f, %.2f) m",
                    originOffsetXM ?: 0.0, originOffsetYM ?: 0.0, originOffsetZM ?: 0.0
                ))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // ── Compact values for the Survey/GNSS stat tiles ──────────────────────────────────

    /** Short fix-status label for a stat tile: "Fixed", "Float", "DGPS", "Single", … Null when absent. */
    fun fixShortLabel(rtkStatus: String?): String? = when (rtkStatus?.uppercase(Locale.US)) {
        null, ""  -> null
        "FIX"     -> "Fixed"
        "FLOAT"   -> "Float"
        "DGPS"    -> "DGPS"
        "SINGLE"  -> "Single"
        "NONE"    -> "No fix"
        "INVALID" -> "Invalid"
        else      -> rtkStatus
    }

    /** Fix-tile value: "Fixed RTK" for a fixed RTK solution, otherwise the short label. Null when absent. */
    fun fixTileValue(rtkStatus: String?): String? {
        val short = fixShortLabel(rtkStatus) ?: return null
        return if (rtkStatus?.uppercase(Locale.US) == "FIX") "Fixed RTK" else short
    }

    /** Compact accuracy value for a stat tile, e.g. "±0.42 m". Null when absent. */
    fun accuracyTileText(m: Double?): String? = when {
        m == null    -> null
        m < 1.0      -> String.format(Locale.US, "±%.2f m", m)
        m < 10.0     -> String.format(Locale.US, "±%.1f m", m)
        else         -> String.format(Locale.US, "±%.0f m", m)
    }

    /** Compact satellite count for a stat tile, e.g. "18/24", "18", or "24". Null when neither is known. */
    fun satellitesTileText(satsUsed: Int?, satsVisible: Int?): String? = when {
        satsUsed != null && satsVisible != null -> "$satsUsed/$satsVisible"
        satsUsed != null    -> "$satsUsed"
        satsVisible != null -> "$satsVisible"
        else -> null
    }

    /**
     * Read-only multi-line location summary for the Edit dialog: latitude, longitude, altitude, and
     * UTM (only when available). Falls back to a friendly message rather than showing "null"/"NaN"
     * when the position is not finite.
     */
    fun locationSummary(c: Coordinate): String {
        if (!c.latitude.isFinite() || !c.longitude.isFinite()) {
            return "No saved location details available"
        }
        val lines = mutableListOf(
            "Latitude: ${fmt6(c.latitude)}°",
            "Longitude: ${fmt6(c.longitude)}°",
        )
        if (c.altitude.isFinite()) lines += "Altitude: ${fmtM2(c.altitude)}"
        val e = c.easting; val n = c.northing; val zone = c.utmZone
        if (e != null && n != null && !zone.isNullOrBlank()) {
            lines += "UTM: $zone ${String.format(Locale.US, "%.3f", e)} E, ${String.format(Locale.US, "%.3f", n)} N"
        }
        return lines.joinToString("\n")
    }

    /** Whether the audit "Record history" rows add information beyond the captured timestamp. */
    data class RecordHistory(val showCreated: Boolean, val showUpdated: Boolean) {
        val anyShown: Boolean get() = showCreated || showUpdated
    }

    /**
     * Decides whether Created / Last-updated should be shown, hiding values that merely duplicate the
     * captured time (epoch-minute comparison, timezone-independent):
     *  - Created hidden when it is the same minute as Captured (shown as a fallback when there is no
     *    captured timestamp).
     *  - Last updated shown only when it is later than Created and not in the same minute.
     */
    fun recordHistoryVisibility(capturedMs: Long, createdAt: Long, updatedAt: Long): RecordHistory {
        fun sameMinute(a: Long, b: Long): Boolean = a > 0L && b > 0L && a / 60_000L == b / 60_000L
        val showCreated = createdAt > 0L && !sameMinute(createdAt, capturedMs)
        val showUpdated = updatedAt > 0L && updatedAt > createdAt && !sameMinute(updatedAt, createdAt)
        return RecordHistory(showCreated, showUpdated)
    }

    /** Corrections at or below this age (seconds) are labeled "Fresh". */
    private const val FRESH_CORRECTION_AGE_S = 5.0
}
