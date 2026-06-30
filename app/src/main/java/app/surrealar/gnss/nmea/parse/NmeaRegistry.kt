package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.NmeaSentence
import app.surrealar.util.DiagnosticsLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Central NMEA sentence parser registry.
 *
 * @param parsers Map of sentence type (e.g., "GGA", "RMC") to parser implementation
 * @param verifyChecksum If true, validates checksums when present. If [requireChecksum] is also
 *                       true, rejects sentences without checksums entirely.
 * @param requireChecksum If true (and [verifyChecksum] is true), rejects sentences that lack a
 *                        checksum. Set to false for dev/replay scenarios where test data may
 *                        omit checksums.
 *
 * Diagnostics: per-sentence parse failures are counted (never logged individually — that would be
 * per-sentence spam). A compact summary is emitted to [DiagnosticsLogger] at most once per
 * [SUMMARY_INTERVAL_MS] when there were failures, and on demand via [logParseSummary] (e.g. when a
 * source stops). Summaries carry counts and recent failed sentence TYPES only — never raw payloads.
 */
class NmeaRegistry(
    private val parsers: Map<String, SentenceParser<out NmeaSentence>>,
    private val verifyChecksum: Boolean = true,
    private val requireChecksum: Boolean = false
) {
    // Windowed counters (reset on each summary emit). Atomic — parse() is called from the internal
    // main looper and the external TCP reader thread.
    private val wConsidered = AtomicLong(0)
    private val wMalformed  = AtomicLong(0)
    private val wChecksum   = AtomicLong(0)
    private val wUnknown    = AtomicLong(0)
    private val wParseFail  = AtomicLong(0)
    private val recentUnknownTypes = LinkedHashSet<String>()
    private val summaryLock = Any()
    @Volatile private var lastSummaryMs = System.currentTimeMillis()

    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null            // benign blank — not counted as a failure
        wConsidered.incrementAndGet()
        maybeEmitSummary()
        if (trimmed[0] != '$') { wMalformed.incrementAndGet(); return null }

        val starIdx = trimmed.indexOf('*')
        val payloadEnd = if (starIdx >= 0) starIdx else trimmed.length
        if (payloadEnd <= 1) { wMalformed.incrementAndGet(); return null }

        val payload = trimmed.substring(1, payloadEnd)

        // Checksum validation
        if (verifyChecksum) {
            if (starIdx >= 0 && starIdx + 3 <= trimmed.length) {
                // Checksum present: validate it
                val csHex = trimmed.substring(starIdx + 1, starIdx + 3)
                val expected = csHex.toIntOrNull(16) ?: run { wChecksum.incrementAndGet(); return null }
                if (xorChecksum(payload) != expected) {
                    android.util.Log.w("NmeaRegistry", "Checksum mismatch: $trimmed")
                    wChecksum.incrementAndGet()
                    return null
                }
            } else if (requireChecksum) {
                // Checksum missing but required: reject sentence
                android.util.Log.w("NmeaRegistry", "Missing required checksum: $trimmed")
                wChecksum.incrementAndGet()
                return null
            }
            // else: checksum missing but not required; allow sentence
        }

        val fields = payload.split(',')
        if (fields.isEmpty()) { wMalformed.incrementAndGet(); return null }

        val talkerAndTag = fields[0]
        if (talkerAndTag.length < 5) { wMalformed.incrementAndGet(); return null } // e.g., "GP" + "GGA"

        val talker = talkerAndTag.substring(0, 2).uppercase()
        val tag    = talkerAndTag.substring(2).uppercase()

        val parser = parsers[tag] ?: run {
            wUnknown.incrementAndGet()
            synchronized(recentUnknownTypes) {
                recentUnknownTypes.add(tag)
                while (recentUnknownTypes.size > MAX_RECENT_TYPES) {
                    recentUnknownTypes.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
                }
            }
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val result = (parser as SentenceParser<NmeaSentence>).parse(talker, fields)
        if (result == null) wParseFail.incrementAndGet()
        return result
    }

    /** Forces a parse summary to the diagnostic log now (e.g. when a source stops). */
    fun logParseSummary(reason: String) {
        synchronized(summaryLock) {
            lastSummaryMs = System.currentTimeMillis()
            emitSummaryLocked(reason)
        }
    }

    private fun maybeEmitSummary() {
        if (System.currentTimeMillis() - lastSummaryMs < SUMMARY_INTERVAL_MS) return
        synchronized(summaryLock) {
            if (System.currentTimeMillis() - lastSummaryMs < SUMMARY_INTERVAL_MS) return
            lastSummaryMs = System.currentTimeMillis()
            emitSummaryLocked("periodic")
        }
    }

    private fun emitSummaryLocked(reason: String) {
        drainSummaryLine(reason)?.let { DiagnosticsLogger.i("NMEA", it) }
    }

    /**
     * Drains the current parse-failure window counters and returns the formatted summary line, or
     * null for a `"periodic"` reason when nothing failed (so periodic windows stay silent). Resets
     * the window as a side effect. `internal` for unit tests — production callers go through the
     * throttled [maybeEmitSummary] / [logParseSummary].
     */
    internal fun drainSummaryLine(reason: String): String? {
        val considered = wConsidered.getAndSet(0)
        val malformed  = wMalformed.getAndSet(0)
        val checksum   = wChecksum.getAndSet(0)
        val unknown    = wUnknown.getAndSet(0)
        val parseFail  = wParseFail.getAndSet(0)
        val failures   = malformed + checksum + unknown + parseFail
        // For the periodic window, stay silent unless something actually failed (avoid noise).
        if (failures == 0L && reason == "periodic") return null
        val types = synchronized(recentUnknownTypes) { recentUnknownTypes.toList() }
        return "parse summary ($reason): considered=$considered " +
            "ok=${considered - failures} malformed=$malformed checksumFail=$checksum " +
            "unknownType=$unknown parseFail=$parseFail recentUnknownTypes=$types"
    }

    private fun xorChecksum(s: String): Int {
        var cs = 0
        for (ch in s) cs = cs xor ch.code
        return cs and 0xFF
    }

    private companion object {
        const val SUMMARY_INTERVAL_MS = 60_000L
        const val MAX_RECENT_TYPES = 8
    }
}
