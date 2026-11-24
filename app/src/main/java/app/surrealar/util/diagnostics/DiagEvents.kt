package app.surrealar.util.diagnostics

/**
 * Structured event codes for state CHANGES that the diagnostic report reasons about (summary findings,
 * incident timelines). A line is `<CODE> key=value key=value …` as the message of an ordinary
 * [app.surrealar.util.DiagnosticsLogger] line under the matching [DiagTags] tag, e.g.
 *
 *   W RECEIVER [main] RECEIVER_LINK state=NO_DATA conn=c7 afterS=10
 *   W GNSS     [main] RTK_CHANGE from=FIX to=FLOAT conn=c7
 *   W AR       [main] AR_STATE camera=PAUSED earth=STOPPED reason=INSUFFICIENT_FEATURES ar=a3
 *
 * Rules:
 *  - Log on a transition only — never per frame or per fix. A value that flickers should be debounced
 *    (see DiagnosticsLogger.logOnChange with stableMs).
 *  - Values are short enums, counts, ages and accuracies. Never coordinates, names, hosts with
 *    credentials, file names (use LogPrivacy) or free text.
 *  - Correlation keys (reuse the existing id of the operation; don't mint one per line):
 *      [K_CONN] receiver connection attempt, [K_CAPTURE] capture session, [K_AR] AR session,
 *      [K_OP] model import/load or backup/import operation, `session=` is the app session.
 *  - Older reports won't have these codes: report rules must also accept the legacy messages.
 */
object DiagEvents {
    // ── GNSS / receiver (tags RECEIVER, GNSS, CORRECTIONS) ──
    /** state=CONNECTING|CONNECTED|NO_DATA|DISCONNECTED|UNREACHABLE [conn=] [afterS=] [reason=] */
    const val RECEIVER_LINK = "RECEIVER_LINK"
    /** count=<n> windowS=<s> — repeated reconnects within a window (logged once per burst). */
    const val RECEIVER_RECONNECTS = "RECEIVER_RECONNECTS"
    /** state=LIVE|NO_FIX|STALE [ageS=] — the GNSS feed as the app sees it (NMEA arriving but no usable fix = NO_FIX). */
    const val GNSS_FEED = "GNSS_FEED"
    /** from=<rtk> to=<rtk> (FIX|FLOAT|DGPS|SINGLE|NONE) [hAccM=] */
    const val RTK_CHANGE = "RTK_CHANGE"
    /** state=OK|STALE|NONE [ageS=] */
    const val CORRECTIONS_STATE = "CORRECTIONS_STATE"
    /** selected=<profile> active=<source> — the chosen receiver differs from the active source. */
    const val SOURCE_MISMATCH = "SOURCE_MISMATCH"
    /** reason=MISSING|INCONSISTENT — antenna height setting problem detected at capture/start. */
    const val ANTENNA_HEIGHT = "ANTENNA_HEIGHT"

    // ── AR (tag AR) ──
    /** reason=UNSUPPORTED|NOT_INSTALLED|UPDATE_REQUIRED|CAMERA_UNAVAILABLE|… */
    const val AR_UNAVAILABLE = "AR_UNAVAILABLE"
    /** camera=TRACKING|PAUSED|STOPPED earth=TRACKING|PAUSED|STOPPED [reason=] [ar=] */
    const val AR_STATE = "AR_STATE"
    /** level=GOOD|FAIR|POOR [hAccM=] [yawDeg=] — geospatial accuracy band changed. */
    const val AR_GEO_ACCURACY = "AR_GEO_ACCURACY"
    /** result=OK|FAILED|DELAYED [kind=TERRAIN|WGS84] [afterS=] [reason=] */
    const val AR_ANCHOR = "AR_ANCHOR"
    /** reason=DISTANCE|VISIBILITY|OFFSCREEN|NO_ALTITUDE|TRACKING [ref=] — a model is not shown. */
    const val AR_MODEL_HIDDEN = "AR_MODEL_HIDDEN"
    /** deltaM=<m> [deltaYawDeg=] — AR/GNSS alignment jumped. */
    const val AR_ALIGNMENT_JUMP = "AR_ALIGNMENT_JUMP"

    // ── Models (tag MODEL) ──
    /** result=OK|FAILED [reason=] [op=] [ref=] */
    const val MODEL_LOAD = "MODEL_LOAD"
    /** [ref=] — a point links a model whose file is missing. */
    const val MODEL_MISSING_FILE = "MODEL_MISSING_FILE"

    // ── Correlation keys ──
    const val K_CONN = "conn"
    const val K_CAPTURE = "capture"
    const val K_AR = "ar"
    const val K_OP = "op"

    val ALL: Set<String> = setOf(
        RECEIVER_LINK, RECEIVER_RECONNECTS, GNSS_FEED, RTK_CHANGE, CORRECTIONS_STATE, SOURCE_MISMATCH, ANTENNA_HEIGHT,
        AR_UNAVAILABLE, AR_STATE, AR_GEO_ACCURACY, AR_ANCHOR, AR_MODEL_HIDDEN, AR_ALIGNMENT_JUMP,
        MODEL_LOAD, MODEL_MISSING_FILE,
    )

    /** Parses `CODE key=value …` (values without spaces). Returns null when [message] doesn't start with a known code. */
    fun parse(message: String): Pair<String, Map<String, String>>? {
        val code = message.substringBefore(' ')
        if (code !in ALL) return null
        val fields = message.substringAfter(' ', "").split(' ')
            .mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }
            .toMap()
        return code to fields
    }
}
