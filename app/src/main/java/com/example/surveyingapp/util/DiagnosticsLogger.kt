package com.example.surveyingapp.util

import android.content.Context
import android.util.Log
import com.example.surveyingapp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight singleton logger that writes important app events to Logcat AND a rotating
 * plain-text file in internal storage. Intended for field diagnostics — testers can tap
 * "Export Diagnostic Report" on the About page to share the file without ADB.
 *
 * Call [init] once from Application.onCreate() before any other component.
 * Call [i], [w], [e] (and [d] in debug builds) from any thread.
 *
 * Files are stored in:   filesDir/diagnostics/logs/
 *   app-log-current.txt  ← active log (up to ~1 MB)
 *   app-log-1.txt        ← most recent rotated
 *   app-log-2.txt
 *   app-log-3.txt        ← oldest kept
 *
 * When the current file exceeds MAX_LOG_BYTES, it is rotated:
 *   app-log-3.txt is deleted, each log-N is shifted to log-(N+1),
 *   current is renamed to app-log-1.txt, and a fresh current file begins.
 *
 * File IO is always dispatched off the main thread via an internal CoroutineScope.
 * Logging failures (disk full, IO error) are caught and never crash the app.
 *
 * Privacy note: callers must never pass API keys, passwords, auth tokens, raw NMEA
 * streams, or full coordinate dumps to this logger.
 */
object DiagnosticsLogger {

    private const val MAX_LOG_BYTES = 1L * 1024 * 1024   // 1 MB per file
    private const val MAX_ROTATED   = 3                   // keep 3 rotated files
    private const val CURRENT_FILE  = "app-log-current.txt"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex   = Mutex()
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logDir: File? = null
    @Volatile private var currentSize: Long = 0L

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
    }

    /** Info-level event. Always written to file (once initialized). */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeAsync("INFO ", tag, message, null)
    }

    /** Warning-level event. Optional throwable appended as stack trace. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeAsync("WARN ", tag, message, throwable)
    }

    /** Error-level event. Optional throwable appended as stack trace. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeAsync("ERROR", tag, message, throwable)
    }

    /** Debug-level: written to file only in debug builds; always written to Logcat. */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        if (BuildConfig.DEBUG) writeAsync("DEBUG", tag, message, null)
    }

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

    // ── internals ──────────────────────────────────────────────────────────────

    private fun writeAsync(level: String, tag: String, message: String, t: Throwable?) {
        val dir = logDir ?: return
        ioScope.launch {
            mutex.withLock {
                try {
                    val file = File(dir, CURRENT_FILE)
                    val line = buildLogLine(level, tag, message, t)
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    if (currentSize + bytes.size > MAX_LOG_BYTES) {
                        rotate(dir)
                    }
                    file.appendBytes(bytes)
                    currentSize += bytes.size
                } catch (ex: Exception) {
                    Log.e("DiagnosticsLogger", "File write failed: ${ex.message}")
                }
            }
        }
    }

    private fun buildLogLine(level: String, tag: String, message: String, t: Throwable?): String =
        buildString {
            append(tsFormat.format(Date()))
            append(' ')
            append(level)
            append(' ')
            append(tag)
            append(" - ")
            appendLine(message)
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
}
