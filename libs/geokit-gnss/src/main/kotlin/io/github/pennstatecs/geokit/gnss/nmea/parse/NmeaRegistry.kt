package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import io.github.pennstatecs.geokit.gnss.log.GnssLog
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
 * Diagnostics: per-sentence parse failures are counted. Checksum failures also get a debug-build line
 * with the sentence id (talker + type) and the running count — never the payload, which can hold a
 * position. A compact summary is emitted to [GnssLog.diagnostic] at most once per
 * [SUMMARY_INTERVAL_MS] when there were failures, and on demand via [logParseSummary] (e.g. when a
 * source stops). Summaries carry counts and recent failed sentence TYPES only — never raw payloads.
 */
public class NmeaRegistry(
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

    /**
     * Parses one NMEA line into its sentence, or null if it is blank, malformed, fails the checksum
     * rules, or has no registered parser (failures are counted for the periodic summary). Never throws.
     * Thread-safe: may be called from several reader threads.
     */
    public fun parse(line: String): NmeaSentence? {
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
                    val n = wChecksum.incrementAndGet()
                    // Sentence id and count only — the payload can hold a position (debug builds only).
                    GnssLog.d("NmeaRegistry") { "Checksum mismatch: ${sentenceId(payload)} (#$n this window)" }
                    return null
                }
            } else if (requireChecksum) {
                // Checksum missing but required: reject sentence
                val n = wChecksum.incrementAndGet()
                GnssLog.d("NmeaRegistry") { "Missing required checksum: ${sentenceId(payload)} (#$n this window)" }
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
    public fun logParseSummary(reason: String) {
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
        drainSummaryLine(reason)?.let { GnssLog.diagnostic("NMEA", it) }
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

    /** Talker + sentence type (e.g. "GNGGA") from a payload, for logs that must not carry the data. */
    private fun sentenceId(payload: String): String =
        payload.substringBefore(',').take(16).filter { it.isLetterOrDigit() }.ifEmpty { "?" }
}
