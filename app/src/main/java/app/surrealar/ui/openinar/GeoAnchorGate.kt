package app.surrealar.ui.openinar

/**
 * Pure decision logic for WHEN to commit ARCore geospatial anchors, based on ARCore's own Earth
 * (VPS/visual) localization accuracy — which is independent of the RTK GNSS fix.
 *
 * Why this exists: `Earth.trackingState == TRACKING` only means ARCore has *some* geospatial
 * estimate; right after it first tracks, that estimate can be tens of metres / tens of degrees off.
 * Calling `earth.createAnchor(lat, lon, …)` at that moment maps every coordinate to ≈ the device
 * origin, so all models pile up on the user. Gating anchor creation on ARCore's reported
 * `cameraGeospatialPose` accuracy prevents that.
 *
 * Behaviour (chosen with the user):
 *  - Hold (WAIT) until horizontal accuracy ≤ [MAX_HORIZONTAL_ACCURACY_M] and yaw accuracy ≤
 *    [MAX_ORIENTATION_YAW_ACCURACY_DEG] → [Decision.PLACE_LOCALIZED].
 *  - Fallback: if it never localizes that well (e.g. open/rural, no VPS), place anyway after
 *    [LOCALIZE_TIMEOUT_MS] of continuous tracking → [Decision.PLACE_TIMEOUT], so models always
 *    appear. Such placements are marked so they can be re-anchored when accuracy later improves.
 *  - After placement, [shouldReanchorOnImprovement] re-anchors once localization materially improves
 *    (so a rough initial/timeout placement self-corrects instead of staying wrong until a manual
 *    re-anchor).
 *
 * No Android/ARCore types here → fully unit-testable.
 */
object GeoAnchorGate {

    /** ARCore horizontal accuracy (metres, 1σ) at or below which placement is considered localized. */
    const val MAX_HORIZONTAL_ACCURACY_M = 5.0

    /** ARCore yaw/heading accuracy (degrees) at or below which placement is considered localized. */
    const val MAX_ORIENTATION_YAW_ACCURACY_DEG = 15.0

    /**
     * If ARCore has been tracking this long without meeting the accuracy gate, place anyway at the
     * best current estimate so models never fail to appear (important for no-VPS/rural sites).
     */
    const val LOCALIZE_TIMEOUT_MS = 25_000L

    /**
     * Re-anchor after localization improves to ≤ this fraction of the accuracy at creation AND by at
     * least [REANCHOR_MIN_IMPROVEMENT_M]. Both conditions avoid churning on tiny fluctuations.
     */
    const val REANCHOR_IMPROVEMENT_FACTOR = 0.5
    const val REANCHOR_MIN_IMPROVEMENT_M = 2.0

    enum class Decision { WAIT, PLACE_LOCALIZED, PLACE_TIMEOUT }

    /**
     * @param horizontalAccuracyM ARCore `cameraGeospatialPose.horizontalAccuracy`
     * @param yawAccuracyDeg       ARCore `cameraGeospatialPose.orientationYawAccuracy`
     * @param trackingElapsedMs    how long Earth has been continuously TRACKING
     */
    fun decide(
        horizontalAccuracyM: Double,
        yawAccuracyDeg: Double,
        trackingElapsedMs: Long,
    ): Decision {
        val localized = horizontalAccuracyM.isFinite() && yawAccuracyDeg.isFinite() &&
            horizontalAccuracyM <= MAX_HORIZONTAL_ACCURACY_M &&
            yawAccuracyDeg <= MAX_ORIENTATION_YAW_ACCURACY_DEG
        return when {
            localized -> Decision.PLACE_LOCALIZED
            trackingElapsedMs >= LOCALIZE_TIMEOUT_MS -> Decision.PLACE_TIMEOUT
            else -> Decision.WAIT
        }
    }

    /**
     * Whether existing anchors should be rebuilt because Earth localization has materially improved
     * since they were created — turning a rough/timeout placement into an accurate one.
     */
    fun shouldReanchorOnImprovement(
        accuracyAtCreationM: Double,
        currentAccuracyM: Double,
        cooldownElapsed: Boolean,
    ): Boolean =
        cooldownElapsed &&
        currentAccuracyM.isFinite() && accuracyAtCreationM.isFinite() &&
        currentAccuracyM <= accuracyAtCreationM * REANCHOR_IMPROVEMENT_FACTOR &&
        (accuracyAtCreationM - currentAccuracyM) >= REANCHOR_MIN_IMPROVEMENT_M
}
