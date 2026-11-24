package app.surrealar.ui.settings.categories

import io.github.pennstatecs.geokit.projection.SpcsZone
import io.github.pennstatecs.geokit.projection.SpcsZoneBasis
import io.github.pennstatecs.geokit.projection.SpcsZoneCheck
import io.github.pennstatecs.geokit.projection.SpcsZoneFit
import io.github.pennstatecs.geokit.projection.SpcsZoneSuggestion

/**
 * Wording and rules for the State Plane zone suggestion on Settings → Coordinates (pure, testable).
 * The suggestion comes from geokit's `SpcsZoneLocator` (county-defined zone limits, or EPSG area-of-use
 * boxes where none apply); it never replaces the user's choice.
 */
internal object SpcsZoneHints {
    private const val MAX_ALTERNATIVES = 3

    /**
     * Zone to propose when the user switches to State Plane, or null to keep [saved]. The saved zone is
     * replaced only when it certainly does not fit the position ([savedCheck] = OUTSIDE): near a zone line,
     * offshore or where only a box is known it is kept (no silent change on an uncertain answer).
     */
    fun proposal(saved: SpcsZone?, savedCheck: SpcsZoneCheck?, suggestion: SpcsZoneSuggestion?): SpcsZone? {
        val s = suggestion?.suggested ?: return null
        if (saved == null) return s
        return if (savedCheck?.fit == SpcsZoneFit.OUTSIDE && s != saved) s else null
    }

    /** Hint shown under the zone field after a zone was suggested. */
    fun suggestionHint(suggestion: SpcsZoneSuggestion?): String {
        val others = suggestion?.candidates.orEmpty().drop(1)
        val list = others.take(MAX_ALTERNATIVES).joinToString(", ") { it.label } +
            if (others.size > MAX_ALTERNATIVES) ", …" else ""
        val near = if (suggestion?.nearBoundary == true) " You are near a zone line: confirm the zone." else ""
        return when (suggestion?.basis) {
            SpcsZoneBasis.COUNTY_BOUNDARY ->
                "Suggested from your position (zone county limits). Check it near a zone boundary." + near +
                    if (others.isNotEmpty()) " Also possible here: $list." else ""
            else ->
                "Suggested from your position. Check it: zone areas overlap near state lines." +
                    if (others.isNotEmpty()) " Other possible zones: $list." else ""
        }
    }

    /**
     * Note under the chosen [zone] for the live position, or null. Uses the same zone limits as the
     * suggestion ([check] from `SpcsZoneLocator.check`); only a certain OUTSIDE is worded as a mismatch.
     */
    fun mismatchHint(zone: SpcsZone, check: SpcsZoneCheck?, suggestion: SpcsZoneSuggestion?): String? {
        val s = suggestion?.suggested
        return when (check?.fit) {
            SpcsZoneFit.OUTSIDE ->
                if (check.basis == SpcsZoneBasis.COUNTY_BOUNDARY && suggestion?.basis == SpcsZoneBasis.COUNTY_BOUNDARY &&
                    s != null && s != zone) "Your position appears to be in ${s.label}, not this zone."
                else "Your current position is outside this zone."
            SpcsZoneFit.UNCERTAIN -> when (check.basis) {
                SpcsZoneBasis.COUNTY_BOUNDARY -> "Your position is near this zone's boundary: check the zone."
                SpcsZoneBasis.AREA_OF_USE_BOX -> "This zone's limits are only approximate here: check the zone."
                SpcsZoneBasis.NONE -> null
            }
            SpcsZoneFit.INSIDE, null -> null
        }
    }
}
