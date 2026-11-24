package app.surrealar.util.diagnostics

import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.io.File
import java.util.Locale

/**
 * CAPTURE diagnostics for one averaged capture, observed from outside the session (read-only):
 * start policy, pauses and resumes, a rejected-fix summary by reason, and the outcome. Fix reasons
 * are classified with the policy's public gates (the session keeps its own count); no coordinates.
 */
class CaptureDiagnostics(
    private val policy: AveragingPolicy,
    private val mode: String,
    /** `capture=` on every line of this capture (start, pause/resume, summary, outcome, save). */
    val id: String = DiagIds.next(DiagIds.CAPTURE),
) {

    private val rejected = sortedMapOf<String, Int>()
    var received = 0
        private set
    private var pausedReason: String? = null

    fun startLine(): String =
        "Capture started capture=$id mode=$mode minDurationS=${policy.minDurationSec} maxDurationS=${policy.maxDurationSec} minFixes=${policy.minSamples}" +
            " requiredRtk=${policy.requiredMinStatus} maxCorrAgeS=${policy.maxDiffAgeSec}" +
            (policy.horizontalPrecisionTargetM?.let { " precisionTargetM=$it" } ?: "")

    /** Why the policy would refuse [fix], or null if it passes these gates. */
    fun reasonFor(fix: Fix): String? = when {
        !fix.latDeg.isFinite() || !fix.lonDeg.isFinite() -> "no position"
        !fix.rtkStatus.meetsOrExceeds(policy.requiredMinStatus) -> "rtk below ${policy.requiredMinStatus}"
        policy.requiresFreshCorrections && (fix.diffAgeS == null || fix.diffAgeS!! > policy.maxDiffAgeSec) -> "corrections too old"
        else -> null
    }

    fun onFix(fix: Fix) {
        received++
        reasonFor(fix)?.let { rejected[it] = (rejected[it] ?: 0) + 1 }
    }

    /** Pause/resume lines (only on change). */
    fun onState(state: ObservationSession.State, log: (Char, String) -> Unit = ::write) {
        val reason = (state as? ObservationSession.State.Paused)?.reason
        when {
            reason != null && reason != pausedReason -> log('W', "Capture paused: $reason capture=$id")
            reason == null && pausedReason != null && state is ObservationSession.State.Capturing -> log('I', "Capture resumed capture=$id")
        }
        if (reason != null || state is ObservationSession.State.Capturing) pausedReason = reason
    }

    /**
     * ANTENNA_HEIGHT line for the antenna settings this capture started with, or null when they look
     * right. MISSING: the settings couldn't be read, or an external pole height is 0 / not a number.
     * INCONSISTENT: a pole height still stored under its old meaning, a height outside 0–[MAX_HEIGHT_M],
     * or a phase-centre override outside 0–max. [external] = an external-receiver capture.
     */
    fun antennaHeightLine(settings: app.surrealar.gnss.settings.GnssReceiverSettings?, external: Boolean): String? {
        val (reason, detail) = when {
            settings == null -> "MISSING" to "settingsUnreadable"
            external && (!settings.poleHeightM.isFinite() || settings.poleHeightM <= 0.0) -> "MISSING" to "poleHeightZero"
            external && settings.poleHeightNeedsCheck -> "INCONSISTENT" to "poleHeightOldMeaning"
            external && settings.poleHeightM > MAX_HEIGHT_M -> "INCONSISTENT" to "poleHeightRange"
            external && settings.phaseCenterOffsetOverrideM?.let {
                !it.isFinite() || it < 0.0 || it > app.surrealar.settings.model.AntennaSetup.MAX_PHASE_CENTER_OFFSET_M
            } == true -> "INCONSISTENT" to "phaseCenterOffsetRange"
            !external && (!settings.internalAntennaHeightM.isFinite() || settings.internalAntennaHeightM < 0.0 ||
                settings.internalAntennaHeightM > MAX_HEIGHT_M) -> "INCONSISTENT" to "internalHeightRange"
            else -> return null
        }
        return DiagLine.of(DiagEvents.ANTENNA_HEIGHT, "reason" to reason, "detail" to detail, "mode" to mode,
            DiagEvents.K_CAPTURE to id, text = "Capture antenna height setting looks wrong")
    }

    fun rejectedSummary(accepted: Int?): String =
        "Capture fix summary capture=$id received=$received accepted=${accepted ?: "unknown"} rejected=${rejected.values.sum()}" +
            if (rejected.isEmpty()) "" else ": " + rejected.entries.joinToString(", ") { "${it.key}=${it.value}" }

    /**
     * One line for the log and captures.log. [pointId] only when the point was saved: the
     * user-entered point name never goes in (the report is shared; names can be personal).
     */
    fun outcomeLine(outcome: String, samples: Int?, durationS: Long?, rtk: RtkStatus?, hAccM: Double?, vAccM: Double?, pointId: Any? = null): String =
        "Capture $outcome" + (pointId?.let { " id=$it" } ?: "") +
            " capture=$id mode=$mode samples=${samples ?: 0} duration=${durationS ?: 0}s rtk=${rtk?.name ?: "none"}" +
            " hAcc=${GnssFeedTracker.hAccBucket(hAccM)} vAcc=${GnssFeedTracker.hAccBucket(vAccM)}" +
            " rejected=${rejected.values.sum()}/$received"

    private fun write(level: Char, msg: String) = GnssFeedLogger.log(level, DiagTags.CAPTURE, msg)

    companion object {
        /** Taller antenna/pole heights are almost certainly typos (cm entered as m). */
        const val MAX_HEIGHT_M = 10.0
    }
}

/** diagnostics/captures.log: one line per capture outcome (no coordinates), rotated at 1 MB. */
object CaptureLog {
    const val MAX_BYTES = 1L * 1024 * 1024
    const val FILE = "captures.log"

    fun file(dir: File? = DiagnosticsLogger.diagnosticsDir()): File? = dir?.let { File(it, FILE) }

    /** One background writer: callers are on the main thread (viewModelScope); lines keep call order. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "captures-log").apply { isDaemon = true }
    }

    /**
     * Queues [line] for captures.log and returns at once. The timestamp and directory are taken now,
     * on the caller's thread, so the line reads the same as a synchronous write.
     */
    fun append(line: String, dir: File? = DiagnosticsLogger.diagnosticsDir(), nowMs: Long = System.currentTimeMillis()) {
        val f = file(dir) ?: return
        runCatching { writer.execute { write(f, line, nowMs) } }
    }

    /** Waits until every queued line is written (tests, and before reading the file). False on timeout. */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        if (runCatching { writer.execute { done.countDown() } }.isFailure) return false
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun write(f: File, line: String, nowMs: Long) {
        runCatching {
            if (f.length() > MAX_BYTES) {
                File(f.parentFile, "captures-1.log").delete()
                f.renameTo(File(f.parentFile, "captures-1.log"))
            }
            f.appendText(LogLineFormat.timestamp(nowMs) + " " + line + "\n", Charsets.UTF_8)
        }
    }

    fun format(v: Double?): String = v?.let { String.format(Locale.US, "%.3f", it) } ?: "?"
}