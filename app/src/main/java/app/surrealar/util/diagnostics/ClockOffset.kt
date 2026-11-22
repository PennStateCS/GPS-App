package app.surrealar.util.diagnostics

import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import java.util.Locale
import kotlin.math.abs

/**
 * Device clock vs GNSS time. Only fixes stamped with GNSS time (ZDA / provider clock) count; the
 * offset includes the few hundred ms of delivery latency (which jitters), so a line is written only
 * when the offset has moved at least [CHANGE_MS] from the last LOGGED value (hysteresis: jitter around
 * a rounding edge never flips it), plus a heartbeat when nothing was logged for [HEARTBEAT_MS]. More
 * than 2 s off is a warning (a wrong device clock skews fix ages).
 */
object ClockOffset {

    internal const val CHANGE_MS = 1_000L
    internal const val HEARTBEAT_MS = 600_000L
    private const val WARN_MS = 2_000L

    @Volatile var lastOffsetMs: Long? = null
        private set

    /** The offset in the last line written, and when (DiagnosticsLogger.nowMs); null until the first. */
    @Volatile private var lastLoggedOffsetMs: Long? = null
    @Volatile private var lastLineAtMs: Long = 0L

    /** Device clock minus GNSS time, or null when the fix has no GNSS time. */
    fun offsetMs(fix: Fix, wallNowMs: Long): Long? = when (fix.timestampSource) {
        TimestampSource.NMEA_ZDA, TimestampSource.GNSS_PROVIDER -> wallNowMs - fix.timeUtc.toEpochMilli()
        else -> null
    }

    fun describe(offsetMs: Long, source: TimestampSource): String =
        "Clock offset measured deviceMinusGnssS=${String.format(Locale.US, "%.1f", offsetMs / 1000.0)} source=${source.name}"

    /** True when [offsetMs] is a change worth a line, given the offset in the last line written. */
    internal fun isChange(lastLogged: Long?, offsetMs: Long): Boolean =
        lastLogged == null || abs(offsetMs - lastLogged) >= CHANGE_MS

    /** Forgets the last logged offset (tests). */
    internal fun reset() {
        lastOffsetMs = null
        lastLoggedOffsetMs = null
        lastLineAtMs = 0L
    }

    /** Called per fix by [GnssFeedLogger]; cheap unless a line is due. */
    fun onFix(fix: Fix, wallNowMs: Long) {
        val off = offsetMs(fix, wallNowMs) ?: return
        lastOffsetMs = off
        val now = DiagnosticsLogger.nowMs()
        val due = isChange(lastLoggedOffsetMs, off) || now - lastLineAtMs >= HEARTBEAT_MS
        if (!due) return
        lastLoggedOffsetMs = off
        lastLineAtMs = now
        val line = describe(off, fix.timestampSource)
        if (abs(off) > WARN_MS) DiagnosticsLogger.w(DiagTags.GNSS, line) else DiagnosticsLogger.i(DiagTags.GNSS, line)
    }
}
