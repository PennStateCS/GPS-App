package app.surrealar.stakeout

import kotlin.math.roundToInt

/**
 * Pure (Android-free) stakeout guidance state + logic.
 *
 * This is display/feedback logic only. It consumes distance/bearing values that are computed by the
 * existing map math (haversine distance + great-circle bearing in `RenderMapFragment`) and turns
 * them into a navigation status, a relative arrow angle, and direction text. It deliberately does
 * NOT compute distance or bearing itself, so the existing stakeout math is untouched.
 */

/** Navigation status for the active stakeout target. Low accuracy is surfaced separately (orthogonal). */
enum class StakeoutStatus {
    NO_TARGET,
    WAITING_FOR_POSITION,
    NAVIGATING,
    WITHIN_TOLERANCE,
    AT_TARGET,
}

/** How the guidance arrow's heading reference was derived this frame. */
enum class HeadingSource { COMPASS, COURSE_OVER_GROUND, NORTH_UP }

/**
 * Immutable snapshot of the guidance UI state. Kept separate from any persisted coordinate.
 * Angles are degrees. Distances/deltas are metres. Nulls mean "not available yet".
 */
data class StakeoutGuidanceState(
    val isActive: Boolean = false,
    val targetName: String? = null,
    val distanceMeters: Double? = null,
    val bearingToTargetDeg: Double? = null,
    /** Arrow angle relative to where the device is pointing; null when no heading reference. */
    val relativeBearingDeg: Double? = null,
    val headingDeg: Double? = null,
    val headingSource: HeadingSource = HeadingSource.NORTH_UP,
    val horizontalAccuracyMeters: Double? = null,
    val deltaNorthMeters: Double? = null,
    val deltaEastMeters: Double? = null,
    val lowAccuracy: Boolean = false,
    val status: StakeoutStatus = StakeoutStatus.NO_TARGET,
)

/**
 * Pure functions that turn already-computed distance/bearing into [StakeoutGuidanceState]:
 * navigation [status], the relative arrow angle, and direction text. Stateless and Android-free; it
 * computes neither distance nor bearing (the map math does that). Tolerance handling is display-only
 * and never blocks navigation.
 */
object StakeoutGuidance {

    /** AT_TARGET is reported once the distance is within this fraction of the tolerance. */
    const val AT_TARGET_TOLERANCE_FRACTION = 0.5

    /**
     * Navigation status from the raw inputs. Tolerance comparison is display-only and never blocks.
     * A non-positive tolerance is treated as a tiny epsilon so "within tolerance" stays meaningful.
     */
    fun status(
        hasTarget: Boolean,
        hasPosition: Boolean,
        distanceMeters: Double?,
        toleranceMeters: Double,
    ): StakeoutStatus {
        if (!hasTarget) return StakeoutStatus.NO_TARGET
        if (!hasPosition || distanceMeters == null) return StakeoutStatus.WAITING_FOR_POSITION
        val tol = if (toleranceMeters > 0.0) toleranceMeters else 1e-6
        return when {
            distanceMeters <= tol * AT_TARGET_TOLERANCE_FRACTION -> StakeoutStatus.AT_TARGET
            distanceMeters <= tol -> StakeoutStatus.WITHIN_TOLERANCE
            else -> StakeoutStatus.NAVIGATING
        }
    }

    /** True when a known horizontal accuracy is worse than the warning threshold. Never blocks stakeout. */
    fun isLowAccuracy(horizontalAccuracyMeters: Double?, warningAccuracyMeters: Double): Boolean {
        val acc = horizontalAccuracyMeters ?: return false
        return acc > warningAccuracyMeters
    }

    /**
     * True when the most recent fix is older than [staleAfterMs] (clocks are monotonic elapsed-time).
     * A never-received fix is treated as stale. Used to fall back to the "waiting" state when live
     * positions stop arriving, so the arrow/readout don't appear live and feedback is suppressed.
     */
    fun isPositionStale(lastFixElapsedMs: Long?, nowElapsedMs: Long, staleAfterMs: Long): Boolean {
        if (lastFixElapsedMs == null) return true
        return nowElapsedMs - lastFixElapsedMs > staleAfterMs
    }

    /**
     * Arrow angle = bearingToTarget − heading, normalised to [0, 360). Returns null when no heading
     * reference is available (caller then shows a north-up arrow with a note).
     */
    fun relativeBearing(bearingToTargetDeg: Double, headingDeg: Double?): Double? {
        if (headingDeg == null) return null
        return normalize360(bearingToTargetDeg - headingDeg)
    }

    /** Normalises any angle to [0, 360). */
    fun normalize360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Normalises any angle to (−180, 180]. */
    fun normalize180(deg: Double): Double {
        var d = normalize360(deg)
        if (d > 180.0) d -= 360.0
        return d
    }

    /**
     * Chooses the heading reference per Task 5: compass when allowed & available, else
     * course-over-ground when the user is moving, else north-up. Returns the chosen source and the
     * heading degrees to use (null heading ⇒ north-up arrow).
     */
    fun resolveHeading(
        preferCompass: Boolean,
        compassHeadingDeg: Double?,
        courseOverGroundDeg: Double?,
        speedMps: Double?,
        minMovingSpeedMps: Double = 0.5,
    ): Pair<HeadingSource, Double?> {
        if (preferCompass && compassHeadingDeg != null) {
            return HeadingSource.COMPASS to normalize360(compassHeadingDeg)
        }
        val moving = (speedMps ?: 0.0) >= minMovingSpeedMps
        if (courseOverGroundDeg != null && moving) {
            return HeadingSource.COURSE_OVER_GROUND to normalize360(courseOverGroundDeg)
        }
        return HeadingSource.NORTH_UP to null
    }

    /** 8-point compass abbreviation for a bearing (e.g. "NE"). */
    fun compassPoint(bearingDeg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(normalize360(bearingDeg) / 45.0).roundToInt() % 8]
    }

    /** Human "move <word>" direction for a bearing. */
    fun directionWord(bearingDeg: Double): String = when (compassPoint(bearingDeg)) {
        "N" -> "north"
        "NE" -> "northeast"
        "E" -> "east"
        "SE" -> "southeast"
        "S" -> "south"
        "SW" -> "southwest"
        "W" -> "west"
        else -> "northwest"
    }
}
