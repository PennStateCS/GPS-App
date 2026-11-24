package app.surrealar.gnss.external

import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter

/**
 * The app's [LinkLog] for the external receiver link (geokit's TcpNmeaSource and
 * ReconnectingNmeaAdapter): every line goes to [DiagnosticsLogger] (logcat + the exportable
 * diagnostics log) with its throttle keys, exactly as when that code lived in the app.
 */
object DiagnosticsLinkLog : LinkLog {

    /**
     * Told when the link reports a connected TCP socket (TcpNmeaSource's "socket" retry key, which it
     * passes on every connect even when the line itself is throttled). Lets ReceiverLinkTracker tell
     * an unreachable receiver from one that accepts the socket but sends no NMEA. Logging only.
     */
    @Volatile var onSocketConnected: (() -> Unit)? = null

    /** The key TcpNmeaSource uses for its "TCP socket connected" line. */
    internal const val SOCKET_KEY = ReconnectingNmeaAdapter.RETRY_LOG_PREFIX + "socket"

    override fun d(tag: String, message: () -> String) = DiagnosticsLogger.d(tag, message)

    override fun i(tag: String, message: String) = DiagnosticsLogger.i(tag, message)

    override fun w(tag: String, message: String, throwable: Throwable?) = DiagnosticsLogger.w(tag, message, throwable)

    override fun logOnChange(
        tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String,
    ): Boolean {
        if (key == SOCKET_KEY) runCatching { onSocketConnected?.invoke() }
        return DiagnosticsLogger.logOnChange(tag, key, value, level.char(), throwable = throwable, message = message)
    }

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
