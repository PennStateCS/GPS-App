package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.hasLinkedModel
import app.surrealar.ui.theme.StatusTone
import java.util.Locale

/**
 * Display state for a single header badge on the coordinate detail screen: the text, its semantic
 * [tone] (the view fills the chip with the tone's container colour and draws the text in its
 * on-container colour, both theme-aware), and optional accessibility text. Pure data — no Android views.
 */
data class BadgeUi(
    val text: String,
    val tone: StatusTone,
    val contentDescription: String? = null
)

/**
 * Display state for the coordinate detail header badge row. A `null` badge means that badge is
 * hidden (GONE). [anyVisible] mirrors the Fragment's "show the row only if at least one badge is
 * visible" rule.
 */
data class CoordinateDetailBadges(
    val source: BadgeUi?,
    val fix: BadgeUi?,
    val extra: BadgeUi?,
    val accuracy: BadgeUi?
) {
    val anyVisible: Boolean get() = listOf(source, fix, extra, accuracy).any { it != null }
}

/**
 * Pure mapper from a [Coordinate] to the coordinate detail header badge display state.
 *
 * Extracted from [CoordinateDetailFragment] so the source/fix/accuracy/extra badge decisions (labels,
 * colors, visibility, accessibility text) are unit-testable in one place. Contains NO Android views.
 * Labels and content descriptions are preserved exactly from the previous in-fragment implementation —
 * changing any of them is a UI/behavior change. Colours are semantic tones (design system phase 2): the
 * old fixed fills with white text failed contrast (amber + white ≈ 1.9:1) and ignored dark mode.
 */
object CoordinateDetailUiMapper {

    /**
     * Short receiver name for the source badge: the profile's short label when the point's sourceDevice
     * is a profile label (e.g. "Emlid Reach RS2+" → "RS2+"), else "EXTERNAL GNSS".
     */
    private fun externalBadgeLabel(sourceDevice: String?): String =
        app.surrealar.settings.model.ExternalReceiverProfile.fromLabel(sourceDevice)?.shortLabel?.uppercase(Locale.US)
            ?: "EXTERNAL GNSS"

    /** The "source" badge is always shown; label + color depend on capture method / provider. */
    fun sourceBadge(c: Coordinate): BadgeUi {
        val cm = c.captureMethod?.trim()?.lowercase(Locale.US)
        val prov = c.provider.trim().lowercase(Locale.US)
        val (label, color) = when {
            cm == "model_embedded" || prov == "model"        -> "MODEL EMBEDDED LOCATION" to StatusTone.INFO
            cm == "imported"                                 -> "IMPORTED"     to StatusTone.NEUTRAL
            cm == "manual" || cm == "map_tap"                -> "MANUAL"       to StatusTone.NEUTRAL
            app.surrealar.data.local.db.ProviderTokens.isExternal(prov) || cm == "external_gnss" ||
                cm == "rtk_receiver"                         -> externalBadgeLabel(c.sourceDevice) to StatusTone.INFO
            else                                             -> "INTERNAL GPS" to StatusTone.INFO
        }
        return BadgeUi(label, color)
    }

    /** The RTK/fix badge; `null` (hidden) for non-GNSS sources or when no RTK status is present. */
    fun fixBadge(c: Coordinate): BadgeUi? {
        val cm = c.captureMethod?.trim()?.lowercase(Locale.US)
        // Non-GNSS sources: quality badge is not applicable
        if (cm == "model_embedded" || cm == "imported" || cm == "manual" || cm == "map_tap") return null
        val rtk = c.rtkStatus?.trim()?.uppercase(Locale.US)
        if (rtk.isNullOrBlank()) return null
        val (label, color) = when (rtk) {
            "FIX", "FIXED"                -> "FIX"    to StatusTone.SUCCESS
            "FLOAT"                       -> "FLOAT"  to StatusTone.WARNING
            "DGPS"                        -> "DGPS"   to StatusTone.INFO
            "SINGLE", "GPS", "AUTONOMOUS" -> "SINGLE" to StatusTone.NEUTRAL
            "NO FIX", "NOFIX", "NONE"     -> "NO FIX" to StatusTone.ERROR
            else                          -> "UNKNOWN" to StatusTone.NEUTRAL
        }
        val description = when (label) {
            "FIX"    -> "RTK fix solution"
            "FLOAT"  -> "RTK float solution"
            "SINGLE" -> "Standard GPS"
            "DGPS"   -> "Differential GPS"
            "NO FIX" -> "No GNSS fix"
            else     -> "Fix quality unknown"
        }
        return BadgeUi(label, color, description)
    }

    /** The "extra" badge: AVERAGED, MODEL LINKED, or `null` (hidden). */
    fun extraBadge(c: Coordinate): BadgeUi? = when {
        (c.averagedSamples ?: 0) > 0 -> BadgeUi("AVERAGED", StatusTone.NEUTRAL)
        c.hasLinkedModel             -> BadgeUi("MODEL LINKED", StatusTone.INFO)
        else                         -> null
    }

    /**
     * The horizontal-accuracy badge; `null` (hidden) when accuracy indicators are disabled or no
     * measured horizontal accuracy is available. Color tiers: survey-grade / sub-metre / degraded.
     */
    fun accuracyBadge(c: Coordinate, showAccuracyIndicators: Boolean): BadgeUi? {
        if (!showAccuracyIndicators) return null
        // Only use measured accuracy — do not estimate from HDOP (misleading as a badge).
        val hAcc = c.horizontalAccuracyM ?: return null
        val color = when {
            hAcc <= 0.05 -> StatusTone.SUCCESS   // survey-grade RTK
            hAcc <= 0.30 -> StatusTone.WARNING   // sub-metre
            else         -> StatusTone.ERROR     // degraded / single-point (was a deeper orange than sub-metre)
        }
        val description = String.format(Locale.US, "Horizontal accuracy plus or minus %.2f meters, 68 percent confidence", hAcc)
        return BadgeUi(CoordinateDetailFormatter.accuracyBadgeText(hAcc), color, description)
    }

    /** Builds the full header badge row state. */
    fun badges(c: Coordinate, showAccuracyIndicators: Boolean) = CoordinateDetailBadges(
        source = sourceBadge(c),
        fix = fixBadge(c),
        extra = extraBadge(c),
        accuracy = accuracyBadge(c, showAccuracyIndicators)
    )
}
