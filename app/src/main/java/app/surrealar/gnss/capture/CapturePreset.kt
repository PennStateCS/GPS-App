package app.surrealar.gnss.capture

import app.surrealar.gnss.model.RtkStatus

/**
 * Capture presets (Settings → GNSS Capture). A preset defines one averaging policy for an EXTERNAL
 * receiver and one for the device's INTERNAL GNSS, because realistic numbers differ by orders of
 * magnitude. [CUSTOM] uses the individual fields in [GnssCaptureSettings] for external captures and
 * [AveragingPolicy.INTERNAL] for internal ones (the behaviour before presets existed).
 *
 * Spread targets (horizontal scatter of the individual accepted fixes — a conservative stop rule,
 * not the accuracy of the averaged point; stored as `horizontalPrecisionTargetM` / the
 * precision-target preference key, which keep their names) apply to RTK receivers only. Device
 * GNSS errors are dominated by slowly varying effects (multipath, atmosphere), so a tight spread over
 * a short occupation does not indicate accuracy there; internal presets use time + fix counts only.
 *
 * [prefKey] is persisted — never rename.
 */
enum class CapturePreset(
    val prefKey: String,
    val label: String,
    val external: AveragingPolicy?,
    val internal: AveragingPolicy?,
) {
    QUICK_TOPO(
        "quick_topo", "Quick topo",
        external = AveragingPolicy(minDurationSec = 5, maxDurationSec = 30, minSamples = 5,
            requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 3, maxDiffAgeSec = 10,
            horizontalPrecisionTargetM = 0.03),
        internal = AveragingPolicy(minDurationSec = 10, maxDurationSec = 60, minSamples = 10,
            requiredMinStatus = RtkStatus.SINGLE, maxFixAgeSec = 3, maxDiffAgeSec = 10),
    ),
    STANDARD(
        "standard", "Standard",
        external = AveragingPolicy(minDurationSec = 30, maxDurationSec = 120, minSamples = 30,
            requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 3, maxDiffAgeSec = 10,
            horizontalPrecisionTargetM = 0.02),
        internal = AveragingPolicy.INTERNAL,
    ),
    CONTROL(
        "control", "Control",
        external = AveragingPolicy(minDurationSec = 180, maxDurationSec = 300, minSamples = 180,
            requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 3, maxDiffAgeSec = 5,
            horizontalPrecisionTargetM = 0.015),
        internal = AveragingPolicy(minDurationSec = 120, maxDurationSec = 300, minSamples = 100,
            requiredMinStatus = RtkStatus.SINGLE, maxFixAgeSec = 3, maxDiffAgeSec = 10),
    ),
    /** The individual Settings fields (external) / [AveragingPolicy.INTERNAL] (internal). */
    CUSTOM("custom", "Custom", external = null, internal = null);

    companion object {
        /** Default for new and existing installs: keeps the pre-preset behaviour until a preset is chosen. */
        val DEFAULT = CUSTOM

        fun fromPrefKey(key: String?): CapturePreset = entries.firstOrNull { it.prefKey == key } ?: DEFAULT
    }
}

/**
 * The averaging policy a capture should use for the saved settings: the chosen preset's policy, or for
 * [CapturePreset.CUSTOM] the individual fields (external) / [AveragingPolicy.INTERNAL] (internal).
 */
fun GnssCaptureSettings.resolvePolicy(internal: Boolean): AveragingPolicy {
    val presetPolicy = if (internal) preset.internal else preset.external
    return presetPolicy ?: if (internal) AveragingPolicy.INTERNAL else toAveragingPolicy()
}
