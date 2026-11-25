package app.surrealar.util.diagnostics.report

import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.receiver.tilt.TiltStatusText
import app.surrealar.util.diagnostics.CaptureInterruptions
import java.util.Locale

/**
 * survey-context.txt (Phase 4 WP C): what a surveyor or geodesist needs to interpret this session's positions —
 * receiver and connection, frames and settings, the latest fix's quality, NMEA availability, tilt state, antenna
 * settings, position-reference interpretation, capture interruptions, coordinate systems, geoid and
 * transformation-path status.
 *
 * Privacy (same rules as the rest of the report; [ReportRedactor] still scrubs the output): no position, no
 * position history, no host/IP, no NTRIP credentials, passwords, tokens or account data. The latest fix is
 * described by quality only (fix type, accuracy, satellites, DOP, correction age, coordinate epoch), never by
 * coordinates. Pure renderer: the exporter gathers [Input].
 */
object SurveyContextCollector {

    /** The latest fix's quality (no coordinates). */
    data class FixQuality(
        val provider: String,
        val fixType: String,
        val coordinateEpoch: Double?,
        val timestampSource: String?,
        val correctionAgeS: Double?,
        val hAccM: Double?,
        val vAccM: Double?,
        val satsUsed: Int?,
        val satsVisible: Int?,
        val hdop: Double?,
        val pdop: Double?,
        val ageS: Long?,
    )

    data class Input(
        val activeSource: String?,
        val receiverProfile: String?,
        val receiverModel: String?,
        val connectionType: String?,
        val receiverFrame: String?,
        val compensatedOutput: String?,
        val firmware: String?,
        val fix: FixQuality?,
        /** Sentence counts this session (GGA, RMC, GST, ETC …) and the live line rate. */
        val sentenceCounts: List<Pair<String, String>>,
        val nmeaLinesPerSecond: Double?,
        val tilt: TiltStatus?,
        val poleHeightM: Double?,
        val phaseCenterOffsetM: Double?,
        val phaseCenterFromProfile: Boolean?,
        val cameraBelowReceiverM: Double?,
        val captureOffsetM: Double?,
        val interruptions: CaptureInterruptions.Snapshot,
        val horizontal: String?,
        val vertical: String?,
        val outputDatum: String?,
        val betaPreview: Boolean?,
        val betaSuspended: List<String>,
        val geoidModel: String?,
        val geoidVersion: String?,
        val geoidLoadState: String?,
        val transformationPaths: List<String>,
        val gridFailures: List<String>,
    )

    fun render(i: Input): String = buildString {
        appendLine("=== Survey context ===")
        appendLine("For interpreting this session's positions. No coordinates, position history, host/IP or credentials.")
        appendLine()
        appendLine("--- Receiver ---")
        row("Active GNSS source", i.activeSource)
        row("Receiver profile", i.receiverProfile)
        row("Receiver model", i.receiverModel)
        row("Connection type", i.connectionType)
        row("Receiver frame setting", i.receiverFrame)
        row("Compensated NMEA output", i.compensatedOutput)
        row("Firmware", i.firmware ?: "unknown (receiver did not report it)")
        appendLine()
        appendLine("--- Latest fix (quality only) ---")
        val f = i.fix
        if (f == null) appendLine("No live fix.") else {
            row("Provider", f.provider)
            row("Fix type", f.fixType)
            row("Coordinate epoch", f.coordinateEpoch?.let { String.format(Locale.US, "%.3f", it) })
            row("Time source", f.timestampSource)
            row("Correction age", f.correctionAgeS?.let { String.format(Locale.US, "%.1f s", it) })
            row("Horizontal accuracy", f.hAccM?.let { String.format(Locale.US, "%.3f m (68%% radius)", it) })
            row("Vertical accuracy", f.vAccM?.let { String.format(Locale.US, "%.3f m (1 sigma)", it) })
            row("Satellites used/visible", "${f.satsUsed ?: "?"} / ${f.satsVisible ?: "?"}")
            row("HDOP / PDOP", "${f.hdop?.let { fmt2(it) } ?: "?"} / ${f.pdop?.let { fmt2(it) } ?: "?"}")
            row("Fix age", f.ageS?.let { "$it s" })
        }
        appendLine()
        appendLine("--- NMEA messages (external stream, this session) ---")
        row("Line rate", i.nmeaLinesPerSecond?.let { String.format(Locale.US, "%.1f lines/s", it) })
        if (i.sentenceCounts.isEmpty()) appendLine("No sentence counts.")
        i.sentenceCounts.forEach { (k, v) -> row(k, v) }
        i.tilt?.let { row("ETC rate", TiltStatusText.etcRateText(it)) }
        appendLine()
        appendLine("--- Tilt and position reference (RS4 / RS4 Pro) ---")
        val t = i.tilt
        if (t == null || !t.applicable) appendLine("Not applicable for this receiver profile (no IMU / no ETC expected).") else {
            row("Tilt state", TiltStatusText.stateLine(t).removePrefix("Tilt: "))
            row("Tilt data freshness", when {
                !t.etcSeen -> "no ETC received since the source started"
                t.etcLive -> "live (last ETC ${t.etcAgeMs ?: "?"} ms ago)"
                else -> "stale (last ETC ${t.etcAgeMs?.let { it / 1000 } ?: "?"} s ago)"
            })
            row("Tilt warning", TiltStatusText.warningLine(t)?.removePrefix("Tilt warning: ") ?: "none")
            row("Position reference", t.reference?.let { TiltStatusText.referenceLabel(it) } ?: "not interpreted (no recent fix)")
            row("Reference reason", t.result?.reason?.name ?: "-")
            row("ETC match (latest fix)", t.match?.name ?: "-")
            row("Antenna may be tilted", t.result?.antennaMayBeTilted?.let { if (it) "yes (keep the pole plumb)" else "no" })
        }
        appendLine()
        appendLine("--- Antenna setup (settings) ---")
        row("Pole height (mark to receiver bottom)", i.poleHeightM?.let { fmtM(it) })
        row("Phase-centre offset", i.phaseCenterOffsetM?.let { fmtM(it) + if (i.phaseCenterFromProfile == true) " (receiver profile)" else " (entered)" })
        row("Capture reduction (pole + offset)", i.captureOffsetM?.let { fmtM(it) + " (not applied to receiver-compensated pole-tip captures)" })
        row("Camera below receiver (AR)", i.cameraBelowReceiverM?.let { fmtM(it) })
        appendLine()
        appendLine("--- Capture interruptions (this process) ---")
        val c = i.interruptions
        row("Total", c.total.toString())
        c.counts.forEach { (code, n) -> row(code, n.toString()) }
        row("Last reason", c.lastReason ?: "none")
        appendLine()
        appendLine("--- Coordinate systems ---")
        row("Horizontal system", i.horizontal)
        row("Output datum", i.outputDatum)
        row("Height reference", i.vertical)
        row("NSRS 2022 beta preview", i.betaPreview?.let { if (it) "ON" else "OFF" })
        if (i.betaSuspended.isNotEmpty()) row("Beta choices not in use", i.betaSuspended.joinToString(", "))
        row("Geoid model", i.geoidModel)
        row("Geoid model version", i.geoidVersion)
        row("Geoid load state", i.geoidLoadState)
        appendLine()
        appendLine("--- Transformation paths ---")
        if (i.transformationPaths.isEmpty()) appendLine("unknown") else i.transformationPaths.forEach { appendLine("  $it") }
        appendLine()
        appendLine("--- Grid / model loading failures ---")
        if (i.gridFailures.isEmpty()) appendLine("none") else i.gridFailures.forEach { appendLine("  $it") }
    }

    private fun StringBuilder.row(label: String, value: String?) {
        appendLine("${label.padEnd(LABEL_WIDTH)} : ${value ?: "unknown"}")
    }

    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun fmtM(v: Double) = String.format(Locale.US, "%.3f m", v)

    private const val LABEL_WIDTH = 38
}
