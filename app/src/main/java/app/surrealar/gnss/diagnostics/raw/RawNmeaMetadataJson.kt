package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.SentenceType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The companion `.json` written next to each `.nmea` recording (schema [SCHEMA_VERSION]). Hand-written
 * (no org.json) so it is identical in JVM tests and on the device. Key order is fixed.
 *
 * Written once when the recording starts (`complete=false`, `terminationReason=null`), and rewritten when it
 * ends. A recording interrupted by process death therefore keeps `complete=false` with no termination reason.
 */
object RawNmeaMetadataJson {
    const val SCHEMA_VERSION = 1
    const val KIND = "surrealar.raw_nmea_recording"

    data class Input(
        val nmeaFileName: String,
        val origin: RawNmeaOrigin,
        val meta: RawNmeaSourceMetadata,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val durationMs: Long?,
        val requestedDurationS: Int?,
        val zone: ZoneId,
        val stats: RawNmeaStatsSnapshot,
        val maxFileBytes: Long,
        val termination: RawNmeaTermination?,
        val writerError: String?,
        val complete: Boolean,
    )

    fun write(i: Input): String {
        val utc = DateTimeFormatter.ISO_INSTANT
        val local = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun localOf(ms: Long) = local.format(Instant.ofEpochMilli(ms).atZone(i.zone))
        val s = i.stats
        return JsonOut().run {
            sb.append("{\n")
            field("schemaVersion", SCHEMA_VERSION)
            field("kind", KIND)
            field("nmeaFile", i.nmeaFileName)
            field("appVersion", i.meta.appVersion)
            field("startedAtUtc", utc.format(Instant.ofEpochMilli(i.startedAtMs)))
            field("endedAtUtc", i.endedAtMs?.let { utc.format(Instant.ofEpochMilli(it)) })
            field("startedAtLocal", localOf(i.startedAtMs))
            field("endedAtLocal", i.endedAtMs?.let { localOf(it) })
            field("deviceTimeZone", i.zone.id)
            field("durationMs", i.durationMs)
            field("mode", if (i.requestedDurationS == null) "MANUAL" else "TIMED")
            field("requestedDurationS", i.requestedDurationS)
            obj("source") {
                field("provider", i.origin.name)
                field("connectionType", i.origin.connectionType)
                field("live", true)
                field("tapPoint", i.origin.tapPoint, last = true)
            }
            obj("receiver") {
                field("profile", i.meta.profileKey)
                field("profileLabel", i.meta.profileLabel)
                field("reportedModel", i.meta.reportedModel)
                field("reportedFirmware", i.meta.reportedFirmware)
                field("reportedSerial", i.meta.reportedSerial, last = true)
            }
            if (i.meta.compensatedOutput == null) {
                field("compensatedOutput", null as String?)
            } else {
                obj("compensatedOutput") {
                    field("setting", i.meta.compensatedOutput)
                    field("confirmed", i.meta.compensatedOutput != "UNKNOWN", last = true)
                }
            }
            obj("format") {
                field("encoding", "US-ASCII/UTF-8 text, one sentence per line")
                field("lineTerminator", "CRLF")
                field("byteExact", false)
                field("notPreserved", "original line terminators; leading/trailing whitespace; non-'$' lines and a partial last line (TCP); bytes that are not valid UTF-8", last = true)
            }
            obj("counts") {
                SentenceType.entries.forEachIndexed { idx, t ->
                    field(t.name, s.count(t), last = idx == SentenceType.entries.size - 1)
                }
            }
            obj("totals") {
                field("sentences", s.sentences)
                field("bytes", s.bytes, last = true)
            }
            obj("checksum") {
                field("valid", s.checksumValid)
                field("invalid", s.checksumInvalid, last = true)
            }
            field("malformed", s.malformed)
            field("droppedLines", s.droppedLines)
            field("maxFileBytes", i.maxFileBytes)
            field("terminationReason", i.termination?.name)
            field("writerError", i.writerError)
            field("complete", i.complete, last = true)
            sb.append("}\n")
            sb.toString()
        }
    }

    /** Minimal ordered JSON emitter (one per [write] call, so concurrent writes never share state). */
    private class JsonOut {
        val sb = StringBuilder()
        private var indent = ""

        private fun key(k: String) { sb.append("  ").append(indent).append('"').append(k).append("\": ") }
        private fun end(last: Boolean) { sb.append(if (last) "\n" else ",\n") }

        fun field(k: String, v: String?, last: Boolean = false) {
            key(k); if (v == null) sb.append("null") else sb.append(quote(v)); end(last)
        }
        fun field(k: String, v: Long?, last: Boolean = false) { key(k); sb.append(v?.toString() ?: "null"); end(last) }
        fun field(k: String, v: Int?, last: Boolean = false) = field(k, v?.toLong(), last)
        fun field(k: String, v: Boolean, last: Boolean = false) { key(k); sb.append(v); end(last) }

        /** A nested object; always followed by another field at this level. */
        fun obj(k: String, body: JsonOut.() -> Unit) {
            key(k); sb.append("{\n")
            val prev = indent
            indent = "$prev  "
            try { body() } finally { indent = prev }
            sb.append("  ").append(prev).append("},\n")
        }
    }

    /** JSON string literal with the mandatory escapes. */
    fun quote(v: String): String = buildString {
        append('"')
        for (c in v) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    /** What the recordings list needs from a companion file (our own format; tolerant of a missing file). */
    data class Summary(val complete: Boolean, val termination: String?, val droppedLines: Long?, val provider: String?)

    fun readSummary(json: String): Summary {
        fun str(k: String) = Regex("\"$k\": \"([^\"]*)\"").find(json)?.groupValues?.get(1)
        val complete = Regex("\"complete\": (true|false)").find(json)?.groupValues?.get(1) == "true"
        val dropped = Regex("\"droppedLines\": (\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
        return Summary(complete, str("terminationReason"), dropped, str("provider"))
    }
}
