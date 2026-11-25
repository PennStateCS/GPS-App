package app.surrealar.receiver.tilt

import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import java.util.Locale

/** Short user-facing texts for [TiltStatus] (Settings → Receiver, Developer page, capture dialog, diagnostics). */
object TiltStatusText {

    /** "Tilt: active · 12.3° · heading 254°" etc. */
    fun stateLine(s: TiltStatus): String {
        val st = s.tiltState
        val label = when {
            !s.etcSeen -> "no tilt data (no ETC from the receiver)"
            !s.etcLive -> "temporarily unavailable (tilt data stopped)"
            st == null -> "temporarily unavailable"
            else -> stateLabel(st)
        }
        val r = s.latest
        val extra = if (st == EmlidTiltState.ACTIVE && r != null) buildString {
            r.tiltDeg?.let { append(" · ${fmt1(it)}°") }
            r.headingDeg?.let { append(" · heading ${String.format(Locale.US, "%.0f", it)}°") }
        } else ""
        return "Tilt: $label$extra"
    }

    fun stateLabel(st: EmlidTiltState): String = when (st) {
        EmlidTiltState.OFF -> "off"
        EmlidTiltState.INITIALIZING -> "initializing"
        EmlidTiltState.ALIGNING -> "aligning (move the receiver)"
        EmlidTiltState.ACTIVE -> "active"
        EmlidTiltState.UNAVAILABLE -> "unavailable (receiver tilt fault; reboot the receiver)"
        EmlidTiltState.UNKNOWN -> "temporarily unavailable (unreadable tilt data)"
    }

    /** Receiver notification while compensating, or null. */
    fun warningLine(s: TiltStatus): String? {
        val r = s.latest ?: return null
        if (!r.hasWarning) return null
        val why = when (r.notification) {
            EtcNotification.FAST_MOTION -> "fast motion"
            EtcNotification.BAD_GNSS -> "poor GNSS solution"
            EtcNotification.FILTER_FAULT -> "filter fault"
            else -> "receiver notification"
        }
        return "Tilt warning: $why. Tilt-compensated epochs are not averaged while it lasts."
    }

    /** Position reference + one-sentence explanation. */
    fun referenceLine(s: TiltStatus): String = when (s.reference) {
        null -> if (s.applicable) "Position: not interpreted yet (no recent receiver position)" else "Position: receiver antenna"
        else -> "Position: ${referenceLabel(s.reference)}. ${referenceExplanation(s.reference, s.result)}"
    }

    fun referenceLabel(r: EpochReference): String = when (r) {
        EpochReference.ANTENNA -> "receiver antenna"
        EpochReference.ANTENNA_UNCERTAIN -> "receiver antenna (uncertain)"
        EpochReference.POLE_TIP -> "pole tip (receiver tilt-compensated)"
        EpochReference.MAY_BE_COMPENSATED -> "uncertain (may be the pole tip)"
        EpochReference.NOT_APPLICABLE -> "receiver antenna"
    }

    fun referenceExplanation(r: EpochReference, result: EmlidPositionReference.Result?): String = when (r) {
        EpochReference.ANTENNA ->
            if (result?.antennaMayBeTilted == true) "Captures subtract pole height + antenna offset; keep the pole plumb (the receiver's tilt is not applied to this output)."
            else "Captures subtract pole height + antenna offset."
        EpochReference.ANTENNA_UNCERTAIN ->
            "Treated as the antenna (captures subtract pole height + antenna offset); set \"Compensated NMEA output\" to confirm."
        EpochReference.POLE_TIP ->
            "The receiver already applied its own pole height (set in Emlid Flow); captures do not subtract the app's pole height."
        EpochReference.MAY_BE_COMPENSATED -> when (result?.reason) {
            EmlidPositionReference.Reason.FIRMWARE_UNKNOWN_OR_TOO_OLD ->
                "Compensated output needs receiver firmware 34.7 or later (firmware not known or older); captures refuse these positions."
            EmlidPositionReference.Reason.OUTPUT_SETTING_UNKNOWN ->
                "Tilt is on and the output may be compensated; set \"Compensated NMEA output\". Captures refuse these positions."
            else -> "Tilt compensation is not active for this position; captures refuse these positions."
        }
        EpochReference.NOT_APPLICABLE -> "Captures subtract pole height + antenna offset."
    }

    /** "5.0 Hz" / "stopped" / "none". */
    fun etcRateText(s: TiltStatus): String = when {
        !s.etcSeen -> "none"
        !s.etcLive -> "stopped (${s.etcAgeMs?.let { it / 1000 } ?: "?"} s ago)"
        else -> s.etcRateHz?.let { String.format(Locale.US, "%.1f Hz", it) } ?: "receiving"
    }

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
}
