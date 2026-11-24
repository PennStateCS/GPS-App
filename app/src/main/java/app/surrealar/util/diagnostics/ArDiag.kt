package app.surrealar.util.diagnostics

import kotlin.math.hypot

/**
 * Pure helpers behind the AR [DiagEvents] lines (AR_UNAVAILABLE, AR_GEO_ACCURACY, AR_ANCHOR,
 * AR_MODEL_HIDDEN, AR_ALIGNMENT_JUMP). No ARCore types: callers pass enum names, so this is
 * unit-testable and costs a few comparisons when called from the render loop.
 */
object ArDiag {

    /** AR_UNAVAILABLE reason for an `ArCoreApk.Availability` name. */
    fun availabilityReason(availability: String): String = when (availability) {
        "UNSUPPORTED_DEVICE_NOT_CAPABLE" -> "UNSUPPORTED"
        "SUPPORTED_NOT_INSTALLED" -> "NOT_INSTALLED"
        "SUPPORTED_APK_TOO_OLD" -> "UPDATE_REQUIRED"
        "UNKNOWN_ERROR", "UNKNOWN_TIMED_OUT", "UNKNOWN_CHECKING" -> "UNKNOWN"
        else -> availability
    }

    /** AR_UNAVAILABLE reason for an exception thrown while creating/resuming the ARCore session. */
    fun sessionErrorReason(t: Throwable?): String = when (t?.javaClass?.simpleName) {
        "CameraNotAvailableException" -> "CAMERA_UNAVAILABLE"
        "UnavailableDeviceNotCompatibleException" -> "UNSUPPORTED"
        "UnavailableArcoreNotInstalledException" -> "NOT_INSTALLED"
        "UnavailableApkTooOldException" -> "UPDATE_REQUIRED"
        "UnavailableSdkTooOldException" -> "SDK_TOO_OLD"
        "UnavailableUserDeclinedInstallationException" -> "INSTALL_DECLINED"
        "FatalException" -> "FATAL"
        null -> "unknown"
        else -> "ERROR"
    }

    /** AR_GEO_ACCURACY level for an ArViewQuality.Level name; null when there is no accuracy band (not tracking / unknown). */
    fun geoLevel(arViewQualityLevel: String?): String? = when (arViewQualityLevel) {
        "GOOD" -> "GOOD"
        "FAIR" -> "FAIR"
        "LOW" -> "POOR"
        else -> null
    }

    /** AR_ANCHOR kind for one anchor build: WGS84 (stored heights), TERRAIN, or MIXED. */
    fun anchorKind(stored: Int, terrain: Int): String = when {
        terrain == 0 -> "WGS84"
        stored == 0 -> "TERRAIN"
        else -> "MIXED"
    }

    /** "Anchors waiting reason=…" → AR_ANCHOR result=DELAYED reason. */
    fun waitReason(reason: String): String = reason.uppercase(java.util.Locale.US)

    /** Whole seconds since [sinceMs] (wall clock), or null when it is unknown. */
    fun afterS(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Long? =
        if (sinceMs <= 0L || nowMs < sinceMs) null else (nowMs - sinceMs) / 1000L
}

/**
 * Detects an AR/GNSS alignment jump from the sampled AR_VS_GNSS difference (every few seconds, not
 * per frame): the AR-minus-GNSS offset moved by at least [thresholdM] between two samples. The
 * offset is compared as a vector (east/north from distance + bearing), so a constant offset seen
 * from a different bearing still counts as a move. Not thread-safe (one caller: the GL thread).
 */
class AlignmentJumpDetector(private val thresholdM: Double = DEFAULT_THRESHOLD_M) {
    private var lastE = Double.NaN
    private var lastN = Double.NaN
    private var lastV: Double? = null

    /** Feeds one sample; returns the jump (horizontal metres, vertical metres or null) when it is over the threshold. */
    fun update(horizontalM: Double, bearingDeg: Double, verticalM: Double?): Jump? {
        if (!horizontalM.isFinite() || !bearingDeg.isFinite()) return null
        val rad = Math.toRadians(bearingDeg)
        val e = horizontalM * kotlin.math.sin(rad)
        val n = horizontalM * kotlin.math.cos(rad)
        val prevV = lastV
        val jump = if (lastE.isNaN()) null else {
            val d = hypot(e - lastE, n - lastN)
            val dv = if (verticalM != null && prevV != null) verticalM - prevV else null
            if (d >= thresholdM) Jump(d, dv) else null
        }
        lastE = e; lastN = n; lastV = verticalM?.takeIf { it.isFinite() }
        return jump
    }

    fun reset() { lastE = Double.NaN; lastN = Double.NaN; lastV = null }

    data class Jump(val deltaM: Double, val deltaVertM: Double?)

    companion object {
        /** Big enough to ignore normal sample noise at RTK + good VPS, small enough to catch a re-localisation. */
        const val DEFAULT_THRESHOLD_M = 2.0
    }
}

/**
 * AR_MODEL_HIDDEN transitions for one reason (VISIBILITY, OFFSCREEN, TRACKING): the count of models
 * hidden for that reason is fed every frame and a line comes back only when a new count has held for
 * [holdMs]. The baseline is 0, so an AR screen where nothing is hidden logs nothing. A drop back to 0
 * returns a plain "no longer hidden" sentence (no code). Not thread-safe (one caller: the GL thread).
 */
class HiddenModelGate(private val reason: String, private val holdMs: Long = 3_000L) {
    private var logged = 0
    private var pending = -1
    private var pendingSinceMs = 0L

    fun update(count: Int, nowMs: Long, ar: String?): Pair<Char, String>? {
        if (count == logged) { pending = -1; return null }
        if (count != pending) { pending = count; pendingSinceMs = nowMs; return null }
        if (nowMs - pendingSinceMs < holdMs) return null
        logged = count; pending = -1
        return if (count > 0) 'I' to DiagLine.of(DiagEvents.AR_MODEL_HIDDEN, "reason" to reason, "count" to count, DiagEvents.K_AR to ar)
        else 'I' to "AR models no longer hidden reason=$reason" + (ar?.let { " ar=$it" } ?: "")
    }

    fun reset() { logged = 0; pending = -1 }
}
