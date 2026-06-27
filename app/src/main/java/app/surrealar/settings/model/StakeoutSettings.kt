package app.surrealar.settings.model

/**
 * User preferences for stakeout guidance. Display/feedback only — these never affect the stored
 * coordinates or the stakeout distance/bearing math, only how guidance is presented and what
 * tolerance/feedback the surveyor gets.
 *
 * Canonical defaults live here (conservative): a 10 cm tolerance, a 30 cm accuracy warning
 * threshold, haptics on, audio off, screen kept on during active guidance, and compass heading
 * preferred (the runtime falls back to course-over-ground / north-up when no heading is available).
 */
data class StakeoutSettings(
    val toleranceMeters: Double = 0.10,
    val warningAccuracyMeters: Double = 0.30,
    val enableHaptics: Boolean = true,
    val enableAudio: Boolean = false,
    val keepScreenOnDuringStakeout: Boolean = true,
    val guidanceUsesCompassHeading: Boolean = true,
)
