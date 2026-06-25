package com.example.surveyingapp.gnss.format

import com.example.surveyingapp.gnss.model.RtkStatus
import java.util.Locale

/**
 * Single source of truth for GNSS status wording shown across the app (toolbar, capture dialog,
 * Settings receiver card, diagnostic report, coordinate badges). Centralizing the labels here
 * prevents the same RTK/source strings from drifting between screens.
 *
 * Pure: no Android types, so it is trivially unit-testable. Callers map levels → colors themselves.
 */
object GnssStatusFormatter {

    /**
     * Source label shown next to the live data ("Internal" for internal GPS, otherwise the selected
     * external receiver profile's short label). [externalLabel] defaults to "RS2+" so existing
     * callers and tests keep their previous wording until a profile label is supplied.
     */
    fun formatSource(isInternal: Boolean, externalLabel: String = "RS2+"): String =
        if (isInternal) "Internal" else externalLabel

    /**
     * Fix/SOL label. Internal GPS collapses to "GPS"/"No Fix"; external receivers expose the full
     * RTK ladder ("Fixed"/"Float"/"DGPS"/"Single"/"DR"/"No Fix"). Matches the existing toolbar
     * wording exactly.
     */
    fun formatFixStatus(status: RtkStatus, isInternal: Boolean): String =
        if (isInternal) {
            when (status) {
                RtkStatus.NONE, RtkStatus.INVALID -> "No Fix"
                else -> "GPS"
            }
        } else {
            when (status) {
                RtkStatus.NONE           -> "No Fix"
                RtkStatus.SINGLE         -> "Single"
                RtkStatus.DGPS           -> "DGPS"
                RtkStatus.FLOAT          -> "Float"
                RtkStatus.FIX            -> "Fixed"
                RtkStatus.DEAD_RECKONING -> "DR"
                RtkStatus.INVALID        -> "No Fix"
            }
        }

    /**
     * Capture-dialog fix label from a raw status name (e.g. a stored `rtkStatus` string). Differs
     * from [formatFixStatus] in its fallback: any unrecognized value (including DEAD_RECKONING,
     * INVALID, or null) maps to "Unknown" rather than "No Fix"/"DR". Matches the capture dialog's
     * existing `shortFix` wording exactly.
     */
    fun formatCaptureFixStatus(rawStatusName: String?): String =
        when (rawStatusName?.uppercase(Locale.US)) {
            "FIX"    -> "Fixed"
            "FLOAT"  -> "Float"
            "DGPS"   -> "DGPS"
            "SINGLE" -> "Single"
            "NONE"   -> "No Fix"
            else     -> "Unknown"
        }

    /** Horizontal accuracy in meters, e.g. "±0.02 m". Caller decides the null/placeholder case. */
    fun formatAccuracyMeters(hAccM: Double): String =
        "±${String.format(Locale.US, "%.2f", hAccM)} m"

    /** Satellites used/visible, e.g. "12/20". */
    fun formatSatellites(used: Int, visible: Int): String = "$used/$visible"

    /** Differential/RTK correction age, e.g. "Age 3s"; null when not reported or negative. */
    fun formatCorrectionAge(diffAgeS: Double?): String? =
        diffAgeS?.let { if (it >= 0) "Age ${it.toInt()}s" else null }
}
