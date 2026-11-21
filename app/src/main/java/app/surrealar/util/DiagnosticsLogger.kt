package app.surrealar.util

import android.content.Context
import android.util.Log
import app.surrealar.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import app.surrealar.util.diagnostics.LogLineFormat

/**
 * Lightweight singleton logger that writes important app events to Logcat AND a rotating
 * plain-text file in internal storage. Intended for field diagnostics — testers can tap
 * "Export Diagnostic Report" on the About page to share the file without ADB.
 *
 * Call [init] once from Application.onCreate() before any other component.
 * Call [i], [w], [e] (and [d] in debug builds) from any thread.
 *
 * Files are stored in:   filesDir/diagnostics/logs/
 *   app-log-current.txt  ← active event log (up to ~5 MB)
 *   app-log-1.txt        ← most recent rotated
 *   app-log-2.txt
 *   app-log-3.txt
 *   app-log-4.txt        ← oldest kept
 *
 * When the current file exceeds [MAX_LOG_BYTES], it is rotated:
 *   app-log-4.txt is deleted, each log-N is shifted to log-(N+1),
 *   current is renamed to app-log-1.txt, and a fresh current file begins.
 *
 * Retention policy (see also the developer note in docs/diagnostics-logging.md):
 *   - Event log:  5 MB per active file × (1 current + [MAX_ROTATED] rotated) ≈ 25 MB of history.
 *     Sized so a full field session of state-change events survives until export. The whole set
 *     is bundled into the diagnostic ZIP, so the export never loses the AR failure sequence to
 *     last-N-lines tailing.
 *   - Errors/crashes are ALSO written to a SEPARATE file ([ERROR_CURRENT] + rotated) via [e].
 *     Keeping crashes out of the high-volume event log means the latest crash is not pushed out
 *     by ordinary events and survives across app launches (the file is appended, not truncated),
 *     so a crash from a previous launch is still in the export.
 *   - Raw NMEA is NEVER written here — it has its own separate, optional logger ([LogZip]).
 *
 * Normal writes go to one background writer thread (in call order). Crashes use [writeCrashSync],
 * which writes on the calling thread and fsyncs before returning, because the process dies right
 * after an uncaught exception. Logging failures (disk full, IO error) are caught and never crash the app.
 *
 * Privacy note: callers must never pass API keys, passwords, auth tokens, raw NMEA
 * streams, or full coordinate dumps to this logger.
 */
object DiagnosticsLogger {

    private const val MAX_LOG_BYTES = 5L * 1024 * 1024   // 5 MB per active file (field-session sized)
    private const val MAX_ROTATED   = 4                   // keep 4 rotated event-log files (~25 MB total)
    private const val CURRENT_FILE  = "app-log-current.txt"

    // Errors/crashes are mirrored to their own rotating file so the latest crash is never lost
    // when the busy event log rolls over. Smaller retention — crashes are rare and high-value.
    private const val ERROR_CURRENT     = "app-errors-current.txt"
    private const val MAX_ERROR_ROTATED = 2
    private const val MAX_ERROR_BYTES   = 1L * 1024 * 1024   // 1 MB per error file

    /** One background writer keeps lines in call order; [lock] guards the files for it and [writeCrashSync]. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true }
    }
    private val lock = Any()

    @Volatile private var logDir: File? = null
    @Volatile private var currentSize: Long = 0L
    @Volatile private var errorSize: Long = 0L

    /** Screen currently shown (set by navigation); included in crash records. */
    @Volatile var currentScreen: String? = null

    /** This process session's ID (set at start-up); included in crash records. */
    @Volatile var sessionId: String? = null

    /**
     * Must be called once before any log method. Safe to call multiple times.
     * Uses application context so it never leaks an Activity.
     */
    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.applicationContext.filesDir, "diagnostics/logs")
        dir.mkdirs()
        logDir = dir
        currentSize = File(dir, CURRENT_FILE).length()
        errorSize   = File(dir, ERROR_CURRENT).length()
    }

    /** Points the logger at [dir] (tests only). */
    internal fun initForTest(dir: File) {
        dir.mkdirs()
        logDir = dir
        currentSize = File(dir, CURRENT_FILE).length()
        errorSize   = File(dir, ERROR_CURRENT).length()
    }

    /**
     * Writes a crash to the error log AND the event log on the calling thread, fsynced, before
     * returning — the uncaught-exception handler calls this right before the process dies, when an
     * async write would be lost. Includes the thread, the full stack trace with causes, the current
     * screen and the session ID. [fatal] = false for exceptions caught by a coroutine handler.
     */
    fun writeCrashSync(thread: Thread, throwable: Throwable, fatal: Boolean) {
        val message = (if (fatal) "Uncaught exception (fatal)" else "Uncaught exception in a coroutine (not fatal)") +
            " thread=${thread.name} screen=${currentScreen ?: "unknown"} session=${sessionId ?: "unknown"}" +
            " type=${throwable.javaClass.name}: ${throwable.message}"
        runCatching { Log.e("GlobalCrash", message, throwable) }
        val dir = logDir ?: return
        val bytes = buildLogLine('E', "ERROR", message, throwable, thread.name).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            try {
                if (errorSize + bytes.size > MAX_ERROR_BYTES) rotateErrors(dir)
                appendSynced(File(dir, ERROR_CURRENT), bytes); errorSize += bytes.size
                if (currentSize + bytes.size > MAX_LOG_BYTES) rotate(dir)
                appendSynced(File(dir, CURRENT_FILE), bytes); currentSize += bytes.size
            } catch (ex: Throwable) {
                runCatching { Log.e("DiagnosticsLogger", "Crash write failed: ${ex.message}") }
            }
        }
    }

    private fun appendSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
    }

    /** Info-level event. Always written to file (once initialized). */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeAsync('I', tag, message, null)
    }

    /** Warning-level event. Optional throwable appended as stack trace. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeAsync('W', tag, message, throwable)
    }

    /**
     * Error-level event. Optional throwable appended as stack trace. Written to BOTH the rolling
     * event log and the separate, longer-lived error log so the latest crash is never lost when
     * the event log rolls over.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeAsync('E', tag, message, throwable)
        writeErrorAsync('E', tag, message, throwable)
    }

    // ── throttled helpers ─────────────────────────────────────────────────────

    private val NO_VALUE = Any()
    private val lastValues = ConcurrentHashMap<String, Any>()
    private val lastWrites = ConcurrentHashMap<String, Long>()

    /** Monotonic clock for [every]; swapped in tests. */
    @Volatile internal var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * Writes [message] only when [value] for [key] differs from the last value logged for that key
     * (the first call always writes). The message lambda runs only when the line is written, so
     * callers on hot paths pay one map lookup. Returns true if it wrote.
     */
    fun logOnChange(tag: String, key: String, value: Any?, level: Char = 'I', message: () -> String): Boolean {
        val v = value ?: NO_VALUE
        val previous = lastValues.put(key, v)
        if (previous == v) return false
        write(level, tag, message())
        return true
    }

    /** Writes [message] at most once per [intervalMs] for [key]. Returns true if it wrote. */
    fun every(tag: String, key: String, intervalMs: Long, level: Char = 'I', message: () -> String): Boolean {
        val now = nowMs()
        var due = false
        lastWrites.compute(key) { _, last ->
            if (last == null || now - last >= intervalMs) { due = true; now } else last
        }
        if (due) write(level, tag, message())
        return due
    }

    /** Forgets [logOnChange] values and [every] times (tests, and a new receiver session). */
    fun resetThrottles(keyPrefix: String = "") {
        lastValues.keys.removeAll { it.startsWith(keyPrefix) }
        lastWrites.keys.removeAll { it.startsWith(keyPrefix) }
    }

    private fun write(level: Char, tag: String, message: String) = when (level) {
        'E' -> e(tag, message)
        'W' -> w(tag, message)
        'D' -> d(tag, message)
        else -> i(tag, message)
    }

    /** Debug-level: written to file only in debug builds; always written to Logcat. */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        if (BuildConfig.DEBUG) writeAsync('D', tag, message, null)
    }

    /**
     * Overwrites a small, single-file "session summary" (e.g. `ar-last-session.txt`) in the
     * diagnostics dir. Unlike the rolling event log, this file persists the most recent session's
     * compact state so the export still has it even after the event log has rotated. Keep content
     * small (summary, not an event log). Written off the main thread; failures never crash.
     */
    fun writeSessionSummary(name: String, content: String) {
        val dir = logDir ?: return
        submit {
            try {
                File(dir, name).writeText(content, Charsets.UTF_8)
            } catch (ex: Exception) {
                Log.e("DiagnosticsLogger", "Session summary write failed ($name): ${ex.message}")
            }
        }
    }

    /** Returns a previously written session-summary file if it exists and has content. */
    fun sessionSummaryFile(name: String): File? =
        logDir?.let { File(it, name).takeIf { f -> f.exists() && f.length() > 0 } }

    /** The active diagnostics directory, or null before [init]. `internal` for unit tests only. */
    internal fun currentLogDir(): File? = logDir

    /** filesDir/diagnostics (parent of the logs dir), or null before [init]. */
    fun diagnosticsDir(): File? = logDir?.parentFile

    /**
     * Returns all log files that exist and have content, current first then rotated.
     * Used by [DiagnosticReportExporter] to bundle files into the ZIP.
     */
    fun logFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return buildList {
            File(dir, CURRENT_FILE).takeIf { it.exists() && it.length() > 0 }?.let { add(it) }
            for (n in 1..MAX_ROTATED) {
                File(dir, "app-log-$n.txt").takeIf { it.exists() && it.length() > 0 }?.let { add(it) }
            }
        }
    }

    /**
     * Returns the separate error/crash log files that exist and have content, current first then
     * rotated. Used by [DiagnosticReportExporter] to bundle errors.log into the export so the latest
     * crash is always present — even one from a previous app launch.
     */
    fun errorFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return buildList {
            File(dir, ERROR_CURRENT).takeIf { it.exists() && it.length() > 0 }?.let { add(it) }
            for (n in 1..MAX_ERROR_ROTATED) {
                File(dir, "app-errors-$n.txt").takeIf { it.exists() && it.length() > 0 }?.let { add(it) }
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /**
     * Waits until every line queued so far is on disk (or [timeoutMs] passes). The exporter calls this
     * before zipping so the last events before "Export" are in the report. Don't call it from the
     * writer thread. Returns false on timeout.
     */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        if (logDir == null) return true
        val done = CountDownLatch(1)
        val queued = runCatching { writer.execute { done.countDown() } }.isSuccess
        return queued && done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Runs [block] on the writer thread under [lock]. */
    private fun submit(block: () -> Unit) {
        runCatching { writer.execute { synchronized(lock) { block() } } }
    }

    private fun writeAsync(level: Char, tag: String, message: String, t: Throwable?) {
        val dir = logDir ?: return
        val line = buildLogLine(level, tag, message, t)   // built on the caller (its thread and time)
        submit {
            try {
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (currentSize + bytes.size > MAX_LOG_BYTES) rotate(dir)
                File(dir, CURRENT_FILE).appendBytes(bytes)
                currentSize += bytes.size
            } catch (ex: Exception) {
                Log.e("DiagnosticsLogger", "File write failed: ${ex.message}")
            }
        }
    }

    private fun writeErrorAsync(level: Char, tag: String, message: String, t: Throwable?) {
        val dir = logDir ?: return
        val line = buildLogLine(level, tag, message, t)
        submit {
            try {
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (errorSize + bytes.size > MAX_ERROR_BYTES) rotateErrors(dir)
                File(dir, ERROR_CURRENT).appendBytes(bytes)
                errorSize += bytes.size
            } catch (ex: Exception) {
                Log.e("DiagnosticsLogger", "Error-file write failed: ${ex.message}")
            }
        }
    }

    /** One line in [LogLineFormat] (time, level, tag, [thread], message), then the stack trace if any. */
    private fun buildLogLine(
        level: Char, tag: String, message: String, t: Throwable?,
        thread: String = Thread.currentThread().name,
    ): String =
        buildString {
            appendLine(LogLineFormat.format(System.currentTimeMillis(), level, tag, thread, message.replace('\n', ' ')))
            if (t != null) {
                appendLine(t.stackTraceToString().trimEnd())
            }
        }

    private fun rotate(dir: File) {
        try {
            // Delete oldest rotated file
            File(dir, "app-log-$MAX_ROTATED.txt").delete()
            // Shift: log-2 → log-3, log-1 → log-2
            for (n in MAX_ROTATED - 1 downTo 1) {
                val src = File(dir, "app-log-$n.txt")
                if (src.exists()) src.renameTo(File(dir, "app-log-${n + 1}.txt"))
            }
            // Current → log-1
            File(dir, CURRENT_FILE).takeIf { it.exists() }
                ?.renameTo(File(dir, "app-log-1.txt"))
            currentSize = 0L
        } catch (ex: Exception) {
            Log.e("DiagnosticsLogger", "Log rotation failed: ${ex.message}")
        }
    }

    private fun rotateErrors(dir: File) {
        try {
            File(dir, "app-errors-$MAX_ERROR_ROTATED.txt").delete()
            for (n in MAX_ERROR_ROTATED - 1 downTo 1) {
                val src = File(dir, "app-errors-$n.txt")
                if (src.exists()) src.renameTo(File(dir, "app-errors-${n + 1}.txt"))
            }
            File(dir, ERROR_CURRENT).takeIf { it.exists() }
                ?.renameTo(File(dir, "app-errors-1.txt"))
            errorSize = 0L
        } catch (ex: Exception) {
            Log.e("DiagnosticsLogger", "Error-log rotation failed: ${ex.message}")
        }
    }
}
