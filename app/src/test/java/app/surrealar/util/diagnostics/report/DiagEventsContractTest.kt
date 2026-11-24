package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.GnssFeedTracker
import app.surrealar.util.diagnostics.ReceiverLinkTracker
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The contract between the logging call sites and the report: lines produced by the REAL trackers
 * (not hand-copied examples) must be recognised by [ReportSignals] and turned into findings by
 * [ReportFindings]. If either side changes a code or a field value, this fails.
 */
class DiagEventsContractTest {

    /** Report at 15:00 (UTC-5); tracker time 0 is mapped to 14:40, so every line is inside the focus window. */
    private val now = OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli()
    private val base = now - 20 * 60_000L

    private data class Captured(val atMs: Long, val level: String, val tag: String, val message: String)
    private val captured = mutableListOf<Captured>()
    private var clock = 0L

    private val link = ReceiverLinkTracker { level, tag, m -> captured += Captured(clock, level.toString(), tag, m) }
    private val feed = GnssFeedTracker { level, m -> captured += Captured(clock, level.toString(), "GNSS", m) }

    private fun fix(rtk: RtkStatus, diffAge: Double?, valid: Boolean = true) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = if (valid) 41.2345678 else 0.0, lonDeg = if (valid) -76.1234567 else 0.0,
        altEllipsoidalM = 312.345, altMslM = 345.678, geoidSeparationM = -33.0,
        hDop = 0.7, vDop = 1.2, pDop = null, hAccM = 0.014, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 18, satsVisible = 27, diffAgeS = diffAge, speedMps = null, courseDeg = null,
    )

    private fun at(ms: Long, block: () -> Unit) { clock = ms; block() }

    /** Drives both trackers through a realistic field failure; returns the captured lines as log entries. */
    private fun fieldFailure(): List<LogEntry> {
        // Receiver: socket opens but no NMEA (NO_DATA) …
        at(0) { link.onConnectionState("CONNECTING", 0) }
        at(500) { link.onSocketConnected(500) }
        at(10_500) { link.onConnectionState("ERROR", 10_500) }
        // … later it connects and streams, then drops repeatedly (DISCONNECTED/NO_DATA + RECONNECTS burst).
        var t = 20_000L
        repeat(4) {
            at(t) { link.onConnectionState("RECONNECTING", t) }
            at(t + 1_000) { link.onConnectionState("CONNECTED", t + 1_000) }
            var d = t + 1_000; while (d <= t + 30_000) { val dd = d; at(dd) { link.onData(dd) }; d += 200 }
            at(t + 30_100) { link.onConnectionState("ERROR", t + 30_100) }
            t += 60_000
        }
        // GNSS: FIX with fresh corrections, corrections go stale, RTK drops to FLOAT, then fixes lose position, then silence.
        at(t) { feed.onSource("EXTERNAL_TCP", t) }
        fun stream(ms: Long, rtk: RtkStatus, age: Double?, valid: Boolean = true, fixes: Boolean = true) {
            val end = t + ms
            while (t < end) {
                t += 200
                val tt = t
                at(tt) {
                    if (fixes) { feed.onNmea(tt); feed.onFix(fix(rtk, age, valid), tt) }
                    if (tt % 1_000 == 0L) feed.checkFeed(tt)
                }
            }
        }
        stream(10_000, RtkStatus.FIX, 1.0)
        stream(10_000, RtkStatus.FIX, 12.0)
        stream(10_000, RtkStatus.FLOAT, 35.0)
        stream(10_000, RtkStatus.NONE, null, valid = false)
        stream(20_000, RtkStatus.NONE, null, fixes = false)

        val lines = captured.map { c ->
            "${app.surrealar.util.diagnostics.LogLineFormat.timestamp(base + c.atMs)} ${c.level} ${c.tag} [main] ${c.message}"
        }
        val out = mutableListOf<LogEntry>()
        LogIndex.forEachEntrySized(lines.asSequence(), "app-log-current.txt") { e, _, _ -> if (e != null) out += e }
        return out
    }

    @Test fun `every coded line the trackers write is classified`() {
        val entries = fieldFailure()
        val coded = entries.filter { it.message.matches(Regex("^[A-Z_]+ \\S+=.*")) }
        assertTrue("trackers wrote coded lines: $entries", coded.size >= 6)
        val kinds = coded.map { e -> e.message to ReportSignals.classify(e) }
        // Every coded state the report has a rule for maps to a kind (CONNECTING has no rule by design).
        kinds.filter { (m, _) -> !m.startsWith("RECEIVER_LINK state=CONNECTING") }
            .forEach { (m, k) -> assertTrue("unclassified coded line: $m", k != null) }
        val seen = kinds.mapNotNull { it.second }.toSet()
        for (k in listOf(SignalKind.RECEIVER_NO_DATA, SignalKind.RECEIVER_RECONNECTS, SignalKind.RECEIVER_UP,
                SignalKind.RTK_LOST, SignalKind.CORRECTIONS_STALE, SignalKind.GNSS_NO_FIX, SignalKind.GNSS_STALE)) {
            assertTrue("$k not produced by the real trackers: ${kinds.map { it.first }}", k in seen)
        }
    }

    @Test fun `the real tracker lines produce the receiver and GNSS findings`() {
        val es = fieldFailure().sortedBy { it.epochMs }
        val input = FindingsInput(
            nowMs = now,
            signals = es.mapNotNull { e -> ReportSignals.classify(e)?.let { Signal(it, e) } },
            crashes = es.filter(LogIndex::isCrash),
            sessionStarts = emptyList(),
        )
        val rules = ReportFindings.evaluate(input).map { it.rule }
        for (r in listOf("tcp_no_nmea", "repeated_reconnects", "rtk_lost", "corrections_stale")) {
            assertTrue("missing finding $r in $rules", r in rules)
        }
        assertTrue("a GNSS feed finding (no fix or stale) in $rules", "nmea_no_fix" in rules || "gnss_stale" in rules)
        // Evidence quotes the coded lines (with correlation ids), never coordinates.
        val evidence = ReportFindings.evaluate(input).flatMap { it.evidence }.joinToString("\n") { it.text }
        assertTrue(evidence, evidence.contains("conn=c"))
        listOf("41.23", "76.12", "312.3").forEach { assertTrue("coordinate $it in evidence", !evidence.contains(it)) }
        assertEquals(ReportFindings.evaluate(input), ReportFindings.evaluate(input))   // deterministic
    }
}
