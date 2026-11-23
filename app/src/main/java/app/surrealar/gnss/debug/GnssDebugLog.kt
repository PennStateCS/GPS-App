package app.surrealar.gnss.debug

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-build-only GNSS tracing to logcat, for comparing what the receiver actually sent with what
 * the app computed and displayed.
 *
 * - **Debug builds only** ([enabled] = BuildConfig.DEBUG) and **logcat only** — never written to the
 *   diagnostic export / DiagnosticsLogger files, so raw coordinates never leave the device's logcat.
 * - One tag, [TAG], with an `[area]` prefix:  `adb logcat -s GnssDebug`
 *   Areas: `nmea-int` / `nmea-ext` (raw sentence samples), `fuser` (computed fix), `sky` (satellite
 *   inventory), `switchboard` (binding / liveness), `internal` (fallback / enrichment), `external`
 *   (connection), `mock` (mock publishing), `toolbar` (what is on screen), `corrections` (Reach
 *   socket.io broadcasts), `receiver` (Reach HTTP /info, /status). Library code reaches this through
 *   GnssLog.every(key, area).
 * - [every] throttles per key, so periodic samples stay readable (default 15 s).
 *
 * Message lambdas are only evaluated when a line is actually written, so disabled/throttled calls
 * cost a boolean check and a map lookup.
 */
object GnssDebugLog {
    const val TAG = "GnssDebug"
    const val DEFAULT_INTERVAL_MS = 15_000L

    /** Debug builds only. A `var` so unit tests (and a future developer toggle) can turn it on/off. */
    @Volatile var enabled: Boolean = runCatching { app.surrealar.BuildConfig.DEBUG }.getOrDefault(false)

    private val lastLogged = ConcurrentHashMap<String, Long>()

    /** Logs every call (use for rare events: state changes, fallbacks, errors). */
    inline fun event(area: String, message: () -> String) {
        if (!enabled) return
        write(area, message())
    }

    /** Logs at most once per [intervalMs] for [key] (use for per-epoch / per-sentence samples). */
    inline fun every(key: String, area: String, intervalMs: Long = DEFAULT_INTERVAL_MS, message: () -> String) {
        if (!enabled) return
        if (!due(key, intervalMs)) return
        write(area, message())
    }

    @PublishedApi
    internal fun due(key: String, intervalMs: Long): Boolean {
        val now = nowMs()
        val last = lastLogged[key]
        if (last != null && now - last < intervalMs) return false
        lastLogged[key] = now
        return true
    }

    @PublishedApi
    internal fun write(area: String, msg: String) {
        // Logcat truncates long lines (~4 KB); split so multi-sentence samples stay complete.
        msg.lineSequence().forEach { line ->
            runCatching { android.util.Log.d(TAG, "[$area] $line") }
        }
    }

    /** Monotonic clock; unit tests run without Android, so fall back to nanoTime. */
    private fun nowMs(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrNull()?.takeIf { it > 0 } ?: (System.nanoTime() / 1_000_000L)

    /** Test hook: forget throttle state. */
    internal fun resetForTest() = lastLogged.clear()
}
