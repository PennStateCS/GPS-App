package app.surrealar.ui.toolbar.report

import app.surrealar.coords.CoordinateFormat
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.receiver.selection.LiveSourceState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.SourceTransition
import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.receiver.tilt.TiltStatusText
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.ui.home.FixSnapshots
import app.surrealar.ui.home.HomeFieldStatusInputs
import app.surrealar.ui.home.HomeFieldStatusMapper
import app.surrealar.ui.toolbar.GnssToolbarState
import app.surrealar.ui.toolbar.ToolbarFrame
import app.surrealar.ui.toolbar.ToolbarInputs
import app.surrealar.ui.toolbar.ToolbarMapResult
import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyStatus
import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyValidity
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import java.util.Locale

/** Row emphasis; the value text always says the same thing (never colour only). */
enum class ReportLevel { NORMAL, GOOD, CAUTION, WARNING }

/** One report row. [copy] = false keeps the row out of "Copy status" (the receiver's address). */
data class ReportRow(
    val label: String,
    val value: String,
    val note: String? = null,
    val level: ReportLevel = ReportLevel.NORMAL,
    val copy: Boolean = true,
)

/** A report section; [collapsible] sections start collapsed (Advanced diagnostics). */
data class ReportSection(val id: String, val title: String, val rows: List<ReportRow>, val collapsible: Boolean = false)

/** The whole GNSS Status report, ready to bind. */
data class GnssStatusReport(
    val headline: String,
    val level: ReportLevel,
    /** All active warnings (the compact toolbar's list), highest priority first. */
    val warnings: List<String>,
    val sections: List<ReportSection>,
    /** A grid system is configured, so the toolbar's coordinate display can be switched. */
    val coordToggleAvailable: Boolean,
    /** The toolbar currently shows lat/lon although a grid is configured. */
    val showingLatLon: Boolean,
)

/** Everything the report reads: the toolbar's own latest frame plus existing status flows (no new GNSS input). */
data class GnssReportInputs(
    /** The toolbar pipeline's latest frame (same fix, conversion and interpretation the toolbar shows). */
    val frame: ToolbarFrame?,
    val selection: ReceiverSelectionState,
    val live: LiveSourceState,
    val receiver: ExternalReceiverSettings?,
    val battery: ReceiverBattery? = null,
    val deviceInfo: ReceiverDeviceInfo? = null,
    val corrections: ReceiverCorrections? = null,
    val tilt: TiltStatus? = null,
    val diag: DiagnosticData = DiagnosticData(),
    val recentParseErrors: Long = 0,
)

/**
 * Builds the GNSS Status report (pure, no Android). Sections: A Receiver & connection, B GNSS solution,
 * C Corrections, D Position, E Receiver-specific (RS4 / RS4 Pro tilt), F Advanced diagnostics (collapsed).
 *
 * Consistency: the headline is the toolbar's highest-priority warning (shared [GnssStatusInterpreter], with
 * the toolbar's correction hysteresis), otherwise the Home Field Status headline built from the same inputs by
 * [HomeFieldStatusMapper] — so the report, the toolbar and the Home card never disagree. "Unavailable" / "Not
 * reported" always means unknown; a 0 is a real zero.
 */
object GnssStatusReportPresenter {

    const val UNAVAILABLE = "Unavailable"
    const val NOT_REPORTED = "Not reported"

    fun build(i: GnssReportInputs): GnssStatusReport {
        val frame = i.frame
        val state = (frame?.result as? ToolbarMapResult.Render)?.state
        val inputs = frame?.inputs
        val external = (inputs?.source ?: i.selection.selectedSource) != LocationSourceType.INTERNAL
        val replay = inputs?.replayActive == true || i.live.kind == LiveSourceKind.REPLAY
        // Solution values only for a live (not stale, not waiting) fix the toolbar shows.
        val liveFix = frame?.inputs?.fix?.takeIf { state != null && !state.isWaiting && !state.isStale }

        val home = runCatching { homeStatus(i) }.getOrNull()
        val primary = state?.primaryWarning
        val headline = primary?.text ?: home?.headline ?: state?.fixText ?: "No GNSS data yet"
        val level = primary?.let { levelOf(it.severity) } ?: home?.let { levelOf(it.severity) } ?: ReportLevel.NORMAL

        val sections = listOf(
            receiverSection(i, state, external, replay),
            solutionSection(i, state, liveFix, headline, level, external),
            correctionsSection(i, state, liveFix, external, replay),
            positionSection(frame, state),
            receiverSpecificSection(i, external),
            advancedSection(i, liveFix, external),
        ).filterNotNull()
        return GnssStatusReport(
            headline = headline,
            level = level,
            warnings = state?.warnings?.map { it.text }.orEmpty(),
            sections = sections,
            coordToggleAvailable = state?.coordToggleAvailable == true,
            showingLatLon = state?.coordLabel == "LL",
        )
    }

    /** The Home card's status for the same inputs (Home precedence, raw staleness). */
    fun homeStatus(i: GnssReportInputs): HomeFieldStatusMapper.FieldStatus? {
        val inputs = i.frame?.inputs ?: return null
        val receiver = i.receiver ?: return null
        return HomeFieldStatusMapper.map(HomeFieldStatusInputs.build(
            snapshot = FixSnapshots.of(inputs.fix), fixProvider = inputs.fix?.provider, source = inputs.source,
            receiver = receiver, diag = i.diag, sky = inputs.sky, conn = i.selection, tilt = i.tilt,
            recentParseErrors = i.recentParseErrors, liveSource = i.live,
        ))
    }

    // ── A: Receiver & connection ──────────────────────────────────────────────────────────────

    private fun receiverSection(i: GnssReportInputs, s: GnssToolbarState?, external: Boolean, replay: Boolean): ReportSection {
        val rows = mutableListOf<ReportRow>()
        rows += ReportRow("Source", when {
            replay -> "NMEA replay (recorded data)"
            external -> "External receiver"
            else -> "Device GNSS"
        })
        val liveExt = i.live.external.takeIf { i.live.kind == LiveSourceKind.EXTERNAL }
        if (external && !replay) {
            val identity = liveExt?.identity
            val model = identity?.takeIf { it.modelVerified }?.let { it.recognizedProfile?.label ?: it.reportedModel }
            val name = identity?.name?.takeIf { it.isNotBlank() } ?: liveExt?.name
            rows += if (liveExt != null) ReportRow("Receiver", listOfNotNull(name, model).distinct().joinToString(" · ")
                .ifBlank { i.selection.profile.label }, note = "Profile: ${i.selection.profile.label}")
            else ReportRow("Receiver", (i.selection.savedName?.takeIf { it.isNotBlank() } ?: i.receiver?.displayName?.takeIf { it.isNotBlank() }
                ?: i.selection.profile.label), note = "Selected — not the live source yet")
        }
        rows += connectionRow(i, s, external, replay)
        if (external && !replay) {
            val b = i.battery
            rows += if (b == null) ReportRow("Battery", NOT_REPORTED)
            else ReportRow("Battery", "${b.percent.coerceIn(0, 100)} %" + if (b.isCharging) ", charging" else "",
                level = if (b.percent <= app.surrealar.ui.toolbar.ToolbarBatteryPolicy.LOW_PERCENT) ReportLevel.CAUTION else ReportLevel.NORMAL)
            val fw = liveExt?.identity?.firmware?.takeIf { it.isNotBlank() }
                ?: i.deviceInfo?.firmware?.takeIf { it.isNotBlank() && liveExt != null }
            rows += ReportRow("Firmware", fw ?: NOT_REPORTED)
        }
        if (replay) rows += ReportRow("Replay", "Replaying a recorded NMEA log", note = "Replay positions are never captured")
        return ReportSection("receiver", "Receiver & connection", rows)
    }

    private fun connectionRow(i: GnssReportInputs, s: GnssToolbarState?, external: Boolean, replay: Boolean): ReportRow {
        if (replay) return ReportRow("Connection", "Replay running")
        if (!external) return ReportRow("Connection", if (i.live.kind == LiveSourceKind.DEVICE) "Device GNSS active" else
            (s?.fixText?.let { "Waiting for position ($it)" } ?: "Waiting for position"))
        return when (val tr = i.live.transition) {
            is SourceTransition.Testing -> ReportRow("Connection", "Testing another receiver…", note = "The current source stays live")
            is SourceTransition.Switching -> ReportRow("Connection", "Switching receiver…", level = ReportLevel.CAUTION)
            is SourceTransition.Connecting -> ReportRow("Connection", "Connecting…")
            is SourceTransition.Reconnecting -> ReportRow("Connection", "Link lost, reconnecting…", level = ReportLevel.CAUTION)
            is SourceTransition.WaitingForPosition -> ReportRow("Connection", "Connected, waiting for position", level = ReportLevel.CAUTION)
            is SourceTransition.ConnectFailed -> ReportRow("Connection", "Connection failed", note = tr.error.message, level = ReportLevel.WARNING)
            is SourceTransition.ExternalNotActive -> ReportRow("Connection", "Receiver selected but not connected", level = ReportLevel.WARNING)
            null -> if (i.live.kind == LiveSourceKind.EXTERNAL) ReportRow("Connection", "Connected · receiving positions")
                else ReportRow("Connection", s?.fixText ?: UNAVAILABLE)
        }
    }

    // ── B: GNSS solution ──────────────────────────────────────────────────────────────────────

    private fun solutionSection(
        i: GnssReportInputs, s: GnssToolbarState?, fix: Fix?, headline: String, level: ReportLevel, external: Boolean,
    ): ReportSection {
        val rows = mutableListOf<ReportRow>()
        rows += ReportRow("Status", headline, level = level,
            note = s?.warnings?.drop(1)?.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Also: ") { it.text })
        rows += ReportRow("Solution", s?.fixText ?: UNAVAILABLE,
            note = if (s?.isStale == true) "Last known position — not current, not for capture" else null,
            level = if (s?.isStale == true) ReportLevel.WARNING else ReportLevel.NORMAL)
        if (fix == null) {
            rows += ReportRow("Horizontal accuracy", UNAVAILABLE)
            rows += ReportRow("Satellites", UNAVAILABLE)
            return ReportSection("solution", "GNSS solution", rows)
        }
        val estimated = fix.hAccM == null
        val h = s?.compactAccuracyText
        rows += ReportRow("Horizontal accuracy",
            h ?: UNAVAILABLE,
            note = when {
                h == null -> null
                estimated -> "Estimated from DOP (the receiver did not report it) · 68 % radius"
                else -> "Reported by the receiver · 68 % radius"
            },
            level = when {
                h == null -> ReportLevel.NORMAL
                s.quality == GnssStatusInterpreter.Quality.HIGH_ACCURACY && s.warnings.isEmpty() -> ReportLevel.GOOD
                else -> ReportLevel.NORMAL
            })
        if (external) {
            val v = fix.vAccM
            rows += when (VerticalAccuracyValidity.classify(v)) {
                VerticalAccuracyStatus.NOT_REPORTED -> ReportRow("Vertical accuracy", NOT_REPORTED)
                VerticalAccuracyStatus.INVALID -> ReportRow("Vertical accuracy", "Invalid (receiver marker)",
                    note = "The receiver marked its height as invalid — not an accuracy", level = ReportLevel.CAUTION)
                VerticalAccuracyStatus.VALID -> ReportRow("Vertical accuracy", "±${f(v!!, 3)} m", note = "Reported by the receiver · 1σ")
            }
        } else rows += ReportRow("Vertical accuracy", "Not used for device GNSS")
        val sky = i.frame?.inputs?.sky
        val used = sky?.totalUsed?.takeIf { it > 0 } ?: fix.satsUsed
        val vis = sky?.totalVisible?.takeIf { it > 0 } ?: fix.satsVisible
        rows += ReportRow("Satellites", if (used == null && vis == null) UNAVAILABLE
            else "${used?.toString() ?: "?"} used / ${vis?.toString() ?: "?"} visible")
        val dops = listOf("HDOP" to fix.hDop, "VDOP" to fix.vDop, "PDOP" to fix.pDop)
        rows += ReportRow("DOP", if (dops.all { it.second == null }) UNAVAILABLE
            else dops.joinToString(" · ") { (k, d) -> "$k ${d?.let { f(it, 1) } ?: "–"}" })
        return ReportSection("solution", "GNSS solution", rows)
    }

    // ── C: Corrections ────────────────────────────────────────────────────────────────────────

    private fun correctionsSection(
        i: GnssReportInputs, s: GnssToolbarState?, fix: Fix?, external: Boolean, replay: Boolean,
    ): ReportSection {
        val title = "Corrections"
        if (!external || replay) return ReportSection("corrections", title,
            listOf(ReportRow("Corrections", if (replay) "Not monitored during replay" else "Not applicable (device GNSS)")))
        val agingS = i.frame?.inputs?.correctionAgingS ?: ToolbarInputs.DEFAULT_CORRECTION_AGING_S
        val staleS = GnssStatusInterpreter.STALE_CORRECTION_S
        val age = fix?.diffAgeS?.takeIf { it.isFinite() && it >= 0.0 }
        val rows = mutableListOf<ReportRow>()
        rows += when {
            fix == null -> ReportRow("Health", "Unknown (no live position)")
            else -> when (s?.correctionHealth ?: CorrectionHealth.OK) {
                CorrectionHealth.OK -> ReportRow("Health", if (age != null) "Within limits" else "No correction age reported")
                CorrectionHealth.AGING -> ReportRow("Health", "Corrections aging",
                    note = "Older than ${trim(agingS)} s: captures that require fresh corrections stop accepting fixes", level = ReportLevel.CAUTION)
                CorrectionHealth.STALE -> ReportRow("Health", "Corrections stale",
                    note = "Older than ${trim(staleS)} s: RTK accuracy is no longer assured", level = ReportLevel.WARNING)
                CorrectionHealth.UNAVAILABLE -> ReportRow("Health", "Corrections unavailable",
                    note = "The receiver stopped applying corrections", level = ReportLevel.WARNING)
            }
        }
        rows += ReportRow("Correction age", age?.let { "${f(it, 1)} s" } ?: NOT_REPORTED)
        val c = i.corrections
        rows += ReportRow("Correction link", when {
            c == null -> NOT_REPORTED
            c.isReceiving -> "Receiving" + (c.channel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            else -> "Not receiving" + (c.channel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }, level = if (c != null && !c.isReceiving) ReportLevel.WARNING else ReportLevel.NORMAL)
        val station = fix?.correctionStationId?.takeIf { it.isNotBlank() } ?: c?.stationId?.takeIf { it.isNotBlank() }
        rows += ReportRow("Base station", station ?: NOT_REPORTED,
            note = c?.baselineKm?.let { "Baseline ${f(it, 1)} km" })
        rows += ReportRow("Limits", "Aging above ${trim(agingS)} s · stale above ${trim(staleS)} s",
            note = "Aging = the capture correction-age limit (Settings → Capture); stale = Home card rule")
        return ReportSection("corrections", title, rows)
    }

    // ── D: Position ───────────────────────────────────────────────────────────────────────────

    private fun positionSection(frame: ToolbarFrame?, s: GnssToolbarState?): ReportSection {
        val rows = mutableListOf<ReportRow>()
        val p = frame?.coords
        if (s == null || s.latLonText == "--") {
            rows += ReportRow("Position", UNAVAILABLE)
            return ReportSection("position", "Position", rows)
        }
        if (s.isStale) rows += ReportRow("Status", "Last known position", note = "Not current and not for capture; " +
            "cleared ${app.surrealar.ui.toolbar.GnssToolbarStateMapper.STALE_KEEP_MS / 1000} s after the fix was lost",
            level = ReportLevel.WARNING)
        val fix = frame?.inputs?.fix
        if (p != null) {
            rows += ReportRow("Toolbar shows", if (s.coordLabel == "NE") "Grid coordinates (NE)" else "Latitude / longitude (LL)")
            rows += ReportRow("Latitude, longitude", String.format(Locale.US, "%.8f, %.8f", p.latDeg, p.lonDeg))
            if (p.easting != null && p.northing != null) rows += ReportRow("Grid", CoordinateFormat.horizontal(p),
                note = p.zoneLabel, level = if (p.outsideZone) ReportLevel.WARNING else ReportLevel.NORMAL)
            rows += ReportRow("Reference system", CoordinateFormat.systemSummary(p))
            rows += ReportRow("Coordinate epoch", p.epochYear?.let { String.format(Locale.US, "%.3f", it) }
                ?: "Not applicable for this frame")
            rows += ReportRow("Elevation", if (p.height == null && !p.heightLoading) UNAVAILABLE else CoordinateFormat.heightOrLoading(p),
                note = if (p.height == null) p.heightUnavailableReason else "Antenna phase centre (no pole height removed)")
            rows += ReportRow("Units", p.unit.label)
        } else if (fix != null) {
            // Conversion unavailable: what the receiver sent (the toolbar falls back the same way).
            rows += ReportRow("Latitude, longitude", String.format(Locale.US, "%.8f, %.8f", fix.latDeg, fix.lonDeg),
                note = "As received (coordinate conversion unavailable)")
            rows += ReportRow("Elevation", s.altitudeText.takeIf { it != "--" } ?: UNAVAILABLE, note = s.altitudeLabel)
        }
        return ReportSection("position", "Position", rows)
    }

    // ── E: Receiver-specific (RS4 / RS4 Pro tilt) ─────────────────────────────────────────────

    private fun receiverSpecificSection(i: GnssReportInputs, external: Boolean): ReportSection? {
        val t = i.tilt?.takeIf { external && it.applicable } ?: return null
        val rows = mutableListOf<ReportRow>()
        rows += ReportRow("Tilt", TiltStatusText.stateLine(t).removePrefix("Tilt: "))
        val ref = GnssStatusInterpreter.referenceOf(t.applicable, t.reference, t.compensatedOutput)
        rows += ReportRow("Position reference", TiltStatusText.referenceLine(t).removePrefix("Position: "),
            level = when (ref) {
                GnssStatusInterpreter.Reference.MAY_BE_COMPENSATED -> ReportLevel.WARNING
                GnssStatusInterpreter.Reference.UNCONFIRMED -> ReportLevel.CAUTION
                else -> ReportLevel.NORMAL
            },
            note = when (ref) {
                GnssStatusInterpreter.Reference.POLE_TIP -> "Compensated pole tip (confirmed)"
                GnssStatusInterpreter.Reference.ANTENNA -> "Antenna (confirmed)"
                GnssStatusInterpreter.Reference.UNCONFIRMED, GnssStatusInterpreter.Reference.MAY_BE_COMPENSATED -> "Not confirmed"
                null -> null
            })
        TiltStatusText.warningLine(t)?.let { rows += ReportRow("Tilt warning", it, level = ReportLevel.CAUTION) }
        rows += ReportRow("Compensated NMEA output", t.compensatedOutput.label,
            level = if (t.reference == EpochReference.MAY_BE_COMPENSATED) ReportLevel.CAUTION else ReportLevel.NORMAL)
        t.firmware?.takeIf { it.isNotBlank() }?.let { rows += ReportRow("Receiver firmware (tilt)", it) }
        return ReportSection("receiver_specific", "Receiver-specific", rows)
    }

    // ── F: Advanced diagnostics ───────────────────────────────────────────────────────────────

    private fun advancedSection(i: GnssReportInputs, fix: Fix?, external: Boolean): ReportSection {
        val d = i.diag
        val rows = mutableListOf<ReportRow>()
        rows += ReportRow("NMEA stream", when {
            d.linesPerSecond >= 1.0 -> "${d.linesPerSecond.toInt()} lines/s"
            d.totalLinesProcessed == 0L -> "No data received"
            else -> "No data (stopped)"
        })
        rows += ReportRow("Lines processed", d.totalLinesProcessed.toString())
        rows += ReportRow("Parse errors", "${d.totalParseErrors} total · ${i.recentParseErrors} in the last minute",
            level = if (i.recentParseErrors > 0) ReportLevel.CAUTION else ReportLevel.NORMAL)
        rows += ReportRow("Active provider", i.selection.activeProvider.name)
        if (external) {
            rows += ReportRow("Receiver address", i.selection.savedTarget?.toString() ?: "None saved", copy = false)
            rows += ReportRow("Link", i.selection.link?.name ?: UNAVAILABLE)
            rows += ReportRow("Profile", "${i.selection.profile.label} (${i.selection.profileOrigin.name.lowercase(Locale.ROOT).replace('_', ' ')})")
        }
        rows += ReportRow("Fix time (UTC)", fix?.timeUtc?.toString() ?: UNAVAILABLE)
        rows += ReportRow("Provider of fix", fix?.provider?.name ?: UNAVAILABLE)
        return ReportSection("advanced", "Advanced diagnostics", rows, collapsible = true)
    }

    // ── Copy status ───────────────────────────────────────────────────────────────────────────

    /**
     * Readable snapshot for "Copy status": [timestamp], headline, warnings and every section's rows (collapsed
     * sections included) except rows marked not to copy (the receiver's address).
     */
    fun snapshotText(r: GnssStatusReport, timestamp: String): String = buildString {
        appendLine("SurReal AR — GNSS status")
        appendLine("Time: $timestamp")
        appendLine("Status: ${r.headline}")
        appendLine("Warnings: " + if (r.warnings.isEmpty()) "none" else r.warnings.joinToString("; "))
        for (sec in r.sections) {
            appendLine()
            appendLine(sec.title)
            for (row in sec.rows) if (row.copy) {
                append("  ").append(row.label).append(": ").append(row.value)
                row.note?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
                appendLine()
            }
        }
    }.trimEnd()

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    fun levelOf(s: GnssStatusInterpreter.Severity): ReportLevel = when (s) {
        GnssStatusInterpreter.Severity.GOOD -> ReportLevel.GOOD
        GnssStatusInterpreter.Severity.CAUTION -> ReportLevel.CAUTION
        GnssStatusInterpreter.Severity.WARNING -> ReportLevel.WARNING
        GnssStatusInterpreter.Severity.NEUTRAL -> ReportLevel.NORMAL
    }

    private fun levelOf(s: HomeFieldStatusMapper.Severity): ReportLevel = levelOf(GnssStatusInterpreter.Severity.valueOf(s.name))

    private fun f(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)
    private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else f(v, 1)
}
