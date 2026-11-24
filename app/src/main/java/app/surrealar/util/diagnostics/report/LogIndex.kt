package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.DiagEvents
import app.surrealar.util.diagnostics.LogLineFormat
import java.io.File

/** One parsed log line plus the stack-trace lines under it. [source] = the log file it came from. */
data class LogEntry(
    val epochMs: Long, val level: Char, val tag: String, val thread: String, val message: String,
    val raw: String, val continuation: List<String> = emptyList(), val source: String = "",
)

/** The entries around one marked problem, chronological; [omitted] = window entries left out by the cap. */
data class IncidentWindow(val entries: List<LogEntry>, val omitted: Int)

/**
 * One streaming pass over the event and error logs that keeps only what the report needs (the logs
 * can be ~25 MB, so nothing else is held in memory). Errors appear in both logs; they are counted once.
 *
 * [incidentTimes] are the marked problems' times: every entry from [INCIDENT_BEFORE_MS] before to
 * [INCIDENT_AFTER_MS] after each one is collected in the same pass (bounded, see [incidentWindows]).
 */
class LogIndex(val nowMs: Long, val incidentTimes: List<Long> = emptyList()) {

    var firstMs: Long? = null; private set
    var lastMs: Long? = null; private set
    val sessions = mutableListOf<LogEntry>()
    val crashes = mutableListOf<LogEntry>()          // fatal uncaught exceptions + ANR/crash exit reasons
    val exits = mutableListOf<LogEntry>()            // "Previous process exit" lines
    val timeline = mutableListOf<LogEntry>()         // last 2 h, see [TIMELINE_TAGS]; at most [MAX_TIMELINE]
    var timelineDropped = 0; private set
    val warnCounts24h = HashMap<String, Int>()       // normalized W/E message → count
    val warnFirstText = HashMap<String, String>()
    var lastDbEvent: LogEntry? = null; private set
    /** Entries the findings rules recognise (last 7 days), chronological after [finish]; at most [MAX_SIGNALS]. */
    val signals = mutableListOf<Signal>()
    var signalsDropped = 0; private set
    /** One per [incidentTimes] entry, same order (filled by [finish]). */
    var incidentWindows: List<IncidentWindow> = emptyList(); private set
    private val seenErrors = HashSet<String>()
    private val windowKey = List(incidentTimes.size) { mutableListOf<LogEntry>() }
    private val windowOther = List(incidentTimes.size) { mutableListOf<LogEntry>() }
    private val windowTotal = IntArray(incidentTimes.size)

    fun add(e: LogEntry) {
        if (e.level == 'E' && !seenErrors.add(e.raw)) return
        if (firstMs == null || e.epochMs < firstMs!!) firstMs = e.epochMs
        if (lastMs == null || e.epochMs > lastMs!!) lastMs = e.epochMs
        val m = e.message
        if (m.startsWith("SESSION_START")) sessions += e
        if (m.startsWith("Previous process exit")) exits += e
        if (isCrash(e)) crashes += e
        if (m.startsWith("Database migrated") || m.startsWith("Database created") || m.startsWith("Database reset")) lastDbEvent = e
        val key = isKey(e)
        if (nowMs - e.epochMs <= TIMELINE_MS && (key || e.tag in TIMELINE_TAGS)) {
            timeline += e
            if (timeline.size > MAX_TIMELINE * 2) trim(timeline, MAX_TIMELINE)
        }
        if ((e.level == 'W' || e.level == 'E') && nowMs - e.epochMs <= DAY_MS) {
            val k = "${e.level} ${e.tag} ${normalize(m)}"
            warnCounts24h[k] = (warnCounts24h[k] ?: 0) + 1
            warnFirstText.putIfAbsent(k, "${e.level} ${e.tag} $m")
        }
        if (nowMs - e.epochMs <= WEEK_MS) ReportSignals.classify(e)?.let { kind ->
            signals += Signal(kind, e)
            if (signals.size > MAX_SIGNALS * 2) {
                signals.sortBy { it.entry.epochMs }
                val drop = signals.size - MAX_SIGNALS
                signalsDropped += drop
                signals.subList(0, drop).clear()
            }
        }
        for (i in incidentTimes.indices) {
            val t = incidentTimes[i]
            if (e.epochMs < t - INCIDENT_BEFORE_MS || e.epochMs > t + INCIDENT_AFTER_MS) continue
            windowTotal[i]++
            val list = if (key || e.tag in INCIDENT_KEY_TAGS) windowKey[i] else windowOther[i]
            list += e
            if (list.size > MAX_INCIDENT_RAW) {
                list.sortBy { kotlin.math.abs(it.epochMs - t) }
                list.subList(MAX_INCIDENT_RAW / 2, list.size).clear()
            }
        }
    }

    /** Drops the oldest ordinary lines first, then the oldest key lines, down to [max]. */
    private fun trim(list: MutableList<LogEntry>, max: Int) {
        if (list.size <= max) return
        list.sortBy { it.epochMs }
        var excess = list.size - max
        val it = list.iterator()
        while (excess > 0 && it.hasNext()) if (!isKey(it.next())) { it.remove(); excess--; timelineDropped++ }
        if (excess > 0) { list.subList(0, excess).clear(); timelineDropped += excess }
    }

    fun finish(): LogIndex {
        sessions.sortBy { it.epochMs }; crashes.sortByDescending { it.epochMs }; exits.sortBy { it.epochMs }
        trim(timeline, MAX_TIMELINE)
        timeline.sortBy { it.epochMs }
        signals.sortBy { it.entry.epochMs }
        incidentWindows = incidentTimes.indices.map { i ->
            val t = incidentTimes[i]
            val byDistance = compareBy<LogEntry> { kotlin.math.abs(it.epochMs - t) }
            val key = windowKey[i].sortedWith(byDistance).take(MAX_INCIDENT_LINES)
            val other = windowOther[i].sortedWith(byDistance).take(MAX_INCIDENT_LINES - key.size)
            val kept = (key + other).sortedBy { it.epochMs }
            IncidentWindow(kept, windowTotal[i] - kept.size)
        }
        return this
    }

    /** The current session id: the newest SESSION_START's `session=` value. */
    fun lastSessionId(): String? = sessions.lastOrNull()?.let { sessionOf(it.message) }

    companion object {
        const val TIMELINE_MS = 2L * 3_600_000L
        const val DAY_MS = 24L * 3_600_000L
        const val WEEK_MS = 7 * DAY_MS
        /** An incident's timeline: this long before the mark … */
        const val INCIDENT_BEFORE_MS = 5L * 60_000L
        /** … to this long after it. */
        const val INCIDENT_AFTER_MS = 2L * 60_000L
        /** Lines shown per incident (warnings, errors, state changes and key tags first). */
        const val MAX_INCIDENT_LINES = 200
        private const val MAX_INCIDENT_RAW = 2_000
        const val MAX_TIMELINE = 1_500
        const val MAX_SIGNALS = 4_000

        val TIMELINE_TAGS = setOf("LIFECYCLE", "NAV", "RECEIVER", "CORRECTIONS", "CAPTURE",
            "AR", "MODEL", "GNSS", "DATA", "ERROR", "DIAG")
        /** Tags kept first in an incident window when it is over the cap. */
        private val INCIDENT_KEY_TAGS = setOf("RECEIVER", "CORRECTIONS", "GNSS", "AR", "MODEL", "DIAG", "ERROR")

        /** Warnings, errors, DiagEvents state changes, session starts and incident marks. */
        fun isKey(e: LogEntry): Boolean = e.level == 'W' || e.level == 'E' ||
            e.message.startsWith("SESSION_START") || e.message.startsWith("INCIDENT_MARKED") ||
            DiagEvents.parse(e.message) != null

        /** A fatal uncaught exception, or Android's record of an earlier process ending in a crash or ANR. */
        fun isCrash(e: LogEntry): Boolean = (e.level == 'E' && e.message.startsWith("Uncaught exception (fatal)")) ||
            (e.message.startsWith("Previous process exit") && (e.message.contains("reason=ANR") || e.message.contains("reason=CRASH")))

        private val SESSION = Regex("""\bsession=(\S+)""")
        fun sessionOf(message: String): String? = SESSION.find(message)?.groupValues?.get(1)

        /** Numbers → "#" so "over 10 s (12.0 s)" and "(14.0 s)" count as one message. */
        fun normalize(m: String): String = m.replace(Regex("\\d+(\\.\\d+)?"), "#")

        /**
         * A file that cannot be read is skipped and reported to [onFileError] (export-health.txt).
         * [onEntrySize] sees every entry of every file with its line count and UTF-8 bytes (null entry =
         * lines before the first timestamp), for the log budget planner in the same pass.
         */
        fun build(
            files: List<File>, nowMs: Long,
            incidentTimes: List<Long> = emptyList(),
            onEntrySize: ((File, LogEntry?, Int, Long) -> Unit)? = null,
            onFileError: (File, Throwable) -> Unit = { _, _ -> },
        ): LogIndex {
            val idx = LogIndex(nowMs, incidentTimes)
            files.forEach { f ->
                runCatching {
                    f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        forEachEntrySized(lines, f.name) { e, n, bytes ->
                            if (e != null) idx.add(e)
                            onEntrySize?.invoke(f, e, n, bytes)
                        }
                    }
                }.onFailure { onFileError(f, it) }
            }
            return idx.finish()
        }

        /** Streams [lines] as entries (continuation lines attached, max 40 kept per entry). */
        fun forEachEntry(lines: Sequence<String>, sink: (LogEntry) -> Unit) =
            forEachEntrySized(lines, "") { e, _, _ -> if (e != null) sink(e) }

        /**
         * Like [forEachEntry], also giving each entry's full line count and UTF-8 bytes (newlines
         * included). Lines before the first timestamped line come first as one null entry.
         */
        fun forEachEntrySized(lines: Sequence<String>, source: String, sink: (LogEntry?, Int, Long) -> Unit) {
            var pending: LogEntry? = null
            var cont: MutableList<String>? = null
            var n = 0
            var bytes = 0L
            var preamble = true
            for (line in lines) {
                val size = utf8Length(line) + 1L
                val p = LogLineFormat.parse(line)
                if (p == null) {
                    if (pending != null && cont == null) cont = mutableListOf()
                    if (pending != null && cont!!.size < 40) cont.add(line)
                    n++; bytes += size
                    continue
                }
                if (pending != null) sink(if (cont != null) pending.copy(continuation = cont) else pending, n, bytes)
                else if (preamble && n > 0) sink(null, n, bytes)
                preamble = false
                pending = LogEntry(p.epochMs, p.level, p.tag, p.thread, p.message, line, source = source)
                cont = null; n = 1; bytes = size
            }
            if (pending != null) sink(if (cont != null) pending.copy(continuation = cont) else pending, n, bytes)
            else if (n > 0) sink(null, n, bytes)
        }

        /** UTF-8 byte length without allocating. */
        fun utf8Length(s: String): Int {
            var count = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.code < 0x80 -> count++
                    c.code < 0x800 -> count += 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { count += 4; i++ }
                    else -> count += 3
                }
                i++
            }
            return count
        }
    }
}
