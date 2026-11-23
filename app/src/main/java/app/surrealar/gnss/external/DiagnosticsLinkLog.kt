package app.surrealar.gnss.external

import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel

/**
 * The app's [LinkLog] for the external receiver link (geokit's TcpNmeaSource and
 * ReconnectingNmeaAdapter): every line goes to [DiagnosticsLogger] (logcat + the exportable
 * diagnostics log) with its throttle keys, exactly as when that code lived in the app.
 */
object DiagnosticsLinkLog : LinkLog {

    override fun d(tag: String, message: () -> String) = DiagnosticsLogger.d(tag, message)

    override fun i(tag: String, message: String) = DiagnosticsLogger.i(tag, message)

    override fun w(tag: String, message: String, throwable: Throwable?) = DiagnosticsLogger.w(tag, message, throwable)

    override fun logOnChange(
        tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String,
    ): Boolean = DiagnosticsLogger.logOnChange(tag, key, value, level.char(), throwable = throwable, message = message)

    override fun every(
        tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String,
    ): Boolean = DiagnosticsLogger.every(tag, key, intervalMs, level.char(), throwable, message)

    override fun resetThrottles(keyPrefix: String) = DiagnosticsLogger.resetThrottles(keyPrefix)

    internal fun LinkLogLevel.char(): Char = when (this) {
        LinkLogLevel.DEBUG -> 'D'
        LinkLogLevel.INFO -> 'I'
        LinkLogLevel.WARN -> 'W'
        LinkLogLevel.ERROR -> 'E'
    }
}
