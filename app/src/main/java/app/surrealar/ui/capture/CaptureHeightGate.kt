package app.surrealar.ui.capture

import io.github.pennstatecs.geokit.gnss.height.EllipsoidalHeightStatus
import io.github.pennstatecs.geokit.gnss.height.ReceiverHeights
import io.github.pennstatecs.geokit.gnss.model.Fix

/**
 * Height-quality gate for one occupation (docs/geodesy/rs4-nmea-heights.md). Pure apart from [log].
 *
 * - An epoch whose vertical accuracy is an invalid marker (e.g. Emlid's documented 999 m when receiver-side
 *   orthometric corrections are interrupted > 10 s; geokit `VerticalAccuracyValidity`) is **not averaged**:
 *   the averaging is 3-D (ECEF), so its height cannot be dropped while keeping its horizontal position, and
 *   its height must never become a saved elevation or a cm-level vertical accuracy. Averaging resumes by
 *   itself with the first epoch whose vertical accuracy is valid again (corrections resumed).
 * - An epoch with H and a geoid separation of exactly 0 is averaged as before (undecidable from NMEA whether
 *   field 9 is already ellipsoidal) but logged once per occupation as "ellipsoidal height uncertain".
 *
 * @param log receives one line per transition (interrupted / resumed / uncertain separation).
 */
class CaptureHeightGate(private val log: (String) -> Unit = {}) {

    /** Epochs excluded because their vertical accuracy was invalid. */
    var excludedEpochs: Int = 0
        private set

    /** True while the latest epoch had an invalid vertical accuracy. */
    var invalidNow: Boolean = false
        private set

    private var zeroSeparationLogged = false

    companion object {
        /** Capture-dialog status while epochs are excluded for an invalid vertical accuracy. */
        const val INVALID_STATUS: String = "Receiver height invalid (vertical accuracy marker, e.g. 999 m): " +
            "epochs are not averaged until it recovers"
    }

    /** True when [fix] may be averaged. */
    fun admit(fix: Fix): Boolean {
        val a = ReceiverHeights.assess(fix)
        if (a.ellipsoidalStatus == EllipsoidalHeightStatus.VERTICAL_ACCURACY_INVALID) {
            excludedEpochs++
            if (!invalidNow) log("Capture height invalid: vertical accuracy ${fix.vAccM ?: fix.stdDevUpM} m " +
                "(receiver-side height corrections interrupted?) — epochs not averaged until it recovers")
            invalidNow = true
            return false
        }
        if (invalidNow) log("Capture height valid again after $excludedEpochs excluded epoch(s): averaging resumes")
        invalidNow = false
        if (a.ellipsoidalStatus == EllipsoidalHeightStatus.SEPARATION_ZERO_AMBIGUOUS && !zeroSeparationLogged) {
            zeroSeparationLogged = true
            log("Capture height uncertain: GGA geoid separation is 0; " +
                "h = H + 0 is only right if the receiver streams ellipsoidal heights in GGA")
        }
        return true
    }
}
