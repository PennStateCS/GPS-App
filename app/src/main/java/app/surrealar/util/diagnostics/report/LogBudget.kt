package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import java.io.File
import java.util.BitSet

/**
 * Keeps the copied logs within a byte budget without losing the evidence that matters. Used only when
 * the logs are larger than the budget.
 *
 * Pass 1 ([onEntry], fed by the [LogIndex] pass) records each entry's time, size and priority:
 *  - P0 crash/ANR evidence (fatal lines + their stack trace, exit reasons, main-thread blocks),
 *  - P1 inside a marked problem's window (5 min before to 2 min after),
 *  - P2 errors from the last 7 days (at most a quarter of the budget, newest first),
 *  - P3 everything else, newest first, until the budget is full.
 * [plan] picks entries in that order, then adds one marker line per omitted span and, if the markers
 * push it over, drops the oldest low-priority picks until it fits. Pass 2 ([FilePlan.filter]) copies a
 * file entry by entry and writes `… [N lines omitted <from> → <to>] …` where a span was left out.
 *
 * Budget = source bytes (95 % of [budgetBytes] for headroom, since redaction can lengthen a line).
 * Lines appended after the plan (the export's own log lines) are kept.
 */
class LogBudget(private val nowMs: Long, private val budgetBytes: Long, private val incidentTimes: List<Long> = emptyList()) {

    private class Scan(val file: File) {
        var n = 0
        var epochs = LongArray(1024)
        var bytes = IntArray(1024)
        var lines = IntArray(1024)
        var prio = ByteArray(1024)
        var preambleBytes = 0L
        var preambleLines = 0
        fun add(epoch: Long, b: Long, l: Int, p: Int) {
            if (n == epochs.size) {
                val c = n * 2
                epochs = epochs.copyOf(c); bytes = bytes.copyOf(c); lines = lines.copyOf(c); prio = prio.copyOf(c)
            }
            epochs[n] = epoch; bytes[n] = b.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); lines[n] = l; prio[n] = p.toByte(); n++
        }
    }

    private val scans = LinkedHashMap<String, Scan>()

    fun onEntry(file: File, entry: LogEntry?, lines: Int, bytes: Long) {
        val s = scans.getOrPut(file.path) { Scan(file) }
        if (entry == null) { s.preambleBytes += bytes; s.preambleLines += lines; return }
        s.add(entry.epochMs, bytes, lines, priority(entry))
    }

    private fun priority(e: LogEntry): Int = when {
        LogIndex.isCrash(e) || e.message.startsWith("Main thread blocked") || e.message.startsWith("Main thread still blocked") -> P_CRASH
        incidentTimes.any { e.epochMs >= it - LogIndex.INCIDENT_BEFORE_MS && e.epochMs <= it + LogIndex.INCIDENT_AFTER_MS } -> P_INCIDENT
        e.level == 'E' && nowMs - e.epochMs <= LogIndex.WEEK_MS -> P_ERROR
        else -> P_REST
    }

    /** What to keep of one file, and the marker line at the first entry of each omitted span. */
    class FilePlan(
        val entryCount: Int,
        private val keep: BitSet,
        private val markers: Map<Int, String>,
        val originalBytes: Long,
        val includedBytes: Long,
        val originalSpan: String?,
        val includedSpan: String?,
        val omittedSpans: Int,
        val omittedLines: Long,
    ) {
        val reduced: Boolean get() = omittedSpans > 0

        fun note(reason: String) = TruncationNote(reason, originalSpan, includedSpan, omittedSpans, omittedLines)

        /** Pass 2: entry boundaries are found exactly as in pass 1 (a line that parses starts an entry). */
        fun filter(): ZipBuilder.LineFilter = object : ZipBuilder.LineFilter {
            var idx = -1
            override fun onLine(line: String, emit: (String) -> Unit) {
                val header = LogLineFormat.parse(line) != null
                if (header) idx++
                when {
                    idx < 0 || idx >= entryCount -> emit(line)          // preamble, or written after the plan
                    keep[idx] -> emit(line)
                    header -> markers[idx]?.let(emit)
                }
            }
        }
    }

    /** Plans every scanned file. Total kept bytes + markers ≤ 95 % of the budget, unless P0 alone is larger. */
    fun plan(): Map<File, FilePlan> {
        val list = scans.values.toList()
        val limit = budgetBytes * 95 / 100 - list.sumOf { it.preambleBytes }
        val keep = list.map { BitSet(it.n) }
        var used = 0L
        // Candidates per priority, newest first.
        for (p in P_CRASH..P_REST) {
            val cands = ArrayList<Long>()                 // (fileIndex shl 32) or entryIndex
            list.forEachIndexed { fi, s -> for (i in 0 until s.n) if (s.prio[i].toInt() == p) cands += (fi.toLong() shl 32) or i.toLong() }
            cands.sortByDescending { list[(it ushr 32).toInt()].epochs[(it and 0xffffffffL).toInt()] }
            val cap = if (p == P_ERROR) minOf(limit - used, limit / 4) else limit - used
            var classUsed = 0L
            for (c in cands) {
                val s = list[(c ushr 32).toInt()]; val i = (c and 0xffffffffL).toInt()
                val b = s.bytes[i].toLong()
                if (classUsed + b > cap) { if (p == P_REST) break else continue }
                keep[(c ushr 32).toInt()].set(i); classUsed += b
            }
            used += classUsed
        }
        // Markers can push it over: drop the oldest kept entries of the lowest priority first.
        var guard = 0
        while (guard++ < 64) {
            val total = list.indices.sumOf { fi -> keptBytes(list[fi], keep[fi]) + markerBytes(list[fi], keep[fi]) }
            val over = total - limit
            if (over <= 0) break
            if (!dropOldest(list, keep, over + 512)) break
        }
        return list.indices.associate { fi -> list[fi].file to toPlan(list[fi], keep[fi]) }
    }

    private fun dropOldest(list: List<Scan>, keep: List<BitSet>, bytes: Long): Boolean {
        for (p in P_REST downTo P_CRASH) {
            val kept = ArrayList<Long>()
            list.forEachIndexed { fi, s -> var i = keep[fi].nextSetBit(0); while (i >= 0) { if (s.prio[i].toInt() == p) kept += (fi.toLong() shl 32) or i.toLong(); i = keep[fi].nextSetBit(i + 1) } }
            if (kept.isEmpty()) continue
            kept.sortBy { list[(it ushr 32).toInt()].epochs[(it and 0xffffffffL).toInt()] }
            var freed = 0L
            for (c in kept) {
                if (freed >= bytes) break
                val fi = (c ushr 32).toInt(); val i = (c and 0xffffffffL).toInt()
                keep[fi].clear(i); freed += list[fi].bytes[i]
            }
            return true
        }
        return false
    }

    private fun keptBytes(s: Scan, k: BitSet): Long { var t = 0L; var i = k.nextSetBit(0); while (i >= 0) { t += s.bytes[i]; i = k.nextSetBit(i + 1) }; return t }

    private fun gaps(s: Scan, k: BitSet): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < s.n) {
            if (k[i]) { i++; continue }
            val start = i
            while (i < s.n && !k[i]) i++
            out += start until i
        }
        return out
    }

    private fun marker(s: Scan, r: IntRange): String {
        var lines = 0L
        for (i in r) lines += s.lines[i]
        return "… [$lines lines omitted ${LogLineFormat.timestamp(s.epochs[r.first])} → ${LogLineFormat.timestamp(s.epochs[r.last])}] …"
    }

    private fun markerBytes(s: Scan, k: BitSet): Long = gaps(s, k).sumOf { LogIndex.utf8Length(marker(s, it)) + 1L }

    private fun toPlan(s: Scan, k: BitSet): FilePlan {
        val gaps = gaps(s, k)
        val markers = gaps.associate { it.first to marker(s, it) }
        val original = s.preambleBytes + (0 until s.n).sumOf { s.bytes[it].toLong() }
        val included = s.preambleBytes + keptBytes(s, k) + markers.values.sumOf { LogIndex.utf8Length(it) + 1L }
        fun span(a: Long?, b: Long?) = if (a == null || b == null) null else "${LogLineFormat.timestamp(a)} → ${LogLineFormat.timestamp(b)}"
        val first = k.nextSetBit(0).takeIf { it >= 0 }
        val last = k.previousSetBit(s.n - 1).takeIf { it >= 0 }
        return FilePlan(
            entryCount = s.n, keep = k, markers = markers,
            originalBytes = original, includedBytes = included,
            originalSpan = if (s.n == 0) null else span(s.epochs.take(s.n).minOrNull(), s.epochs.take(s.n).maxOrNull()),
            includedSpan = span(first?.let { s.epochs[it] }, last?.let { s.epochs[it] }),
            omittedSpans = gaps.size,
            omittedLines = gaps.sumOf { r -> r.sumOf { s.lines[it].toLong() } },
        )
    }

    companion object {
        const val P_CRASH = 0
        const val P_INCIDENT = 1
        const val P_ERROR = 2
        const val P_REST = 3
        const val REASON = "reduced to fit the log size budget (crash, marked-problem and recent error lines kept first, then the newest)"
    }
}
