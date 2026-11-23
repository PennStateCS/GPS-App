package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.log.GnssLog
import java.util.concurrent.ConcurrentHashMap

/** Severity of a [LinkLog] line. */
public enum class LinkLogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Where [TcpNmeaSource] and [ReconnectingNmeaAdapter] write their connection lines (connect, retry,
 * dead link, reconnect). These are field-diagnostics events, so a host usually keeps them in its
 * exportable log; they never contain coordinates or sentence text.
 *
 * Besides plain levels it has two throttles, because a receiver that is off retries for hours:
 * [logOnChange] (only when a value changes) and [every] (at most once per interval). Both are keyed;
 * [resetThrottles] forgets keys by prefix, so a new outage logs its first failure in full. One
 * instance should be shared by the source and the adapter, since the adapter resets the source's keys.
 *
 * Implementations must be thread-safe. The default is [GnssLinkLog].
 */
public interface LinkLog {
    /** Debug detail; [message] is built only if debug lines are written. */
    public fun d(tag: String, message: () -> String)

    /** Info event. */
    public fun i(tag: String, message: String)

    /** Warning, with an optional [throwable]. */
    public fun w(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Writes [message] at [level] only when [value] for [key] differs from the last value written for
     * that key (the first call always writes). [message] runs only when the line is written. Returns
     * true if it wrote.
     */
    public fun logOnChange(
        tag: String, key: String, value: Any?, level: LinkLogLevel = LinkLogLevel.INFO,
        throwable: Throwable? = null, message: () -> String,
    ): Boolean

    /** Writes [message] at [level] at most once per [intervalMs] for [key]. Returns true if it wrote. */
    public fun every(
        tag: String, key: String, intervalMs: Long, level: LinkLogLevel = LinkLogLevel.INFO,
        throwable: Throwable? = null, message: () -> String,
    ): Boolean

    /** Forgets [logOnChange] values and [every] times for keys starting with [keyPrefix]. */
    public fun resetThrottles(keyPrefix: String = "")

    public companion object {
        /** Tag for receiver connection events. */
        public const val TAG_RECEIVER: String = "RECEIVER"
        /** Tag for NMEA processing events. */
        public const val TAG_NMEA: String = "NMEA"
    }
}

/**
 * Default [LinkLog]: keeps its own throttle state and writes through [GnssLog] — debug lines with
 * [GnssLog.d], info/warn/error with [GnssLog.diagnostic] / [GnssLog.diagnosticWarn] /
 * [GnssLog.diagnosticError]. An info line with a throwable names the exception instead of carrying a
 * stack trace.
 */
public class GnssLinkLog(
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : LinkLog {
    private val noValue = Any()
    private val lastValues = ConcurrentHashMap<String, Any>()
    private val lastWrites = ConcurrentHashMap<String, Long>()

    override fun d(tag: String, message: () -> String): Unit = GnssLog.d(tag, message)

    override fun i(tag: String, message: String): Unit = GnssLog.diagnostic(tag, message)

    override fun w(tag: String, message: String, throwable: Throwable?): Unit =
        GnssLog.diagnosticWarn(tag, message, throwable)

    override fun logOnChange(
        tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String,
    ): Boolean {
        val v = value ?: noValue
        val previous = lastValues.put(key, v)
        if (previous == v) return false
        write(level, tag, message(), throwable)
        return true
    }

    override fun every(
        tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String,
    ): Boolean {
        val now = monotonicNowMs()
        var due = false
        lastWrites.compute(key) { _, last ->
            if (last == null || now - last >= intervalMs) { due = true; now } else last
        }
        if (due) write(level, tag, message(), throwable)
        return due
    }

    override fun resetThrottles(keyPrefix: String) {
        lastValues.keys.removeAll { it.startsWith(keyPrefix) }
        lastWrites.keys.removeAll { it.startsWith(keyPrefix) }
    }

    private fun write(level: LinkLogLevel, tag: String, message: String, throwable: Throwable?) = when (level) {
        LinkLogLevel.ERROR -> GnssLog.diagnosticError(tag, message, throwable)
        LinkLogLevel.WARN -> GnssLog.diagnosticWarn(tag, message, throwable)
        LinkLogLevel.DEBUG -> GnssLog.d(tag, message)
        LinkLogLevel.INFO -> GnssLog.diagnostic(
            tag, if (throwable == null) message else "$message (${throwable.javaClass.simpleName}: ${throwable.message})",
        )
    }
}
