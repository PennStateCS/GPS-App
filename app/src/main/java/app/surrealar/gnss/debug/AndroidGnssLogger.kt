package app.surrealar.gnss.debug

import android.util.Log
import app.surrealar.gnss.log.DiagnosticLevel
import app.surrealar.gnss.log.GnssLogger
import app.surrealar.util.DiagnosticsLogger

/**
 * The app's [GnssLogger]: sends gnss-core's log calls to exactly the places they went before the
 * module existed. Installed once in SurRealApplication.onCreate.
 *
 * - d/i/w/e → android.util.Log (logcat only), same tag and message.
 * - diagnostic → DiagnosticsLogger.i / .w / .e by level (logcat + the exportable diagnostics log).
 * - enabled / isDue / trace → GnssDebugLog (debug builds only, tag "GnssDebug", "[area] " prefix,
 *   the same per-key throttle map as the app's own GnssDebugLog.every calls).
 */
object AndroidGnssLogger : GnssLogger {
    override val enabled: Boolean get() = GnssDebugLog.enabled

    override fun d(tag: String, message: String) { Log.d(tag, message) }

    override fun i(tag: String, message: String) { Log.i(tag, message) }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) = when (level) {
        DiagnosticLevel.INFO -> DiagnosticsLogger.i(tag, message)
        DiagnosticLevel.WARN -> DiagnosticsLogger.w(tag, message, throwable)
        DiagnosticLevel.ERROR -> DiagnosticsLogger.e(tag, message, throwable)
    }

    override fun isDue(key: String, intervalMs: Long): Boolean = GnssDebugLog.due(key, intervalMs)

    override fun trace(area: String, message: String) = GnssDebugLog.write(area, message)
}
