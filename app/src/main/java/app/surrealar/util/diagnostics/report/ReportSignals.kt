package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.DiagEvents

/** What a log line tells the findings rules. */
enum class SignalKind {
    RECEIVER_DOWN, RECEIVER_UP, RECEIVER_NO_DATA, RECEIVER_RECONNECTS, RECEIVER_NOT_CONFIGURED,
    GNSS_NO_FIX, GNSS_STALE, GNSS_LIVE,
    RTK_LOST, RTK_FIX,
    CORRECTIONS_STALE, CORRECTIONS_OK,
    CAMERA_PERMISSION, LOCATION_PERMISSION,
    AR_UNAVAILABLE, AR_TRACKING_LOST, AR_TRACKING_OK, GEO_POOR, GEO_OK, GEO_NO_NETWORK,
    MODEL_FAILED, MODEL_OK, MODEL_MISSING_FILE,
    DB_RESET, DB_FAILED,
    STORAGE_LOW, NETWORK_LOST, NETWORK_OK,
    MAIN_THREAD_BLOCKED,
}

/** A log entry the findings rules recognised. */
data class Signal(val kind: SignalKind, val entry: LogEntry)

/**
 * Classifies log lines for [ReportFindings]. Structured [DiagEvents] codes come first; the legacy
 * sentences that builds before those codes (and the GeoKit link code) write are matched too, so a
 * report from an older build still yields findings (tags are not checked: some lines moved, e.g. model
 * preload lines from AR to MODEL). Pure; one line → at most one kind.
 */
object ReportSignals {

    private val RTK_LEGACY = Regex("""^RTK status (\S+) → (\S+)""")
    private val CAMERA_TRACKING = Regex("""^Camera tracking changed trackingState=(\S+)""")
    private val EARTH_TRACKING = Regex("""^Earth tracking changed trackingState=(\S+)""")
    private val AR_QUALITY = Regex("""^AR_QUALITY \S+ → (\S+)""")
    private val NOT_TRACKING = setOf("PAUSED", "STOPPED")

    private val RECEIVER_DOWN_LEGACY = listOf(
        "External receiver link down", "External receiver still unreachable", "External receiver connect failed",
        "External receiver TCP connect failed", "External receiver connection ended: reconnecting",
        "External receiver TCP connection closed by remote", "Link down", "TCP connect failed",
    )
    private val RECEIVER_UP_LEGACY = listOf("External receiver link up", "External receiver connected", "Link up")
    private val RECEIVER_NO_DATA_LEGACY = listOf(
        "External receiver link stalled", "External receiver TCP link dead", "External receiver connect timed out: no NMEA",
        "External receiver connection ended before any NMEA", "TCP: no data",
    )
    private val CORRECTIONS_STALE_LEGACY = listOf("Corrections stream lost", "Corrections age over threshold", "Correction age over")
    private val CORRECTIONS_OK_LEGACY = listOf("Corrections age recovered", "Corrections stream connected")
    private val AR_UNAVAILABLE_LEGACY = listOf(
        "ARCore unavailable", "ARCore install declined", "AR session create failed", "AR session resume failed",
        "AR session configure failed", "Camera unavailable: AR",
    )
    private val GEO_POOR_LEGACY = listOf("Geospatial unavailable", "Geospatial mode not supported", "Geospatial mode enable failed")
    private val MODEL_FAILED_LEGACY = listOf(
        "Model preload failed", "Model GLB validation failed", "Model import failed", "Model import rejected",
        "Model viewer load failed", "Linked model load failed", "Model stuck loading", "AR renderer init failed: 3D models disabled",
        "Model file copy failed", "Model reprojection failed",
    )
    private val MODEL_MISSING_LEGACY = listOf("Model thumbnail failed: model file not found", "Model viewer setup failed: model file missing",
        "Model skipped: file missing")

    fun classify(e: LogEntry): SignalKind? {
        val m = e.message
        // Coded lines are `CODE key=value … (old sentence)`: parse the codes without the sentence, so its
        // words can't add or override fields. Legacy lines start with the sentence itself.
        DiagEvents.parse(m.substringBefore(" (").trimEnd())?.let { (code, f) -> return structured(code, f) }
        fun starts(list: List<String>) = list.any { m.startsWith(it) }
        val warnOrError = e.level == 'W' || e.level == 'E'
        RTK_LEGACY.find(m)?.let { r ->
            val (from, to) = r.destructured
            return when {
                from == "FIX" && to != "FIX" -> SignalKind.RTK_LOST
                to == "FIX" -> SignalKind.RTK_FIX
                to == "NONE" -> SignalKind.GNSS_NO_FIX
                else -> null
            }
        }
        CAMERA_TRACKING.find(m)?.let { return if (it.groupValues[1] in NOT_TRACKING) SignalKind.AR_TRACKING_LOST else SignalKind.AR_TRACKING_OK }
        EARTH_TRACKING.find(m)?.let { return if (it.groupValues[1] in NOT_TRACKING) SignalKind.GEO_POOR else SignalKind.GEO_OK }
        AR_QUALITY.find(m)?.let {
            return when (it.groupValues[1]) { "LOW" -> SignalKind.GEO_POOR; "GOOD", "FAIR" -> SignalKind.GEO_OK; else -> null }
        }
        return when {
            starts(RECEIVER_NO_DATA_LEGACY) -> SignalKind.RECEIVER_NO_DATA
            warnOrError && starts(RECEIVER_DOWN_LEGACY) -> SignalKind.RECEIVER_DOWN
            starts(RECEIVER_UP_LEGACY) -> SignalKind.RECEIVER_UP
            m.startsWith("External receiver connect skipped: no host or port") -> SignalKind.RECEIVER_NOT_CONFIGURED
            starts(CORRECTIONS_STALE_LEGACY) -> SignalKind.CORRECTIONS_STALE
            starts(CORRECTIONS_OK_LEGACY) -> SignalKind.CORRECTIONS_OK
            m.startsWith("AR session start blocked: camera permission") -> SignalKind.CAMERA_PERMISSION
            m.startsWith("AR session start blocked: location permission") -> SignalKind.LOCATION_PERMISSION
            starts(AR_UNAVAILABLE_LEGACY) -> SignalKind.AR_UNAVAILABLE
            m.startsWith("Geospatial cannot localize: no network") -> SignalKind.GEO_NO_NETWORK
            starts(GEO_POOR_LEGACY) -> SignalKind.GEO_POOR
            starts(MODEL_MISSING_LEGACY) -> SignalKind.MODEL_MISSING_FILE
            starts(MODEL_FAILED_LEGACY) -> SignalKind.MODEL_FAILED
            m.startsWith("Model preload succeeded") -> SignalKind.MODEL_OK
            m.startsWith("Database reset") -> SignalKind.DB_RESET
            e.level == 'E' && m.startsWith("Database") -> SignalKind.DB_FAILED
            m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) || m.startsWith("Storage low") -> SignalKind.STORAGE_LOW
            m.startsWith("Network lost") || m.startsWith("Network connected without internet") -> SignalKind.NETWORK_LOST
            m.startsWith("Network connected") && m.contains("validated=true") -> SignalKind.NETWORK_OK
            m.startsWith("Main thread blocked") || m.startsWith("Main thread still blocked") -> SignalKind.MAIN_THREAD_BLOCKED
            else -> null
        }
    }

    private fun structured(code: String, f: Map<String, String>): SignalKind? = when (code) {
        DiagEvents.RECEIVER_LINK -> when (f["state"]) {
            "CONNECTED" -> SignalKind.RECEIVER_UP
            "NO_DATA" -> SignalKind.RECEIVER_NO_DATA
            "DISCONNECTED", "UNREACHABLE" -> SignalKind.RECEIVER_DOWN
            else -> null
        }
        DiagEvents.RECEIVER_RECONNECTS -> SignalKind.RECEIVER_RECONNECTS
        DiagEvents.GNSS_FEED -> when (f["state"]) {
            "NO_FIX" -> SignalKind.GNSS_NO_FIX
            "STALE" -> SignalKind.GNSS_STALE
            "LIVE" -> SignalKind.GNSS_LIVE
            else -> null
        }
        DiagEvents.RTK_CHANGE -> when {
            f["from"] == "FIX" && f["to"] != "FIX" -> SignalKind.RTK_LOST
            f["to"] == "FIX" -> SignalKind.RTK_FIX
            else -> null
        }
        DiagEvents.CORRECTIONS_STATE -> when (f["state"]) {
            "STALE", "NONE" -> SignalKind.CORRECTIONS_STALE
            "OK" -> SignalKind.CORRECTIONS_OK
            else -> null
        }
        DiagEvents.AR_UNAVAILABLE -> SignalKind.AR_UNAVAILABLE
        DiagEvents.AR_STATE -> when {
            f["camera"] in NOT_TRACKING -> SignalKind.AR_TRACKING_LOST
            f["earth"] in NOT_TRACKING -> SignalKind.GEO_POOR
            f["camera"] == "TRACKING" -> SignalKind.AR_TRACKING_OK
            else -> null
        }
        DiagEvents.AR_GEO_ACCURACY -> when (f["level"]) {
            "POOR" -> SignalKind.GEO_POOR
            "GOOD", "FAIR" -> SignalKind.GEO_OK
            else -> null
        }
        DiagEvents.MODEL_LOAD -> when (f["result"]) {
            "FAILED" -> SignalKind.MODEL_FAILED
            "OK" -> SignalKind.MODEL_OK
            else -> null
        }
        DiagEvents.MODEL_MISSING_FILE -> SignalKind.MODEL_MISSING_FILE
        else -> null
    }
}
