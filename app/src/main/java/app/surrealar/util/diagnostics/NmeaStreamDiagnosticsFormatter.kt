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
        sb.appendLine("GST                   : ${timing.gstCount}")
        sb.appendLine("GSA by constellation  : ${byTalker(timing.gsaCountByTalker)}")
        sb.appendLine("GSV by constellation  : ${byTalker(timing.gsvCountByTalker)}")
        sb.appendLine("Emitted fixes         : ${timing.emittedFixCount}")
        sb.appendLine("Last fix date source  : ${dateSourceLabel(timing.lastTimestampSource)}")
        sb.appendLine("(Checksum-failure and unsupported-sentence-by-type counts are in the app log, tag \"NMEA\".)")
        sb.appendLine()
        sb.appendLine("--- Last emitted fix (\"unknown\" = not provided by receiver, never 0.0) ---")
        sb.appendLine("RTK status            : ${timing.lastRtkStatus?.name ?: "unknown"}")
        sb.appendLine("Satellites used       : ${timing.lastSatsUsed?.toString() ?: "unknown"}")
        sb.appendLine("Satellites visible    : ${timing.lastSatsVisible?.toString() ?: "unknown"}")
        sb.appendLine("MSL altitude (m)      : ${timing.lastAltMslM?.let { "%.3f".format(it) } ?: "unknown"}")
        sb.appendLine("Geoid separation (m)  : ${timing.lastGeoidSepM?.let { "%.3f".format(it) } ?: "unknown"}")
        sb.appendLine("Horizontal accuracy   : ${timing.lastHAccM?.let { "±%.3f m".format(it) } ?: "unknown"}")
        sb.appendLine("Accuracy source       : ${accuracySourceLabel(timing.lastAccuracySource)}")
        sb.appendLine()
        sb.appendLine("--- Reach RS4 / RS4 Pro NMEA ---")
        sb.appendLine("EBP seen              : ${seenLine(custom.ebpSeen, custom.ebpCount)} (base/reference position — never used as the rover fix)")
        sb.appendLine("ETC seen              : ${seenLine(custom.etcSeen, custom.etcCount)}")
        sb.appendLine("IMU/orientation seen  : ${yesNo(custom.imuOrientationSeen)}")
        custom.latestEtc?.let { etc ->
            val body = if (etc.hasOrientationData)
                "orientation/IMU present time=${etc.timeRaw ?: "?"} rawFieldCount=${etc.dataFields.size} rawFields=${etc.dataFields}"
            else
                "timestamp only (no orientation data)"
            sb.appendLine("Latest ETC            : $body")
        }
        sb.appendLine("Note: GNETC field format is undocumented and is NOT mapped to AR heading/tilt/orientation.")
        return sb.toString()
    }

    /** "GP=354 GL=250 GA=354 GB=354" or "none". */
    private fun byTalker(counts: Map<String, Int>): String =
        if (counts.isEmpty()) "none" else counts.entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun accuracySourceLabel(src: NmeaFuser.AccuracySource): String = when (src) {
        NmeaFuser.AccuracySource.RECEIVER_GST  -> "receiver_gst (GST std-dev)"
        NmeaFuser.AccuracySource.DOP_ESTIMATE  -> "dop_estimate (HDOP × UERE, downstream)"
        NmeaFuser.AccuracySource.UNKNOWN       -> "unknown"
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
