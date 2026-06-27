package app.surrealar.util.diagnostics

import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.TimestampSource

/**
 * Pure formatter for the "NMEA Stream Diagnostics" section of the diagnostic report.
 *
 * Takes only sanitized inputs (receiver profile, host/port, cumulative sentence/fix counts, EBP/ETC
 * activity). It deliberately emits **no raw NMEA and no coordinates** — not the live position and not
 * the EBP base position. Unit-testable with no Android dependencies.
 */
object NmeaStreamDiagnosticsFormatter {

    fun format(
        profileLabel: String,
        connType: String?,
        host: String?,
        port: Int?,
        timing: NmeaFuser.FuserTimingStats,
        custom: NmeaFuser.NmeaCustomStats,
    ): String {
        val totalSentences = timing.ggaCount + timing.rmcCount + timing.zdaCount +
            custom.ebpCount + custom.etcCount
        val firstNmea = totalSentences > 0
        val firstFix = timing.emittedFixCount > 0

        val sb = StringBuilder()
        sb.appendLine("=== NMEA Stream Diagnostics ===")
        sb.appendLine("Sanitized — message counts only, no raw NMEA or live coordinates.")
        sb.appendLine()
        sb.appendLine("Receiver profile      : $profileLabel")
        sb.appendLine("Connection type       : ${connType ?: "unknown"}")
        sb.appendLine("Host / IP             : ${host?.takeIf { it.isNotBlank() } ?: "not configured"}")
        sb.appendLine("Port                  : ${port ?: "not configured"}")
        sb.appendLine("TCP connected         : ${if (firstNmea) "yes (NMEA flowing)" else "unknown (no NMEA seen this session)"}")
        sb.appendLine("First NMEA received   : ${yesNo(firstNmea)}")
        sb.appendLine("First fix emitted     : ${yesNo(firstFix)}")
        sb.appendLine()
        sb.appendLine("--- Sentence counts (cumulative this session) ---")
        sb.appendLine("GGA                   : ${timing.ggaCount}")
        sb.appendLine("RMC                   : ${timing.rmcCount}")
        sb.appendLine("ZDA                   : ${timing.zdaCount}")
        sb.appendLine("Emitted fixes         : ${timing.emittedFixCount}")
        sb.appendLine("Last fix date source  : ${dateSourceLabel(timing.lastTimestampSource)}")
        sb.appendLine("(Live per-second rates are not sampled; counts above are cumulative.)")
        sb.appendLine()
        sb.appendLine("--- Reach RS4 / RS4 Pro NMEA ---")
        sb.appendLine("EBP seen              : ${seenLine(custom.ebpSeen, custom.ebpCount)}")
        sb.appendLine("ETC seen              : ${seenLine(custom.etcSeen, custom.etcCount)}")
        custom.latestEtc?.let { etc ->
            sb.appendLine(
                "Latest ETC            : tilt=${etc.tiltAngleDeg ?: "?"}° heading=${etc.headingDeg ?: "?"}° " +
                    "status=${etc.tiltStatusRaw ?: "?"} warning=${etc.warningRaw ?: "?"}"
            )
        }
        return sb.toString()
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "yes (N)" when seen, otherwise a non-error note (absent EBP/ETC is normal for RS2+/Generic). */
    private fun seenLine(seen: Boolean, count: Int): String =
        if (seen) "yes ($count)" else "no (normal for RS2+ / Generic NMEA TCP)"

    private fun dateSourceLabel(src: TimestampSource?): String = when (src) {
        TimestampSource.NMEA_ZDA      -> "GGA time + ZDA date"
        TimestampSource.GNSS_PROVIDER -> "GGA time + RMC date"
        TimestampSource.DEVICE        -> "GGA time + device/UTC date (fallback)"
        null                          -> "none yet"
        else                          -> src.name
    }
}
