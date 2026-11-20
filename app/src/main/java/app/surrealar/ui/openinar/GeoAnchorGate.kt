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
 * Heading dominates: a yaw error θ moves a model d·tan θ sideways (15° → 5.4 m at 20 m; 5° → 1.7 m).
 * So with an RTK receiver — where position is no longer the limit — the gate also demands a tighter
 * heading ([Profile.RTK]) and waits longer for it before giving up.
 *
 * Behaviour:
 *  - Hold (WAIT) until horizontal accuracy ≤ profile.maxHorizontalM and yaw accuracy ≤
 *    profile.maxYawDeg → [Decision.PLACE_LOCALIZED].
 *  - Fallback: if it never localizes that well (e.g. open/rural, no VPS), place anyway after
 *    profile.timeoutMs of continuous tracking → [Decision.PLACE_TIMEOUT], so models always appear.
 *  - After placement, [shouldReanchorOnImprovement] re-anchors once localization materially improves
 *    — horizontally OR in heading — so a rough placement self-corrects.
 *
 * No Android/ARCore types here → fully unit-testable.
 */
object GeoAnchorGate {

    /** Gate thresholds. STANDARD = internal GNSS / non-RTK; RTK = external receiver in FIX or FLOAT. */
    enum class Profile(val maxHorizontalM: Double, val maxYawDeg: Double, val timeoutMs: Long) {
        STANDARD(MAX_HORIZONTAL_ACCURACY_M, MAX_ORIENTATION_YAW_ACCURACY_DEG, LOCALIZE_TIMEOUT_MS),
        RTK(MAX_HORIZONTAL_ACCURACY_M, RTK_MAX_ORIENTATION_YAW_ACCURACY_DEG, RTK_LOCALIZE_TIMEOUT_MS),
    }

    /** ARCore horizontal accuracy (metres) at or below which placement is considered localized. */
    const val MAX_HORIZONTAL_ACCURACY_M = 5.0

    /** ARCore yaw/heading accuracy (degrees) at or below which placement is considered localized. */
    const val MAX_ORIENTATION_YAW_ACCURACY_DEG = 15.0

    /** Heading gate with an RTK receiver: 5° ≈ 1.7 m at 20 m (vs 5.4 m at 15°). */
    const val RTK_MAX_ORIENTATION_YAW_ACCURACY_DEG = 5.0

    /**
     * If ARCore has been tracking this long without meeting the accuracy gate, place anyway at the
     * best current estimate so models never fail to appear (important for no-VPS/rural sites).
     */
    const val LOCALIZE_TIMEOUT_MS = 25_000L

    /** RTK waits longer for the tighter heading before falling back (heading improves as you move). */
    const val RTK_LOCALIZE_TIMEOUT_MS = 45_000L

    /**
     * Horizontal re-anchor: accuracy improved to ≤ this fraction of the accuracy at creation AND by at
     * least [REANCHOR_MIN_IMPROVEMENT_M]. 0.3 m (was 2 m) so RTK-scale gains (1.5 m → 0.3 m) count.
     */
    const val REANCHOR_IMPROVEMENT_FACTOR = 0.5
    const val REANCHOR_MIN_IMPROVEMENT_M = 0.3

    /** Heading re-anchor: yaw accuracy halved AND improved by at least this many degrees. */
    const val REANCHOR_MIN_YAW_IMPROVEMENT_DEG = 2.0

    enum class Decision { WAIT, PLACE_LOCALIZED, PLACE_TIMEOUT }

    /** RTK profile only when the external receiver is in an RTK solution (FIX or FLOAT). */
    fun profileFor(isExternal: Boolean, rtkFixOrFloat: Boolean): Profile =
        if (isExternal && rtkFixOrFloat) Profile.RTK else Profile.STANDARD

    /**
     * @param horizontalAccuracyM ARCore `cameraGeospatialPose.horizontalAccuracy`
     * @param yawAccuracyDeg       ARCore `cameraGeospatialPose.orientationYawAccuracy`
     * @param trackingElapsedMs    how long Earth has been continuously TRACKING
     */
    fun decide(
        horizontalAccuracyM: Double,
        yawAccuracyDeg: Double,
        trackingElapsedMs: Long,
        profile: Profile = Profile.STANDARD,
    ): Decision {
        val localized = horizontalAccuracyM.isFinite() && yawAccuracyDeg.isFinite() &&
            horizontalAccuracyM <= profile.maxHorizontalM &&
            yawAccuracyDeg <= profile.maxYawDeg
        return when {
            localized -> Decision.PLACE_LOCALIZED
            trackingElapsedMs >= profile.timeoutMs -> Decision.PLACE_TIMEOUT
            else -> Decision.WAIT
        }
    }

    /** Why a re-anchor fires (for logs), or null when it should not. */
    enum class ReanchorReason { HORIZONTAL, HEADING }

    /**
     * Whether existing anchors should be rebuilt because Earth localization has materially improved
     * since they were created — horizontally or in heading. Yaw arguments default to NaN (ignored).
     */
    fun reanchorReason(
        accuracyAtCreationM: Double,
        currentAccuracyM: Double,
        cooldownElapsed: Boolean,
        yawAtCreationDeg: Double = Double.NaN,
        currentYawDeg: Double = Double.NaN,
    ): ReanchorReason? {
        if (!cooldownElapsed) return null
        if (currentAccuracyM.isFinite() && accuracyAtCreationM.isFinite() &&
            currentAccuracyM <= accuracyAtCreationM * REANCHOR_IMPROVEMENT_FACTOR &&
            (accuracyAtCreationM - currentAccuracyM) >= REANCHOR_MIN_IMPROVEMENT_M
        ) return ReanchorReason.HORIZONTAL
        if (currentYawDeg.isFinite() && yawAtCreationDeg.isFinite() &&
            currentYawDeg <= yawAtCreationDeg * REANCHOR_IMPROVEMENT_FACTOR &&
            (yawAtCreationDeg - currentYawDeg) >= REANCHOR_MIN_YAW_IMPROVEMENT_DEG
        ) return ReanchorReason.HEADING
        return null
    }

    /** Horizontal-only form kept for callers/tests that predate heading re-anchoring. */
    fun shouldReanchorOnImprovement(
        accuracyAtCreationM: Double,
        currentAccuracyM: Double,
        cooldownElapsed: Boolean,
    ): Boolean = reanchorReason(accuracyAtCreationM, currentAccuracyM, cooldownElapsed) == ReanchorReason.HORIZONTAL

    /**
     * Worst-case sideways placement error (m) of a model [distanceM] away, from ARCore's horizontal
     * accuracy plus the heading error's lever: h + d·tan(yaw). Used by the debug readout/logs.
     */
    fun expectedErrorM(horizontalAccuracyM: Double, yawAccuracyDeg: Double, distanceM: Double): Double? {
        if (!horizontalAccuracyM.isFinite() || !yawAccuracyDeg.isFinite() || !distanceM.isFinite()) return null
        return horizontalAccuracyM + distanceM * kotlin.math.tan(Math.toRadians(yawAccuracyDeg))
    }
}
