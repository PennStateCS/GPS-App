package app.surrealar.util.diagnostics

import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Device clock vs GNSS time. Only fixes stamped with GNSS time (ZDA / provider clock) count; the
 * offset includes the few hundred ms of delivery latency, so it is logged in whole seconds: on
 * change, and every 60 s. More than 2 s off is a warning (a wrong device clock skews fix ages).
 */
object ClockOffset {

    @Volatile var lastOffsetMs: Long? = null
        private set

    /** Device clock minus GNSS time, or null when the fix has no GNSS time. */
    fun offsetMs(fix: Fix, wallNowMs: Long): Long? = when (fix.timestampSource) {
        TimestampSource.NMEA_ZDA, TimestampSource.GNSS_PROVIDER -> wallNowMs - fix.timeUtc.toEpochMilli()
        else -> null
    }

    fun describe(offsetMs: Long, source: TimestampSource): String =
        "Clock offset device−GNSS ${String.format(Locale.US, "%+.1f", offsetMs / 1000.0)} s (${source.name})"

    /** Called per fix by [GnssFeedLogger]; cheap unless a line is due. */
    fun onFix(fix: Fix, wallNowMs: Long) {
        val off = offsetMs(fix, wallNowMs) ?: return
        lastOffsetMs = off
        val level = if (abs(off) > 2_000L) 'W' else 'I'
        val wroteChange = DiagnosticsLogger.logOnChange(DiagTags.GNSS, "gnss.clock", (off / 1000.0).roundToLong(), level) {
            describe(off, fix.timestampSource)
        }
        if (wroteChange) DiagnosticsLogger.resetThrottles("gnss.clock.every")
        DiagnosticsLogger.every(DiagTags.GNSS, "gnss.clock.every", 60_000L, level) { describe(off, fix.timestampSource) }
    }
}