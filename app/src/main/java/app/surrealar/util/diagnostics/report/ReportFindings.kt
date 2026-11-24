package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import app.surrealar.util.diagnostics.incident.IncidentMarker
import java.util.Locale

/** One line of evidence: when, which file in the ZIP, and a short message. */
data class Evidence(val epochMs: Long?, val file: String, val text: String)

/**
 * A deterministic finding for the summary. [certainty] is about the observation itself; how it relates
 * to the reported problem is [incidentRelation] (timing only — never stated as the root cause).
 */
data class Finding(
    val rule: String,
    val severity: Severity,
    val certainty: Certainty,
    val title: String,
    val evidence: List<Evidence>,
    val nextStep: String,
    val incidentRelation: String? = null,
) {
    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW }
    enum class Certainty {
        /** The logs or the state at export show it happened. */
        OBSERVED,
        /** Consistent with the evidence, but not shown directly. */
        POSSIBLE_CAUSE,
        /** The information needed to tell is missing. */
        UNKNOWN,
    }
}

/** Everything the findings rules look at. All nullable inputs mean "unknown". */
data class FindingsInput(
    val nowMs: Long,
    val signals: List<Signal> = emptyList(),
    val crashes: List<LogEntry> = emptyList(),
    val sessionStarts: List<LogEntry> = emptyList(),
    /** The latest marked problem; the rules focus on the minutes around it. */
    val incident: IncidentMarker? = null,
    val live: LiveState? = null,
    val device: DeviceHealth? = null,
    val data: DataHealth? = null,
    val info: ReportInfo = ReportInfo(),
)

/**
 * The summary's "Important findings" and "Subsystem status": fixed rules over [FindingsInput], no
 * guessing. Each rule reports what was OBSERVED; when it happened shortly before a marked problem it
 * says so as a POSSIBLE_CAUSE relation (timing only). Pure and deterministic (no clock reads).
 */
object ReportFindings {

    const val MAX_FINDINGS = 8
    /** With an incident: rules look from this long before it … */
    const val FOCUS_BEFORE_MS = 15L * 60_000L
    /** … to this long after it. */
    const val FOCUS_AFTER_MS = 2L * 60_000L
    /** An event this close before the mark is reported as a possible cause of the problem. */
    const val RELATION_MS = 5L * 60_000L
    const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
    const val LOW_STORAGE_PERCENT = 5
    const val STALE_FIX_MS = 5_000L
    const val STALE_CORRECTIONS_S = 10.0
    const val RECONNECT_BURST = 3
    const val RECONNECT_WINDOW_MS = 10L * 60_000L
    private const val MAX_EVIDENCE = 3

    /** The window the rules look at: around the latest incident, else the current session (or the last 2 h). */
    fun focus(input: FindingsInput): LongRange {
        input.incident?.let { return (it.epochMs - FOCUS_BEFORE_MS)..(it.epochMs + FOCUS_AFTER_MS) }
        val start = input.sessionStarts.maxOfOrNull { it.epochMs } ?: (input.nowMs - LogIndex.TIMELINE_MS)
        return start..input.nowMs
    }

    fun evaluate(input: FindingsInput): List<Finding> {
        val ctx = Ctx(input)
        val all = buildList {
            addAll(crash(ctx))
            receiverDown(ctx)?.let(::add)
            noNmea(ctx)?.let(::add)
            reconnects(ctx)?.let(::add)
            noFix(ctx)?.let(::add)
            staleFix(ctx)?.let(::add)
            rtkLost(ctx)?.let(::add)
            corrections(ctx)?.let(::add)
            locationPermission(ctx)?.let(::add)
            cameraPermission(ctx)?.let(::add)
            arUnavailable(ctx)?.let(::add)
            arTracking(ctx)?.let(::add)
            geospatial(ctx)?.let(::add)
            modelFailed(ctx)?.let(::add)
            modelMissing(ctx)?.let(::add)
            sourceMismatch(ctx)?.let(::add)
            antennaHeight(ctx)?.let(::add)
            arAnchor(ctx)?.let(::add)
            modelHidden(ctx)?.let(::add)
            alignmentJump(ctx)?.let(::add)
            noAltitude(ctx)?.let(::add)
            addAll(database(ctx))
            storage(ctx)?.let(::add)
            network(ctx)?.let(::add)
        }
        return all.sortedWith(
            compareBy<Finding> { it.severity.ordinal }
                .thenBy { if (it.incidentRelation?.startsWith(POSSIBLE) == true) 0 else 1 }
                .thenBy { it.certainty.ordinal }
                .thenByDescending { f -> f.evidence.mapNotNull { it.epochMs }.maxOrNull() ?: Long.MIN_VALUE }
                .thenBy { it.rule }
        ).take(MAX_FINDINGS)
    }

    // ── Context shared by the rules ──

    private class Ctx(val input: FindingsInput) {
        val focus = focus(input)
        val incidentMs = input.incident?.epochMs
        val inFocus: List<Signal> = input.signals.filter { it.entry.epochMs in focus }
        fun of(vararg kinds: SignalKind) = inFocus.filter { it.kind in kinds }
        /** The last of [kinds] at or before the incident (or overall without one). */
        fun lastAtIncident(vararg kinds: SignalKind): Signal? =
            inFocus.lastOrNull { it.kind in kinds && (incidentMs == null || it.entry.epochMs <= incidentMs) }
        val ictx get() = input.incident?.context
    }

    private const val POSSIBLE = "POSSIBLE_CAUSE"

    /** Timing of [epochs] relative to the incident, or null when there is none or they are unrelated in time. */
    private fun relate(ctx: Ctx, epochs: List<Long>, what: String, presentAtMark: Boolean = false): String? {
        val t = ctx.incidentMs ?: return null
        val before = epochs.filter { it <= t && t - it <= RELATION_MS }.maxOrNull()
        return when {
            before != null -> "$POSSIBLE of the reported problem: $what ${ago(t - before)} before it was marked (timing only, not proof)"
            presentAtMark -> "$POSSIBLE of the reported problem: $what when it was marked (timing only, not proof)"
            epochs.any { it > t } -> "Observed after the problem was marked; not a cause of it"
            else -> null
        }
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return if (s < 120) "$s s" else "${s / 60} min"
    }

    /** Free text cut for the summary: scrubbed first, so the cut cannot split a secret past the redactor. */
    private fun clip(s: String, n: Int) = ReportRedactor.scrub(s).let { if (it.length > n) it.take(n) + "…" else it }

    private fun ev(s: Signal) = ev(s.entry)
    fun ev(e: LogEntry) = Evidence(e.epochMs, e.source.ifEmpty { "app-log-*.txt" }, "${e.level} ${e.tag} ${clip(e.message, 140)}")

    /** First, the one nearest the incident, and last — at most [MAX_EVIDENCE], chronological. */
    private fun pick(ctx: Ctx, list: List<Signal>): List<Evidence> {
        if (list.size <= MAX_EVIDENCE) return list.map(::ev)
        val t = ctx.incidentMs
        val nearest = if (t != null) list.minByOrNull { kotlin.math.abs(it.entry.epochMs - t) } else list[list.size / 2]
        return listOfNotNull(list.first(), nearest, list.last()).distinct().sortedBy { it.entry.epochMs }.map(::ev)
    }

    private fun incidentEvidence(ctx: Ctx, text: String) =
        Evidence(ctx.incidentMs, "incidents.txt", "at the marked problem: $text")

    private fun recovered(list: List<Signal>, bad: Set<SignalKind>, good: Set<SignalKind>, all: List<Signal>): Boolean? {
        val last = all.lastOrNull { it.kind in bad || it.kind in good } ?: return null
        return last.kind in good && list.isNotEmpty()
    }

    // ── Rules ──

    private fun crash(ctx: Ctx): List<Finding> {
        val input = ctx.input
        val currentSession = input.sessionStarts.maxByOrNull { it.epochMs }?.let { LogIndex.sessionOf(it.message) }
        val recent = input.crashes.filter { input.nowMs - it.epochMs <= LogIndex.DAY_MS || it.epochMs in ctx.focus }
            .sortedBy { it.epochMs }
        val out = mutableListOf<Finding>()
        if (recent.isNotEmpty()) {
            val anr = recent.any { it.message.contains("reason=ANR") }
            val crashed = recent.any { !it.message.contains("reason=ANR") }
            val newest = recent.last()
            // A fatal line names its session; a "Previous process exit" line is written at the next start about the one before.
            val fatalSession = LogIndex.sessionOf(newest.message)
            val where = if (fatalSession != null && fatalSession == currentSession) "current session" else "previous process"
            val what = when { anr && crashed -> "App crash and ANR"; anr -> "App not responding (ANR)"; else -> "App crashed" }
            val t = ctx.incidentMs
            val relation = when {
                t == null -> null
                ctx.input.incident?.sessionId != null && ctx.input.incident?.sessionId == fatalSession ->
                    "$POSSIBLE of the reported problem: the session the problem was marked in ended in this crash (timing only, not proof)"
                else -> relate(ctx, recent.map { it.epochMs }, what.lowercase(Locale.US))
            }
            out += Finding("crash", Finding.Severity.CRITICAL, Finding.Certainty.OBSERVED,
                "$what in the last 24 h (${recent.size}; newest in the $where)",
                recent.takeLast(MAX_EVIDENCE).map { ev(it) },
                "Read the stack trace in app-errors-*.txt and the exit reason in exits.txt (ANR traces in exits/).", relation)
        }
        val blocked = ctx.of(SignalKind.MAIN_THREAD_BLOCKED)
        if (blocked.isNotEmpty()) {
            out += Finding("main_thread_blocked", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
                "Main thread blocked (${blocked.size}x; the screen froze, a possible ANR)", pick(ctx, blocked),
                "The watchdog line has the blocked stack in app-errors-*.txt / app-log-*.txt.",
                relate(ctx, blocked.map { it.entry.epochMs }, "the main thread was blocked"))
        }
        return out
    }

    private val RX_BAD = setOf(SignalKind.RECEIVER_DOWN, SignalKind.RECEIVER_NO_DATA)
    private val RX_GOOD = setOf(SignalKind.RECEIVER_UP)

    private fun receiverDown(ctx: Ctx): Finding? {
        val down = ctx.of(SignalKind.RECEIVER_DOWN, SignalKind.RECEIVER_NOT_CONFIGURED)
        val linkNow = ctx.input.live?.receiverLink
        val ctxLink = ctx.ictx?.receiverLink
        val downAtMark = ctxLink != null && ctxLink.uppercase(Locale.US).let { it.contains("DISCONNECT") || it.contains("ERROR") || it.contains("RECONNECT") }
        if (down.isEmpty() && !downAtMark) return null
        val rec = recovered(down, RX_BAD + SignalKind.RECEIVER_NOT_CONFIGURED, RX_GOOD, ctx.inFocus)
        val state = when (rec) { true -> "recovered"; false -> "not recovered" + (linkNow?.let { " (link at export: $it)" } ?: ""); null -> "state unknown" }
        val notConfigured = down.any { it.kind == SignalKind.RECEIVER_NOT_CONFIGURED }
        val evidence = pick(ctx, down) + listOfNotNull(if (downAtMark) incidentEvidence(ctx, "receiverLink=$ctxLink") else null)
        return Finding("receiver_down", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            (if (notConfigured) "External receiver not configured (no host or port)" else "External receiver disconnected or unreachable (${down.size}x, $state)"),
            evidence,
            "Check the receiver is on and on the same Wi-Fi as the tablet, and the host/port in settings-snapshot.txt; RECEIVER lines in timeline.txt show each attempt.",
            relate(ctx, down.map { it.entry.epochMs }, "the receiver link went down", presentAtMark = downAtMark))
    }

    private fun noNmea(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.RECEIVER_NO_DATA)
        if (list.isEmpty()) return null
        return Finding("tcp_no_nmea", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "Receiver TCP connected but no NMEA data arrived (${list.size}x)", pick(ctx, list),
            "Check the receiver's NMEA output (TCP server on, port, GGA/RMC enabled); nmea-stream-diagnostics.txt shows what did arrive.",
            relate(ctx, list.map { it.entry.epochMs }, "NMEA data stopped"))
    }

    private fun reconnects(ctx: Ctx): Finding? {
        val bursts = ctx.of(SignalKind.RECEIVER_RECONNECTS)
        val downs = ctx.of(SignalKind.RECEIVER_DOWN, SignalKind.RECEIVER_NO_DATA)
        var burst: List<Signal> = emptyList()
        for (i in downs.indices) {
            val window = downs.drop(i).takeWhile { it.entry.epochMs - downs[i].entry.epochMs <= RECONNECT_WINDOW_MS }
            if (window.size > burst.size) burst = window
        }
        val list = bursts.ifEmpty { if (burst.size >= RECONNECT_BURST) burst else emptyList() }
        if (list.isEmpty()) return null
        val count = bursts.lastOrNull()?.entry?.message?.let { Regex("""count=(\d+)""").find(it)?.groupValues?.get(1) } ?: list.size.toString()
        return Finding("repeated_reconnects", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Repeated receiver reconnects ($count within ${RECONNECT_WINDOW_MS / 60_000} min)", pick(ctx, list),
            "An unstable Wi-Fi link to the receiver: check signal strength, distance, and whether the tablet roams to another network (SYSTEM network lines).",
            relate(ctx, list.map { it.entry.epochMs }, "the receiver link kept reconnecting"))
    }

    private fun noFix(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.GNSS_NO_FIX)
        val ctxRtk = ctx.ictx?.rtkStatus?.uppercase(Locale.US)
        val atMark = ctxRtk == "NONE" || ctxRtk == "NO_FIX"
        if (list.isEmpty() && !atMark) return null
        return Finding("nmea_no_fix", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "NMEA arriving but no usable position fix", pick(ctx, list) + listOfNotNull(if (atMark) incidentEvidence(ctx, "rtkStatus=$ctxRtk") else null),
            "Check the antenna's sky view and the receiver's satellite count in gnss-stats.txt / nmea-stream-diagnostics.txt.",
            relate(ctx, list.map { it.entry.epochMs }, "the fix was lost", presentAtMark = atMark))
    }

    private fun staleFix(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.GNSS_STALE)
        val age = ctx.ictx?.fixAgeMs
        val atMark = age != null && age > STALE_FIX_MS
        if (list.isEmpty() && !atMark) return null
        return Finding("gnss_stale", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "GNSS fix stale (no new position" + (if (atMark) " for ${age!! / 1000} s at the marked problem" else "") + ")",
            pick(ctx, list) + listOfNotNull(if (atMark) incidentEvidence(ctx, "fixAgeMs=$age") else null),
            "Positions stopped updating: check the receiver link (RECEIVER lines) and the NMEA rate in nmea-stream-diagnostics.txt.",
            relate(ctx, list.map { it.entry.epochMs }, "the fix went stale", presentAtMark = atMark))
    }

    private fun rtkLost(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.RTK_LOST)
        if (list.isEmpty()) return null
        val to = list.last().entry.message.let { m ->
            Regex("""to=(\S+)""").find(m)?.groupValues?.get(1) ?: Regex("""→ (\S+)""").find(m)?.groupValues?.get(1)
        } ?: "lower"
        val rec = recovered(list, setOf(SignalKind.RTK_LOST), setOf(SignalKind.RTK_FIX), ctx.inFocus)
        val atMark = ctx.lastAtIncident(SignalKind.RTK_LOST, SignalKind.RTK_FIX)?.kind == SignalKind.RTK_LOST
        return Finding("rtk_lost", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "RTK FIX lost (FIX → $to, ${list.size}x" + (if (rec == true) ", FIX regained" else "") + ")", pick(ctx, list),
            "Check the corrections (CORRECTIONS lines, correction age) and the sky view; gnss-stats.txt has the time in each RTK status.",
            relate(ctx, list.map { it.entry.epochMs }, "RTK FIX was lost", presentAtMark = atMark))
    }

    private fun corrections(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.CORRECTIONS_STALE)
        val ageAtMark = ctx.ictx?.correctionAgeS
        val atMark = ageAtMark != null && ageAtMark > STALE_CORRECTIONS_S
        val ageNow = ctx.input.live?.correctionAgeS
        val staleNow = ageNow != null && ageNow > STALE_CORRECTIONS_S && ctx.incidentMs == null
        if (list.isEmpty() && !atMark && !staleNow) return null
        val extra = listOfNotNull(
            if (atMark) incidentEvidence(ctx, String.format(Locale.US, "correctionAgeS=%.1f", ageAtMark)) else null,
            if (staleNow) Evidence(ctx.input.nowMs, "app-state-summary.txt", String.format(Locale.US, "correction age at export %.1f s", ageNow)) else null,
        )
        return Finding("corrections_stale", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "RTK corrections stale or missing", pick(ctx, list) + extra,
            "Check the corrections input on the receiver (NTRIP/LoRa) and the receiver's internet connection; gnss-stats.txt has the longest correction gap.",
            relate(ctx, list.map { it.entry.epochMs }, "corrections went stale", presentAtMark = atMark))
    }

    private fun permission(ctx: Ctx, rule: String, kind: SignalKind, deviceKey: String, atMark: Boolean?, title: String, next: String): Finding? {
        val list = ctx.of(kind)
        val deniedNow = ctx.input.device?.permissions?.get(deviceKey) == false
        val deniedAtMark = atMark == false
        if (list.isEmpty() && !deniedNow && !deniedAtMark) return null
        val evidence = pick(ctx, list) + listOfNotNull(
            if (deniedAtMark) incidentEvidence(ctx, "$deviceKey permission=false") else null,
            if (deniedNow) Evidence(ctx.input.nowMs, "device-health.txt", "$deviceKey permission denied at export") else null,
        )
        return Finding(rule, Finding.Severity.HIGH, Finding.Certainty.OBSERVED, title, evidence, next,
            relate(ctx, list.map { it.entry.epochMs }, "the permission was missing", presentAtMark = deniedAtMark))
    }

    private fun locationPermission(ctx: Ctx) = permission(ctx, "location_permission", SignalKind.LOCATION_PERMISSION, "Fine location",
        ctx.ictx?.fineLocationPermission, "Location permission denied (device GNSS, geospatial AR and mock location cannot work)",
        "Grant precise location: Android Settings → Apps → SurReal AR → Permissions → Location → Allow, Use precise location.")

    private fun cameraPermission(ctx: Ctx) = permission(ctx, "camera_permission", SignalKind.CAMERA_PERMISSION, "Camera",
        ctx.ictx?.cameraPermission, "Camera permission denied (AR cannot start)",
        "Grant the camera: Android Settings → Apps → SurReal AR → Permissions → Camera → Allow.")

    private fun arUnavailable(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.AR_UNAVAILABLE)
        if (list.isEmpty()) return null
        return Finding("ar_unavailable", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "ARCore unavailable or the AR session failed to start/resume (${list.size}x)", pick(ctx, list),
            "Check Google Play Services for AR is installed and up to date and the camera is not used by another app; AR lines in the log give the reason.",
            relate(ctx, list.map { it.entry.epochMs }, "AR was unavailable"))
    }

    private fun arTracking(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.AR_TRACKING_LOST)
        val cam = ctx.ictx?.arCameraTracking?.uppercase(Locale.US)
        val atMark = ctx.ictx?.arSessionActive == true && (cam == "PAUSED" || cam == "STOPPED")
        if (list.isEmpty() && !atMark) return null
        val rec = recovered(list, setOf(SignalKind.AR_TRACKING_LOST), setOf(SignalKind.AR_TRACKING_OK), ctx.inFocus)
        return Finding("ar_tracking_lost", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "AR camera tracking lost (${list.size}x" + (if (rec == true) ", recovered" else "") + ")",
            pick(ctx, list) + listOfNotNull(if (atMark) incidentEvidence(ctx, "arCameraTracking=$cam") else null),
            "Tracking needs light and texture: the failure reason is on the AR tracking lines; ar-session-summary.txt has the session tallies.",
            relate(ctx, list.map { it.entry.epochMs }, "AR tracking was lost", presentAtMark = atMark))
    }

    private fun geospatial(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.GEO_POOR, SignalKind.GEO_NO_NETWORK)
        val earth = ctx.ictx?.arEarthTracking?.uppercase(Locale.US)
        val atMark = ctx.ictx?.arSessionActive == true && (earth == "PAUSED" || earth == "STOPPED")
        if (list.isEmpty() && !atMark) return null
        val noNet = list.any { it.kind == SignalKind.GEO_NO_NETWORK }
        return Finding("geospatial_poor", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Geospatial localization unavailable or poor" + if (noNet) " (no network)" else "",
            pick(ctx, list) + listOfNotNull(if (atMark) incidentEvidence(ctx, "arEarthTracking=$earth") else null),
            "Geospatial needs internet and Street View coverage: point the camera at buildings; AR_QUALITY / Earth tracking lines show the accuracy.",
            relate(ctx, list.map { it.entry.epochMs }, "geospatial localization was poor", presentAtMark = atMark))
    }

    private fun modelFailed(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.MODEL_FAILED)
        if (list.isEmpty()) return null
        return Finding("model_failed", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Model load or validation failed (${list.size}x)", pick(ctx, list),
            "model-integrity.txt lists each model's file state and last AR status; the MODEL lines name the failing file (hashed).",
            relate(ctx, list.map { it.entry.epochMs }, "a model failed to load"))
    }

    private fun modelMissing(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.MODEL_MISSING_FILE)
        val links = ctx.input.info.missingModelLinks ?: 0
        if (list.isEmpty() && links <= 0) return null
        val extra = if (links > 0) listOf(Evidence(ctx.input.nowMs, "coordinates.txt", "$links point(s) link a model whose file is missing")) else emptyList()
        return Finding("model_missing_file", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Points link a model whose file is missing", pick(ctx, list) + extra,
            "Re-import the model or relink the points; coordinates.txt (file=file_missing) and model-integrity.txt (exists=false) list them.",
            relate(ctx, list.map { it.entry.epochMs }, "a linked model file was missing"))
    }

    /** Fields of a coded line (`CODE k=v … (old sentence)`), empty for a legacy line. */
    private fun fields(s: Signal): Map<String, String> =
        app.surrealar.util.diagnostics.DiagEvents.parse(s.entry.message.substringBefore(" (").trimEnd())?.second ?: emptyMap()

    private fun sourceMismatch(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.SOURCE_MISMATCH)
        if (list.isEmpty()) return null
        val f = fields(list.last())
        return Finding("source_mismatch", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Selected GNSS source differs from the active one (selected=${f["selected"] ?: "?"}, active=${f["active"] ?: "?"})",
            pick(ctx, list),
            "settings-snapshot.txt shows the chosen source and receiver; the reason field says why the app fell back (e.g. receiver not configured).",
            relate(ctx, list.map { it.entry.epochMs }, "the app was not using the selected GNSS source"))
    }

    private fun antennaHeight(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.ANTENNA_HEIGHT)
        if (list.isEmpty()) return null
        val reasons = list.mapNotNull { fields(it)["reason"] }.distinct().joinToString()
        return Finding("antenna_height", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Antenna height setting missing or inconsistent at capture start" + if (reasons.isNotEmpty()) " ($reasons)" else "",
            pick(ctx, list),
            "Heights of points captured then may be off by the pole height; check the antenna/pole height in settings-snapshot.txt and captures.txt.",
            relate(ctx, list.map { it.entry.epochMs }, "a capture started with an antenna height problem"))
    }

    private fun arAnchor(ctx: Ctx): Finding? {
        val failed = ctx.of(SignalKind.AR_ANCHOR_FAILED)
        val delayed = ctx.of(SignalKind.AR_ANCHOR_DELAYED)
        if (failed.isEmpty() && delayed.isEmpty()) return null
        val list = (failed + delayed).sortedBy { it.entry.epochMs }
        val reasons = list.mapNotNull { fields(it)["reason"] }.distinct().take(3).joinToString()
        return Finding("ar_anchor", if (failed.isNotEmpty()) Finding.Severity.MEDIUM else Finding.Severity.LOW, Finding.Certainty.OBSERVED,
            (if (failed.isNotEmpty()) "AR anchors failed (${failed.size}x)" else "AR anchors delayed (${delayed.size}x)") +
                if (reasons.isNotEmpty()) " — $reasons" else "",
            pick(ctx, list),
            "Anchors wait for geospatial localization; AR_ANCHOR and AR_GEO_ACCURACY lines with the same ar= id show what they waited for.",
            relate(ctx, list.map { it.entry.epochMs }, "AR anchors " + if (failed.isNotEmpty()) "failed" else "were delayed"))
    }

    private fun modelHidden(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.AR_MODEL_HIDDEN)
        if (list.isEmpty()) return null
        val reasons = list.map { fields(it)["reason"] ?: "DISTANCE" }.distinct().joinToString()
        return Finding("ar_model_hidden", Finding.Severity.LOW, Finding.Certainty.OBSERVED,
            "Models were not shown in AR ($reasons)", pick(ctx, list),
            "DISTANCE/VISIBILITY come from AR display settings (settings-snapshot.txt); OFFSCREEN means the models were outside the camera view; TRACKING means tracking was lost.",
            relate(ctx, list.map { it.entry.epochMs }, "models were hidden"))
    }

    private fun alignmentJump(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.AR_ALIGNMENT_JUMP)
        if (list.isEmpty()) return null
        val maxM = list.mapNotNull { fields(it)["deltaM"]?.toDoubleOrNull() }.maxOrNull()
        return Finding("ar_alignment_jump", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "AR/GNSS alignment jumped (${list.size}x" + (maxM?.let { String.format(Locale.US, ", up to %.1f m", it) } ?: "") + ")",
            pick(ctx, list),
            "A jump moves placed models; compare with RTK_CHANGE and AR_GEO_ACCURACY lines just before it (ar-track.csv in debug builds).",
            relate(ctx, list.map { it.entry.epochMs }, "the AR/GNSS alignment jumped"))
    }

    private fun noAltitude(ctx: Ctx): Finding? {
        val list = ctx.of(SignalKind.AR_NO_ALTITUDE)
        if (list.isEmpty()) return null
        return Finding("ar_no_altitude", Finding.Severity.LOW, Finding.Certainty.OBSERVED,
            "Points without a usable stored altitude were placed on the terrain (${list.size}x)", pick(ctx, list),
            "These models sit on the terrain surface, not at the surveyed height; coordinates.txt shows which points lack an altitude.",
            relate(ctx, list.map { it.entry.epochMs }, "a point had no usable altitude"))
    }

    private fun database(ctx: Ctx): List<Finding> {
        val out = mutableListOf<Finding>()
        val integrity = ctx.input.data?.integrity?.trim()
        when {
            integrity == null || integrity.equals("ok", ignoreCase = true) -> Unit
            integrity.startsWith("failed", ignoreCase = true) -> out += Finding("db_integrity", Finding.Severity.MEDIUM, Finding.Certainty.UNKNOWN,
                "Database integrity check could not run", listOf(Evidence(ctx.input.nowMs, "data-health.txt", "Integrity check: ${clip(integrity, 80)}")),
                "Unknown whether the database is intact; data-health.txt has the row counts and last migration.")
            else -> out += Finding("db_integrity", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
                "Database integrity check failed", listOf(Evidence(ctx.input.nowMs, "data-health.txt", "Integrity check: ${clip(integrity, 80)}")),
                "The database file is damaged: export a backup now if possible; data-health.txt has the check output and row counts.")
        }
        val reset = ctx.input.signals.filter { it.kind == SignalKind.DB_RESET }
        val failed = ctx.of(SignalKind.DB_FAILED)
        if (reset.isNotEmpty()) out += Finding("db_reset", Finding.Severity.HIGH, Finding.Certainty.OBSERVED,
            "Database was reset by a destructive migration (saved data cleared)", reset.takeLast(MAX_EVIDENCE).map(::ev),
            "Saved points were cleared on upgrade: restore from a backup; data-health.txt has the version and migration.",
            relate(ctx, reset.map { it.entry.epochMs }, "the database was reset"))
        else if (failed.isNotEmpty()) out += Finding("db_error", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Database errors (${failed.size}x)", pick(ctx, failed), "See the DATA error lines in app-errors-*.txt.",
            relate(ctx, failed.map { it.entry.epochMs }, "a database error occurred"))
        return out
    }

    private fun storage(ctx: Ctx): Finding? {
        val d = ctx.input.device
        val free = d?.storageFreeBytes ?: ctx.input.live?.freeStorageBytes
        val total = d?.storageTotalBytes
        val atMark = ctx.ictx?.freeStorageBytes
        fun low(b: Long?) = b != null && (b < LOW_STORAGE_BYTES || (total != null && total > 0 && b * 100 / total < LOW_STORAGE_PERCENT))
        val list = ctx.of(SignalKind.STORAGE_LOW)
        if (!low(free) && !low(atMark) && list.isEmpty()) return null
        val evidence = pick(ctx, list) + listOfNotNull(
            if (low(atMark)) incidentEvidence(ctx, "freeStorageMb=${atMark!! / (1024 * 1024)}") else null,
            if (low(free)) Evidence(ctx.input.nowMs, "device-health.txt", "free storage at export ${free!! / (1024 * 1024)} MB" +
                (total?.let { " of ${it / (1024 * 1024)} MB" } ?: "")) else null,
        )
        val critical = (free != null && free < 100L * 1024 * 1024)
        return Finding("low_storage", if (critical) Finding.Severity.HIGH else Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
            "Low device storage (below ${LOW_STORAGE_BYTES / (1024 * 1024)} MB or $LOW_STORAGE_PERCENT %)", evidence,
            "Free some space: logs, captures, models and the database need room to write.",
            relate(ctx, list.map { it.entry.epochMs }, "storage was low", presentAtMark = low(atMark)))
    }

    private fun network(ctx: Ctx): Finding? {
        val net = ctx.input.device?.network
        val lost = ctx.of(SignalKind.NETWORK_LOST, SignalKind.GEO_NO_NETWORK)
        val needsNet = ctx.of(SignalKind.GEO_POOR, SignalKind.CORRECTIONS_STALE).isNotEmpty()
        return when {
            net == "none" || lost.isNotEmpty() -> Finding("network", Finding.Severity.MEDIUM, Finding.Certainty.OBSERVED,
                if (net == "none") "No network connection at export (geospatial AR, maps and internet corrections need one)"
                else "Network lost or without internet (${lost.size}x)",
                pick(ctx, lost) + listOfNotNull(if (net == "none") Evidence(ctx.input.nowMs, "device-health.txt", "network: none") else null),
                "Check Wi-Fi / mobile data; on the receiver's own Wi-Fi the tablet has no internet. The export itself works offline.",
                relate(ctx, lost.map { it.entry.epochMs }, "the network was lost"))
            net != null && net.contains("validated=false") -> Finding("network", Finding.Severity.LOW, Finding.Certainty.POSSIBLE_CAUSE,
                "Network without internet at export (possibly the receiver's Wi-Fi)",
                listOf(Evidence(ctx.input.nowMs, "device-health.txt", "network: $net")),
                "Geospatial AR and map tiles need internet; connect a network with internet or use mobile data alongside the receiver Wi-Fi.")
            net == null && needsNet -> Finding("network", Finding.Severity.LOW, Finding.Certainty.UNKNOWN,
                "Network state unknown (geospatial or corrections problems were seen)", emptyList(),
                "Check whether the tablet had internet at the time (SYSTEM network lines in app-log-*.txt).")
            else -> null
        }
    }

    // ── Subsystem status ──

    /** One line per subsystem for the summary, from the live state, the incident context and the focus window. */
    fun subsystemStatus(input: FindingsInput): List<Pair<String, String>> {
        val ctx = Ctx(input)
        val live = input.live
        val ic = input.incident?.context
        fun lastOf(vararg k: SignalKind) = ctx.inFocus.lastOrNull { it.kind in k }
        fun count(vararg k: SignalKind) = ctx.inFocus.count { it.kind in k }
        fun at(s: Signal?) = s?.let { " (last change ${LogLineFormat.timestamp(it.entry.epochMs)}: ${it.kind.name.lowercase(Locale.US)})" } ?: ""
        fun mark(v: String?) = v?.let { "; at the marked problem: $it" } ?: ""
        val gnss = "source=${live?.source ?: "unknown"} rtk=${live?.rtk ?: "no fix"} at export" +
            mark(ic?.let { "rtk=${it.rtkStatus ?: "unknown"} fixAgeMs=${it.fixAgeMs ?: "unknown"} hAccM=${it.hAccM?.let { a -> String.format(Locale.US, "%.3f", a) } ?: "unknown"}" }) +
            at(lastOf(SignalKind.GNSS_NO_FIX, SignalKind.GNSS_STALE, SignalKind.GNSS_LIVE, SignalKind.RTK_LOST, SignalKind.RTK_FIX))
        val downs = count(SignalKind.RECEIVER_DOWN, SignalKind.RECEIVER_NO_DATA)
        val receiver = "link=${live?.receiverLink ?: "unknown"} at export; $downs drop(s) in the focus window" +
            mark(ic?.receiverLink) + at(lastOf(SignalKind.RECEIVER_DOWN, SignalKind.RECEIVER_UP, SignalKind.RECEIVER_NO_DATA))
        val corr = "age=${live?.correctionAgeS?.let { String.format(Locale.US, "%.1f s", it) } ?: "none"}" +
            (live?.corrections?.let { " ($it)" } ?: "") + " at export" +
            mark(ic?.correctionAgeS?.let { String.format(Locale.US, "age %.1f s", it) }) +
            at(lastOf(SignalKind.CORRECTIONS_STALE, SignalKind.CORRECTIONS_OK))
        val ar = when {
            ic?.arSessionActive == true -> "AR open at the marked problem: camera=${ic.arCameraTracking ?: "unknown"} earth=${ic.arEarthTracking ?: "unknown"} " +
                "modelsInScene=${ic.arModelsInScene ?: "unknown"} loading=${ic.arModelsLoading ?: "unknown"}"
            count(SignalKind.AR_TRACKING_LOST, SignalKind.AR_TRACKING_OK, SignalKind.GEO_POOR, SignalKind.GEO_OK, SignalKind.AR_UNAVAILABLE) > 0 ->
                "AR used in the focus window"
            else -> "no AR activity in the focus window"
        } + at(lastOf(SignalKind.AR_TRACKING_LOST, SignalKind.AR_TRACKING_OK, SignalKind.GEO_POOR, SignalKind.GEO_OK, SignalKind.AR_UNAVAILABLE))
        val models = "${count(SignalKind.MODEL_FAILED)} load failure(s), ${count(SignalKind.MODEL_OK)} load(s) ok in the focus window" +
            (input.info.missingModelLinks?.let { "; $it point(s) link a missing model file" } ?: "")
        val free = input.device?.storageFreeBytes ?: live?.freeStorageBytes
        val db = "integrity=${input.data?.integrity?.let { clip(it, 40) } ?: "unknown"} version=${input.data?.version ?: "unknown"}; free storage " +
            (free?.let { mb(it) } ?: "unknown")
        val perms = input.device?.permissions
        fun p(k: String) = when (perms?.get(k)) { true -> "granted"; false -> "DENIED"; null -> "unknown" }
        val permissions = "camera=${p("Camera")} fineLocation=${p("Fine location")}" +
            (ic?.let { c -> if (c.cameraPermission == false || c.fineLocationPermission == false) "; denied at the marked problem" else "" } ?: "")
        return listOf(
            "GNSS" to gnss,
            "Receiver connection" to receiver,
            "RTK corrections" to corr,
            "AR tracking" to ar,
            "Models" to models,
            "Database & storage" to db,
            "Permissions" to permissions,
            "Network" to (input.device?.network ?: "unknown"),
        )
    }

    private fun mb(b: Long) = if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024)) else "${b / (1024 * 1024)} MB"
}
