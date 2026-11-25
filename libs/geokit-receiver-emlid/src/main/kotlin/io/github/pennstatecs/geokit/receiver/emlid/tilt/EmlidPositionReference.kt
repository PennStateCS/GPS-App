package io.github.pennstatecs.geokit.receiver.emlid.tilt

import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel

/** Which physical point a streamed GGA position refers to. */
public enum class PositionReference {
    /**
     * The receiver's antenna, not tilt-compensated (today's assumption for every receiver: the app
     * reduces pole height + phase-centre offset). With [EmlidPositionReference.Result.antennaMayBeTilted]
     * the pole may not be plumb.
     */
    ANTENNA,
    /**
     * The receiver already moved the position to the pole tip with its own antenna height (set in Emlid
     * Flow). The app must not apply its own pole-height / phase-centre reduction to it.
     */
    RECEIVER_COMPENSATED_POLE_TIP,
    /** Not established by the documentation for this situation: keep today's maths, say it is uncertain. */
    UNKNOWN,
}

/**
 * The receiver's "send compensated NMEA" output setting in Emlid Flow, as declared by the user / profile.
 * It is not visible in the NMEA stream.
 */
public enum class CompensatedNmeaOutput {
    /** Compensated NMEA output turned on for the position output the app reads. */
    ENABLED,
    /** Turned off (or not available on that output). */
    DISABLED,
    /** Not known. */
    UNKNOWN,
}

/**
 * Classifies what an Emlid GGA position represents, returning [PositionReference.UNKNOWN] unless
 * Emlid's documentation confirms the answer for that situation. An ETC sentence on its own never makes
 * a position a pole tip.
 *
 * Evidence (retrieved 2026-11-24):
 * - RS4 changelog, firmware 34.4 (2026-04-23): "If the output format is set to NMEA with compensation
 *   enabled, the transmitted position will also be tilt-compensated." … "In all other cases, the output
 *   will be non-compensated." (https://docs.emlid.com/reachrs4/changelogs/)
 * - Firmware 34.7 (2026-08-31): "Fixed an issue where the NMEA GGA and RMC messages reported different
 *   positions when tilt compensation was enabled" — before 34.7 it is undocumented which one was right.
 * - Tilt quickstart: compensation needs RTK FIX and the IMU; the software "determine[s] the position of the
 *   pole tip on the ground". Integration guides: with compensated NMEA, set the antenna height only in
 *   Emlid Flow; "Setting it in third-party apps as well may cause discrepancies."
 * - ETC_rev.3_RS4.pdf: state "" = off. In its integration model GGA is the "non-compensated" rover
 *   position and the third party combines GGA + ETC.
 * - Reach RS2/RS2+ NMEA spec lists no ETC and no IMU.
 *
 * Rules, in order:
 * 1. RS2 / RS2+ profile (no IMU): [PositionReference.ANTENNA] while no ETC has been seen; an ETC on a
 *    profile that cannot send one means the profile is wrong → UNKNOWN.
 * 2. Matched ETC says tilt is OFF → ANTENNA (nothing to compensate with).
 * 3. Output declared DISABLED → ANTENNA ("non-compensated") with [Result.antennaMayBeTilted]: the tilt
 *    engine may be on, so the user may be holding the pole tilted while the GGA stays at the antenna.
 * 4. Output declared ENABLED, firmware known ≥ 34.7 and matched ETC ACTIVE → RECEIVER_COMPENSATED_POLE_TIP.
 * 5. Everything else (unknown setting, firmware unknown or < 34.7, ETC aligning/initialising/fatal,
 *    unmatched/stale/malformed ETC, no ETC on an RS4-class or unknown model) → UNKNOWN.
 *
 * Not confirmed and therefore never assumed: what GGA holds while compensation is enabled but the
 * engine is not compensating (alignment, temporary loss, fatal error), and whether the TCP position
 * output offers the compensation toggle at all (the guides show Bluetooth). Both need a receiver test.
 */
public object EmlidPositionReference {

    /** First firmware with consistent compensated GGA/RMC (34.7; compensated NMEA itself is 34.4). */
    public val MIN_COMPENSATED_FIRMWARE: Pair<Int, Int> = 34 to 7

    /** Why [classify] answered as it did. */
    public enum class Reason {
        NO_IMU_MODEL,
        ETC_ON_NO_IMU_MODEL,
        TILT_OFF,
        COMPENSATED_OUTPUT_DISABLED,
        RECEIVER_COMPENSATING,
        OUTPUT_SETTING_UNKNOWN,
        FIRMWARE_UNKNOWN_OR_TOO_OLD,
        TILT_NOT_COMPENSATING,
        TILT_STATE_UNKNOWN,
    }

    /**
     * @property reference what the position represents.
     * @property reason why.
     * @property antennaMayBeTilted true when the position is the antenna but the pole may not be plumb
     *   (tilt engine on, or its state unknown): warn the user to level the pole.
     * @property mayBeCompensated true when compensated output is declared ENABLED but this epoch could not
     *   be classified (reference UNKNOWN): the GGA may already be the pole tip. The app must NOT apply its own
     *   pole-height reduction to such an epoch (that would double-correct by ≈ the pole height) — exclude or
     *   refuse it, and never average it with epochs of another reference.
     * @property hasWarning the matched ETC carries a receiver notification (e.g. fast motion, poor GNSS):
     *   the reference is known but the compensation quality is degraded — warn or block.
     */
    public data class Result(
        val reference: PositionReference,
        val reason: Reason,
        val antennaMayBeTilted: Boolean = false,
        val mayBeCompensated: Boolean = false,
        val hasWarning: Boolean = false,
    )

    /**
     * @param model receiver model from the profile; null when not known (treated as possibly IMU-equipped).
     * @param compensatedOutput declared Emlid Flow output setting.
     * @param firmware receiver firmware string (e.g. ReceiverDeviceInfo.firmware, "34.7", "v34.10.1"); null if unknown.
     * @param tilt the fix's tilt association from [EmlidTiltTracker.associate].
     */
    public fun classify(
        model: EmlidReachModel?,
        compensatedOutput: CompensatedNmeaOutput,
        firmware: String?,
        tilt: EmlidTiltAssociation,
    ): Result {
        val matched = tilt.match == TiltMatch.MATCHED
        val noImu = model == EmlidReachModel.RS2 || model == EmlidReachModel.RS2_PLUS
        if (noImu) {
            return if (tilt.match == TiltMatch.NO_ETC_SEEN) Result(PositionReference.ANTENNA, Reason.NO_IMU_MODEL)
            else Result(PositionReference.UNKNOWN, Reason.ETC_ON_NO_IMU_MODEL)
        }
        if (matched && tilt.state == EmlidTiltState.OFF) {
            return Result(PositionReference.ANTENNA, Reason.TILT_OFF)
        }
        return when (compensatedOutput) {
            CompensatedNmeaOutput.DISABLED -> Result(
                PositionReference.ANTENNA,
                Reason.COMPENSATED_OUTPUT_DISABLED,
                antennaMayBeTilted = true,
            )
            CompensatedNmeaOutput.UNKNOWN -> Result(PositionReference.UNKNOWN, Reason.OUTPUT_SETTING_UNKNOWN)
            CompensatedNmeaOutput.ENABLED -> when {
                !firmwareAtLeast(firmware, MIN_COMPENSATED_FIRMWARE) ->
                    Result(PositionReference.UNKNOWN, Reason.FIRMWARE_UNKNOWN_OR_TOO_OLD, mayBeCompensated = true)
                !matched -> Result(PositionReference.UNKNOWN, Reason.TILT_STATE_UNKNOWN, mayBeCompensated = true)
                tilt.state == EmlidTiltState.ACTIVE ->
                    Result(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, Reason.RECEIVER_COMPENSATING,
                        hasWarning = tilt.reading?.hasWarning == true)
                else -> Result(PositionReference.UNKNOWN, Reason.TILT_NOT_COMPENSATING, mayBeCompensated = true)
            }
        }
    }

    /**
     * Parses the leading `major.minor` of a firmware string ("34.7", "v34.10", "34.10.1-beta",
     * "Firmware 34.4"); null when there is none.
     */
    public fun parseFirmware(firmware: String?): Pair<Int, Int>? {
        val m = FIRMWARE.find(firmware ?: return null) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        return major to minor
    }

    /** True when [firmware] parses and is at least [min]; unknown firmware is never "at least". */
    public fun firmwareAtLeast(firmware: String?, min: Pair<Int, Int>): Boolean {
        val (major, minor) = parseFirmware(firmware) ?: return false
        return major > min.first || (major == min.first && minor >= min.second)
    }

    private val FIRMWARE = Regex("""(?<![\d.])(\d{1,4})\.(\d{1,4})""")
}
