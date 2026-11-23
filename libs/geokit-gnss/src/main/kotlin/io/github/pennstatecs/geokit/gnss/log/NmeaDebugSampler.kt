package io.github.pennstatecs.geokit.gnss.log

/**
 * Collects the latest raw NMEA sentence of each kind (per talker, GSA per system ID, first GSV page
 * per signal) and dumps them together every [intervalMs] as one [GnssLog.event] trace under [area].
 * Shared by the internal and external sources so both streams can be inspected the same way. Only
 * active while [GnssLog.enabled] (debug builds). Not thread-safe: one per source/reader.
 */
public class NmeaDebugSampler(
    private val area: String,
    private val intervalMs: Long = GnssLog.DEFAULT_INTERVAL_MS,
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val latest = LinkedHashMap<String, String>()
    private var windowStartMs = 0L

    /** Feed every raw line; [header] supplies extra context printed above the sample (e.g. counts). */
    public fun accept(line: String, header: () -> String = { "" }) {
        if (!GnssLog.enabled) return
        val t = line.trim()
        if (t.length < 7 || t[0] != '$') return
        val comma = t.indexOf(',').takeIf { it > 0 } ?: return
        val head = t.substring(1, comma)
        val tag = head.takeLast(3)
        val key = when (tag) {
            "GSV" -> {
                val f = t.split(',')
                if (f.getOrNull(2) != "1") return               // first page only (has the totals)
                "$head#sig${f.lastOrNull()?.substringBefore('*')}"
            }
            "GSA" -> "$head#sys${t.split(',').getOrNull(18)?.substringBefore('*').orEmpty()}"
            "GGA", "RMC", "GST", "ZDA", "VTG", "GLL" -> head
            else -> "other:$head"                               // proprietary / unknown: keep one each
        }
        latest[key] = t
        val now = monotonicNowMs()
        if (windowStartMs == 0L) windowStartMs = now
        if (now - windowStartMs < intervalMs) return
        val h = header()
        GnssLog.event(area) {
            buildString {
                append("---- raw sample").append(if (h.isNotBlank()) " ($h)" else "").append(" ----")
                latest.values.forEach { append('\n').append(it) }
            }
        }
        latest.clear()
        windowStartMs = now
    }
}
