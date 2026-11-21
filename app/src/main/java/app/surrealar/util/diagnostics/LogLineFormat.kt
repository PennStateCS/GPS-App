package app.surrealar.util.diagnostics

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The diagnostic log line format, in one place for the writer and every reader:
 *
 *     2026-11-21T14:03:07.412-05:00 W RECEIVER [main] TCP: no data for 10000ms
 *
 * ISO-8601 local time with offset and milliseconds, level (D/I/W/E), tag, thread, message. A stack
 * trace follows on the next lines (they don't start with a timestamp, so readers attach them to the
 * line above).
 */
object LogLineFormat {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val LINE = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2})) ([DIWE]) (\S+) \[([^\]]*)] (.*)$""")

    data class Parsed(val time: OffsetDateTime, val level: Char, val tag: String, val thread: String, val message: String) {
        val epochMs: Long get() = time.toInstant().toEpochMilli()
    }

    fun timestamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(java.time.Instant.ofEpochMilli(epochMs).atZone(zone))

    fun format(epochMs: Long, level: Char, tag: String, thread: String, message: String, zone: ZoneId = ZoneId.systemDefault()): String =
        "${timestamp(epochMs, zone)} $level $tag [$thread] $message"

    /** Parses one line; null for continuation lines (stack traces) and anything else. */
    fun parse(line: String): Parsed? {
        val m = LINE.matchEntire(line) ?: return null
        val (ts, level, tag, thread, message) = m.destructured
        val time = try { OffsetDateTime.parse(ts, TIME) } catch (_: DateTimeParseException) { return null }
        return Parsed(time, level[0], tag, thread, message)
    }
}