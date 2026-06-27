package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.hasLinkedModel
import java.util.Locale

/**
 * Display state for a single header badge on the coordinate detail screen: the text, its ARGB
 * background color, and optional accessibility text. Pure data — no Android views.
 */
data class BadgeUi(
    val text: String,
    val colorArgb: Int,
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
 * All labels, colors, and content descriptions are preserved exactly from the previous in-fragment
 * implementation — changing any of them is a UI/behavior change.
 */
object CoordinateDetailUiMapper {

    /** The "source" badge is always shown; label + color depend on capture method / provider. */
    fun sourceBadge(c: Coordinate): BadgeUi {
        val cm = c.captureMethod?.trim()?.lowercase(Locale.US)
        val prov = c.provider.trim().lowercase(Locale.US)
        val (label, color) = when {
            cm == "model_embedded" || prov == "model"        -> "MODEL EMBEDDED LOCATION" to 0xFF1565C0.toInt()
            cm == "imported"                                 -> "IMPORTED"     to 0xFF455A64.toInt()
            cm == "manual" || cm == "map_tap"                -> "MANUAL"       to 0xFF455A64.toInt()
            prov.contains("rs2") || cm == "external_gnss" ||
                cm == "rtk_receiver"                         -> "RS2+"         to 0xFF1565C0.toInt()
            else                                             -> "INTERNAL GPS" to 0xFF1565C0.toInt()
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
            "FIX", "FIXED"                -> "FIXED"  to 0xFF2E7D32.toInt()
            "FLOAT"                       -> "FLOAT"  to 0xFFE65100.toInt()
            "DGPS"                        -> "DGPS"   to 0xFF1565C0.toInt()
            "SINGLE", "GPS", "AUTONOMOUS" -> "SINGLE" to 0xFF1565C0.toInt()
            "NO FIX", "NOFIX", "NONE"     -> "NO FIX" to 0xFFC62828.toInt()
            else                          -> "UNKNOWN" to 0xFF607D8B.toInt()
        }
        val description = when (label) {
            "FIXED"  -> "RTK fixed solution"
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
        (c.averagedSamples ?: 0) > 0 -> BadgeUi("AVERAGED", 0xFF455A64.toInt())
        c.hasLinkedModel             -> BadgeUi("MODEL LINKED", 0xFF1565C0.toInt())
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
            hAcc <= 0.05 -> 0xFF2E7D32.toInt()   // green  – survey-grade RTK
            hAcc <= 0.30 -> 0xFFF9A825.toInt()   // amber  – sub-metre
            else         -> 0xFFEF6C00.toInt()   // orange – degraded / single-point
        }
        val description = String.format(Locale.US, "Horizontal accuracy plus or minus %.2f meters", hAcc)
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
