package app.surrealar.ui.settings.categories

import io.github.pennstatecs.geokit.projection.SpcsZone
import io.github.pennstatecs.geokit.projection.SpcsZoneBasis
import io.github.pennstatecs.geokit.projection.SpcsZoneSuggestion

/**
 * Wording and rules for the State Plane zone suggestion on Settings → Coordinates (pure, testable).
 * The suggestion comes from geokit's `SpcsZoneLocator` (county-defined zone limits, or EPSG area-of-use
 * boxes where none apply); it never replaces the user's choice.
 */
internal object SpcsZoneHints {
    private const val MAX_ALTERNATIVES = 3

    /**
     * Zone to propose when the user switches to State Plane, or null to keep [saved]: the saved zone
     * stays when it is one of the zones matching the position (or there is no suggestion).
     */
    fun proposal(saved: SpcsZone?, suggestion: SpcsZoneSuggestion?): SpcsZone? {
        val s = suggestion?.suggested ?: return null
        return if (saved != null && saved in suggestion.candidates) null else s
    }

    /** Hint shown under the zone field after a zone was suggested. */
    fun suggestionHint(suggestion: SpcsZoneSuggestion?): String {
        val others = suggestion?.candidates.orEmpty().drop(1)
        val list = others.take(MAX_ALTERNATIVES).joinToString(", ") { it.label } +
            if (others.size > MAX_ALTERNATIVES) ", …" else ""
        return when (suggestion?.basis) {
            SpcsZoneBasis.COUNTY_BOUNDARY ->
                "Suggested from your position (zone county limits). Check it near a zone boundary." +
                    if (others.isNotEmpty()) " Also valid here: $list." else ""
            else ->
                "Suggested from your position. Check it: zone areas overlap near state lines." +
                    if (others.isNotEmpty()) " Other possible zones: $list." else ""
        }
    }

    /**
     * Warning when the live position does not fit the chosen [zone], or null. County limits decide where
     * they apply; otherwise the zone's area-of-use box ([boxContains]).
     */
    fun mismatchHint(zone: SpcsZone, suggestion: SpcsZoneSuggestion?, boxContains: Boolean): String? {
        val s = suggestion?.suggested
        return when {
            suggestion?.basis == SpcsZoneBasis.COUNTY_BOUNDARY && s != null && zone !in suggestion.candidates ->
                "Your position appears to be in ${s.label}, not this zone."
            !boxContains -> "Your current position is outside this zone."
            else -> null
        }
    }
}
