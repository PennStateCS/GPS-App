package app.surrealar.util.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.surrealar.util.DiagnosticsLogger
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why earlier processes ended (ActivityManager.getHistoricalProcessExitReasons, API 30+):
 * one log line per new exit, and the ANR / native-crash traces saved under diagnostics/exits/.
 * "New" = newer than the last exit already recorded (its timestamp is kept in prefs), so a second
 * start never duplicates them.
 */
class ExitReasonRecorder(
    private val exitsDir: File,
    private val prefs: SharedPreferences,
    private val log: (warn: Boolean, message: String) -> Unit = { warn, m ->
        if (warn) DiagnosticsLogger.w("APP", m) else DiagnosticsLogger.i("APP", m)
    },
) {
    /** Android-free view of one ApplicationExitInfo. */
    data class Exit(
        val timestampMs: Long,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        val trace: () -> InputStream? = { null },
    )

    /** Records exits newer than the last one recorded; returns how many were new. */
    fun record(exits: List<Exit>): Int {
        val last = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        val fresh = exits.filter { it.timestampMs > last }.sortedBy { it.timestampMs }
        if (fresh.isEmpty()) return 0
        for (e in fresh) {
            val name = reasonName(e.reason)
            val bad = e.reason == REASON_ANR || e.reason == REASON_CRASH || e.reason == REASON_CRASH_NATIVE
            log(bad, "Previous process exit: reason=$name status=${e.status} importance=${e.importance}" +
                " pssKb=${e.pssKb} rssKb=${e.rssKb} at=${stamp(e.timestampMs)}" +
                " description=${e.description?.take(200)?.let { "\"${it.replace('"', '\'')}\"" } ?: "none"}")
            if (e.reason == REASON_ANR || e.reason == REASON_CRASH_NATIVE) saveTrace(e, name)
        }
        prefs.edit().putLong(KEY_LAST_EXIT_TS, fresh.last().timestampMs).apply()
        prune()
        return fresh.size
    }

    private fun saveTrace(e: Exit, name: String) {
        runCatching {
            val input = e.trace() ?: return
            exitsDir.mkdirs()
            val file = File(exitsDir, "${fileStamp(e.timestampMs)}-$name.txt")
            input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        }.onFailure { log(true, "Exit trace save failed trace=$name error=${it.javaClass.simpleName}") }
    }

    /** Keeps the newest [MAX_TRACES] trace files. */
    private fun prune() {
        exitsDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }
            ?.drop(MAX_TRACES)?.forEach { it.delete() }
    }

    companion object {
        const val PREFS = "diagnostics"
        const val KEY_LAST_EXIT_TS = "last_exit_reason_ts"
        const val MAX_TRACES = 5
        // ApplicationExitInfo constants (copied so the class works below API 30 and in plain tests).
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6

        fun reasonName(reason: Int): String = when (reason) {
            0 -> "UNKNOWN"; 1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"
            5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"; else -> "REASON_$reason"
        }

        private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))
        private fun fileStamp(ms: Long) = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))

        fun exitsDir(context: Context) = File(context.filesDir, "diagnostics/exits")

        /** Reads the system's exit reasons (API 30+) on a background thread and records the new ones. */
        fun recordAtStartup(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val app = context.applicationContext
            Thread({
                runCatching {
                    val am = app.getSystemService(ActivityManager::class.java) ?: return@runCatching
                    val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 10).map { it.toExit() }
                    ExitReasonRecorder(exitsDir(app), app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).record(exits)
                }.onFailure { DiagnosticsLogger.w(DiagTags.APP, "Exit reasons read failed", it) }
            }, "diag-exits").start()
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
        private fun ApplicationExitInfo.toExit() = Exit(
            timestampMs = timestamp, reason = reason, status = status, importance = importance,
            pssKb = pss, rssKb = rss, description = description,
            trace = { runCatching { traceInputStream }.getOrNull() },
        )
    }
}