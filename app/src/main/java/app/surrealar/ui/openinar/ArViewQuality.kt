package app.surrealar.ui.openinar

/**
 * Pure mapping from ARCore Earth/geospatial pose accuracy + tracking + anchor-placement state to a
 * compact, user-facing "AR view quality" indicator for the AR screen.
 *
 * IMPORTANT scope: this describes the confidence of the **ARCore geospatial (AR) pose** used to place
 * anchors — NOT the GNSS/RS2 fix accuracy. The two are shown separately in the UI (the GNSS chip shows
 * the fix; this drives the AR-quality banner/chip). Missing accuracy is surfaced as "unknown", never
 * as 0.0 — a null accuracy must never read as a perfect fix.
 *
 * No Android/ARCore types here → fully unit-testable. Thresholds are centralized constants so they are
 * easy to tune without touching the fragment.
 */
object ArViewQuality {

    enum class Level { NOT_READY, UNKNOWN, LOW, FAIR, GOOD }

    /** Where the anchors currently stand relative to the placement gate. */
    enum class Placement { WAITING, PLACED, PLACED_BY_TIMEOUT, FAILED }

    // ── Tunable thresholds (ARCore geospatial pose accuracy) ─────────────────────────────────────
    const val GOOD_HACC_M = 2.0
    const val GOOD_YAW_DEG = 15.0
    const val FAIR_HACC_M = 5.0
    const val FAIR_YAW_DEG = 35.0
    /** Vertical accuracy (m) above which quality is capped at LOW regardless of horizontal/yaw. */
    const val POOR_VACC_M = 10.0

    const val HELPER_LOW = "Move slowly and scan nearby buildings/ground."
    const val HELPER_TIMEOUT = "Placed by timeout · accuracy may be low."

    /**
     * @param statusLabel      user-facing status ("Localizing AR…", "Improving AR accuracy…", "AR quality: Good").
     * @param accuracyText     horizontal accuracy as "±X.X m", or "unknown" when missing.
     * @param headingText      yaw/heading accuracy as "±Y°", or null when missing.
     * @param helperText       optional guidance shown when quality is low / placed by timeout.
     * @param modelsRenderable whether anchors are placed (models are/should be on screen).
     */
    data class Status(
        val level: Level,
        val statusLabel: String,
        val accuracyText: String,
        val headingText: String?,
        val helperText: String?,
        val modelsRenderable: Boolean,
    )

    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

    fun evaluate(
        earthTracking: Boolean,
        cameraTracking: Boolean,
        hAccM: Double?,
        vAccM: Double?,
        yawDeg: Double?,
        placement: Placement,
    ): Status {
        val h = hAccM.finiteOrNull()
        val v = vAccM.finiteOrNull()
        val y = yawDeg.finiteOrNull()
        val trackingReady = earthTracking && cameraTracking

        val accuracyText = h?.let { "±${"%.1f".format(it)} m" } ?: "unknown"
        val headingText = y?.let { "±${"%.0f".format(it)}°" }

        val level = when {
            !trackingReady -> Level.NOT_READY
            h == null || y == null -> Level.UNKNOWN
            else -> {
                val vaccPoor = v != null && v > POOR_VACC_M
                when {
                    h <= GOOD_HACC_M && y <= GOOD_YAW_DEG && !vaccPoor -> Level.GOOD
                    h <= FAIR_HACC_M && y <= FAIR_YAW_DEG && !vaccPoor -> Level.FAIR
                    else -> Level.LOW
                }
            }
        }

        val modelsRenderable = placement == Placement.PLACED || placement == Placement.PLACED_BY_TIMEOUT

        val statusLabel = when (placement) {
            Placement.FAILED -> "AR unavailable"
            Placement.WAITING -> if (level == Level.NOT_READY) "Localizing AR…" else "Improving AR accuracy…"
            Placement.PLACED, Placement.PLACED_BY_TIMEOUT -> "AR quality: ${levelWord(level)}"
        }

        val helperText = when {
            placement == Placement.PLACED_BY_TIMEOUT -> HELPER_TIMEOUT
            level == Level.LOW || level == Level.NOT_READY -> HELPER_LOW
            else -> null
        }

        return Status(level, statusLabel, accuracyText, headingText, helperText, modelsRenderable)
    }

    fun levelWord(level: Level): String = when (level) {
        Level.GOOD -> "Good"
        Level.FAIR -> "Fair"
        Level.LOW -> "Low"
        Level.UNKNOWN -> "Unknown"
        Level.NOT_READY -> "Not ready"
    }
}
