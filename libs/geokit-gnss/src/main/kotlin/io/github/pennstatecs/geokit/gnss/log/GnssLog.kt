package io.github.pennstatecs.geokit.gnss.log

/**
 * Logging backend for geokit-gnss. The module never logs on its own: the host installs an
 * implementation once at startup with [GnssLog.install]. Until then (and in unit tests) every call
 * goes to [NoOp].
 *
 * Two channels, kept apart on purpose:
 * - [d]/[i]/[w]/[e]: the developer log (logcat on Android). May contain sentence text.
 * - [diagnostic]: an info-level event that the host also keeps in its exportable diagnostics log.
 *   Callers never put coordinates in it.
 *
 * Debug traces ([GnssLog.every]) are only produced while [enabled] is true and are throttled per key
 * through [isDue], then written with [trace].
 *
 * Implementations must be thread-safe: the parsers and fuser call them from whichever thread reads
 * the receiver.
 */
public interface GnssLogger {
    /** True when debug traces should be produced (e.g. debug builds). Read on every trace call. */
    public val enabled: Boolean

    /** Debug message to the developer log. */
    public fun d(tag: String, message: String)

    /** Info message to the developer log. */
    public fun i(tag: String, message: String)

    /** Warning to the developer log, with an optional [throwable]. */
    public fun w(tag: String, message: String, throwable: Throwable? = null)

    /** Error to the developer log, with an optional [throwable]. */
    public fun e(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Event that is also kept in the host's diagnostics log (no coordinates), at [level] (info unless
     * given), with an optional [throwable].
     */
    public fun diagnostic(tag: String, message: String, level: DiagnosticLevel = DiagnosticLevel.INFO, throwable: Throwable? = null)

    /**
     * Throttle gate for [GnssLog.every]: returns true, and records the time, if [key] has not been
     * due within the last [intervalMs] milliseconds. Must be thread-safe.
     */
    public fun isDue(key: String, intervalMs: Long): Boolean

    /** Writes one debug trace [message] under [area] (e.g. "fuser", "sky"). */
    public fun trace(area: String, message: String)

    /** Discards everything. The default until [GnssLog.install] is called. */
    public object NoOp : GnssLogger {
        override val enabled: Boolean get() = false
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {}
        override fun isDue(key: String, intervalMs: Long): Boolean = false
        override fun trace(area: String, message: String) {}
    }
}

/** Severity of a [GnssLogger.diagnostic] event. */
public enum class DiagnosticLevel { INFO, WARN, ERROR }

/**
 * The single place geokit-gnss logs through. Install a [GnssLogger] once at startup, before any GNSS
 * source starts; the field is volatile, so installing from one thread is seen by all others.
 */
public object GnssLog {
    /** Default throttle interval for [every], milliseconds. */
    public const val DEFAULT_INTERVAL_MS: Long = 15_000L

    @Volatile
    @PublishedApi
    internal var logger: GnssLogger = GnssLogger.NoOp

    /** Routes all geokit-gnss logging to [impl]. */
    public fun install(impl: GnssLogger) {
        logger = impl
    }

    /** Back to [GnssLogger.NoOp] (tests). */
    public fun uninstall() {
        logger = GnssLogger.NoOp
    }

    /** True when debug traces are produced (see [GnssLogger.enabled]). */
    public val enabled: Boolean get() = logger.enabled

    /**
     * Debug message — written only while [enabled] (debug builds / developer tracing), so release
     * logcat never gets debug lines. See [GnssLogger.d].
     */
    public fun d(tag: String, message: String) {
        val l = logger
        if (l.enabled) l.d(tag, message)
    }

    /** Debug message built only while [enabled]: nothing is formatted in release. */
    public inline fun d(tag: String, message: () -> String) {
        val l = logger
        if (l.enabled) l.d(tag, message())
    }

    /** Info message built and written only while [enabled] (detail that release builds don't need). */
    public inline fun i(tag: String, message: () -> String) {
        val l = logger
        if (l.enabled) l.i(tag, message())
    }

    /** Warning built and written only while [enabled] (detail that release builds don't need). */
    public inline fun w(tag: String, message: () -> String) {
        val l = logger
        if (l.enabled) l.w(tag, message())
    }

    /**
     * Throttled debug message under [tag]: at most once per [intervalMs] for [key], and only while
     * [enabled]. For per-message detail (e.g. receiver broadcasts) that would otherwise flood logcat.
     */
    public inline fun dEvery(tag: String, key: String, intervalMs: Long = DEFAULT_INTERVAL_MS, message: () -> String) {
        val l = logger
        if (!l.enabled) return
        if (!l.isDue(key, intervalMs)) return
        l.d(tag, message())
    }

    /** See [GnssLogger.i]. */
    public fun i(tag: String, message: String): Unit = logger.i(tag, message)

    /** See [GnssLogger.w]. */
    public fun w(tag: String, message: String, throwable: Throwable? = null): Unit = logger.w(tag, message, throwable)

    /** See [GnssLogger.e]. */
    public fun e(tag: String, message: String, throwable: Throwable? = null): Unit = logger.e(tag, message, throwable)

    /** See [GnssLogger.diagnostic]. */
    public fun diagnostic(
        tag: String, message: String, level: DiagnosticLevel = DiagnosticLevel.INFO, throwable: Throwable? = null,
    ): Unit = logger.diagnostic(tag, message, level, throwable)

    /** [diagnostic] at [DiagnosticLevel.WARN]. */
    public fun diagnosticWarn(tag: String, message: String, throwable: Throwable? = null): Unit =
        logger.diagnostic(tag, message, DiagnosticLevel.WARN, throwable)

    /** [diagnostic] at [DiagnosticLevel.ERROR]. */
    public fun diagnosticError(tag: String, message: String, throwable: Throwable? = null): Unit =
        logger.diagnostic(tag, message, DiagnosticLevel.ERROR, throwable)

    /**
     * Throttled debug trace: writes [message] under [area] at most once per [intervalMs] for [key].
     * Inline, so when tracing is disabled or the key is throttled the message lambda is neither
     * allocated nor evaluated (one volatile read and a boolean check).
     */
    public inline fun every(key: String, area: String, intervalMs: Long = DEFAULT_INTERVAL_MS, message: () -> String) {
        val l = logger
        if (!l.enabled) return
        if (!l.isDue(key, intervalMs)) return
        l.trace(area, message())
    }
}
