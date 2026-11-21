package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import java.io.File

/** One parsed log line plus the stack-trace lines under it. */
data class LogEntry(
    val epochMs: Long, val level: Char, val tag: String, val thread: String, val message: String,
    val raw: String, val continuation: List<String> = emptyList(),
)

/**
 * One streaming pass over the event and error logs that keeps only what the report needs (the logs
 * can be ~25 MB, so nothing else is held in memory). Errors appear in both logs; they are counted once.
 */
class LogIndex(val nowMs: Long) {

    var firstMs: Long? = null; private set
    var lastMs: Long? = null; private set
    val sessions = mutableListOf<LogEntry>()
    val crashes = mutableListOf<LogEntry>()          // fatal uncaught exceptions + ANR/crash exit reasons
    val exits = mutableListOf<LogEntry>()            // "Previous process exit" lines
    val timeline = mutableListOf<LogEntry>()         // last 2 h, see [TIMELINE_TAGS]
    val warnCounts24h = HashMap<String, Int>()       // normalized W/E message → count
    val warnFirstText = HashMap<String, String>()
    var lastDbEvent: LogEntry? = null; private set
    private val seenErrors = HashSet<String>()

    fun add(e: LogEntry) {
        if (e.level == 'E' && !seenErrors.add(e.raw)) return
        if (firstMs == null || e.epochMs < firstMs!!) firstMs = e.epochMs
        if (lastMs == null || e.epochMs > lastMs!!) lastMs = e.epochMs
        val m = e.message
        if (m.startsWith("SESSION_START")) sessions += e
        if (m.startsWith("Previous process exit")) exits += e
        if ((e.level == 'E' && m.startsWith("Uncaught exception (fatal)")) ||
            (m.startsWith("Previous process exit") && (m.contains("reason=ANR") || m.contains("reason=CRASH")))) crashes += e
        if (m.startsWith("Database migrated") || m.startsWith("Database created") || m.startsWith("Database reset")) lastDbEvent = e
        if (nowMs - e.epochMs <= TIMELINE_MS &&
            (e.level == 'W' || e.level == 'E' || e.tag in TIMELINE_TAGS || m.startsWith("SESSION_START"))) timeline += e
        if ((e.level == 'W' || e.level == 'E') && nowMs - e.epochMs <= DAY_MS) {
            val key = "${e.level} ${e.tag} ${normalize(m)}"
            warnCounts24h[key] = (warnCounts24h[key] ?: 0) + 1
            warnFirstText.putIfAbsent(key, "${e.level} ${e.tag} $m")
        }
    }

    fun finish(): LogIndex {
        sessions.sortBy { it.epochMs }; crashes.sortByDescending { it.epochMs }; exits.sortBy { it.epochMs }
        timeline.sortBy { it.epochMs }
        return this
    }

    companion object {
        const val TIMELINE_MS = 2L * 3_600_000L
        const val DAY_MS = 24L * 3_600_000L
        val TIMELINE_TAGS = setOf("LIFECYCLE", "NAV", "RECEIVER", "CORRECTIONS", "CAPTURE")

        /** Numbers → "#" so "over 10 s (12.0 s)" and "(14.0 s)" count as one message. */
        fun normalize(m: String): String = m.replace(Regex("\\d+(\\.\\d+)?"), "#")

        fun build(files: List<File>, nowMs: Long): LogIndex {
            val idx = LogIndex(nowMs)
            files.forEach { f -> runCatching { f.bufferedReader(Charsets.UTF_8).useLines { forEachEntry(it, idx::add) } } }
            return idx.finish()
        }

        /** Streams [lines] as entries (continuation lines attached, max 40 kept per entry). */
        fun forEachEntry(lines: Sequence<String>, sink: (LogEntry) -> Unit) {
            var pending: LogEntry? = null
            var cont: MutableList<String>? = null
            for (line in lines) {
                val p = LogLineFormat.parse(line)
                if (p == null) {
                    if (pending != null) { if (cont == null) cont = mutableListOf(); if (cont.size < 40) cont.add(line) }
                    continue
                }
                pending?.let { sink(if (cont != null) it.copy(continuation = cont) else it) }
                pending = LogEntry(p.epochMs, p.level, p.tag, p.thread, p.message, line)
                cont = null
            }
            pending?.let { sink(if (cont != null) it.copy(continuation = cont) else it) }
        }
    }
}