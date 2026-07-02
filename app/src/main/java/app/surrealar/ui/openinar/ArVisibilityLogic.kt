package app.surrealar.ui.openinar

/**
 * Pure AR model-visibility rules, shared by [OpenInARViewModel] (the bottom-sheet rows + counts) and
 * the AR render gate in `OpenInARFragment.collectGeoAnchors`. Keeping the rule in one place means the
 * sheet's "renderable"/"in range" and what actually draws in AR can never diverge.
 *
 * Visibility is AR-only: until the user customises it, the effective visible set derives from the map
 * selection (`coordinate.renderEnabled`); once customised, the explicit AR set is authoritative.
 */
object ArVisibilityLogic {

    /**
     * The effective AR-visible coordinate ids. [customized] false → derive from the map selection
     * (model coordinates with `renderEnabled`); true → the explicit [storedIds] set.
     */
    fun effectiveVisibleIds(
        coords: List<CoordWithModel>,
        customized: Boolean,
        storedIds: Set<String>,
    ): Set<String> =
        if (customized) storedIds
        else coords.filter { it.modelFilePath != null && it.coordinate.renderEnabled }
            .map { it.coordinate.id }.toSet()

    /** Add/remove one id from the selection (does not delete the rest — range changes never touch this). */
    fun toggle(current: Set<String>, coordId: String, selected: Boolean): Set<String> =
        if (selected) current + coordId else current - coordId

    /** Whether a coordinate is within the active range for the mode (ALL ignores range entirely). */
    fun inRange(mode: ArVisibilityMode, distanceM: Double?, rangeM: Double?): Boolean =
        mode == ArVisibilityMode.ALL || rangeM == null || (distanceM != null && distanceM <= rangeM)

    /** Whether a model coordinate should render, given mode + range eligibility + selection. */
    fun renderable(mode: ArVisibilityMode, inRange: Boolean, selected: Boolean): Boolean = when (mode) {
        ArVisibilityMode.ALL      -> true
        ArVisibilityMode.NEARBY   -> inRange
        ArVisibilityMode.SELECTED -> selected && inRange
    }
}
