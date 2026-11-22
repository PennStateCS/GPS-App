package app.surrealar.util

import android.util.Log

/**
 * Developer traces for logcat only, debug builds only — the replacement for raw `android.util.Log.d`
 * and `Log.i`. Nothing is written to the diagnostics file, and in release builds the message lambda is
 * neither built nor printed.
 *
 * Use it for detail a developer watches live with `adb logcat` (per-step traces, dumps). Anything a
 * tester's exported report should contain goes through [DiagnosticsLogger] instead (`d` for debug
 * detail that should also be in a debug build's file; `i`/`w`/`e` for events). Hot paths still need a
 * throttle: see [every].
 *
 * Tags here are free-form class names (logcat filtering), unlike the fixed DiagTags of the file log.
 */
object DevLog {

    /** True in debug builds. A `var` for tests. */
    @Volatile var enabled: Boolean = runCatching { app.surrealar.BuildConfig.DEBUG }.getOrDefault(false)

    private val lastWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Debug trace to logcat; [message] is built only when [enabled]. */
    inline fun d(tag: String, message: () -> String) {
        if (enabled) write(tag, message())
    }

    /** [d] at most once per [intervalMs] for [key] — for per-frame / per-fix traces. */
    inline fun every(tag: String, key: String, intervalMs: Long, message: () -> String) {
        if (enabled && due(key, intervalMs)) write(tag, message())
    }

    @PublishedApi internal fun write(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
    }

    @PublishedApi internal fun due(key: String, intervalMs: Long): Boolean {
        val now = System.nanoTime() / 1_000_000L
        var due = false
        lastWrites.compute(key) { _, last -> if (last == null || now - last >= intervalMs) { due = true; now } else last }
        return due
    }
}
