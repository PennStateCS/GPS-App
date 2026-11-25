package app.surrealar.ui.settings.categories

import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.SentenceType
import app.surrealar.gnss.diagnostics.raw.RawNmeaAvailability
import app.surrealar.gnss.diagnostics.raw.RawNmeaFiles
import app.surrealar.gnss.diagnostics.raw.RawNmeaOrigin
import app.surrealar.gnss.diagnostics.raw.RawNmeaState
import app.surrealar.gnss.diagnostics.raw.RawNmeaStatsSnapshot
import app.surrealar.gnss.diagnostics.raw.RawNmeaTermination
import java.util.Locale

/** Texts of the Raw NMEA Recording section (pure, unit-tested). */
internal object RawNmeaUiText {

    const val PRIVACY_TITLE = "Raw NMEA recordings"
    const val PRIVACY_MESSAGE =
        "Recordings contain precise coordinates from your receiver and receiver identifiers (profile, and model, " +
            "firmware and serial number when the receiver reports them).\n\n" +
            "They stay on this device, are never uploaded automatically, and leave it only if you share them. " +
            "No passwords, NTRIP credentials or network addresses are recorded."

    fun availability(a: RawNmeaAvailability): String = when (a) {
        is RawNmeaAvailability.Available -> when (a.origin) {
            RawNmeaOrigin.EXTERNAL_TCP -> "Source: external receiver over TCP (live raw NMEA available)"
            RawNmeaOrigin.INTERNAL -> "Source: tablet GNSS (live raw NMEA available)"
        }
        is RawNmeaAvailability.Unavailable -> a.reason
    }

    fun status(state: RawNmeaState, nowMonoMs: Long): String = when (state) {
        RawNmeaState.Idle -> "Not recording"
        is RawNmeaState.Recording -> {
            val elapsed = clock((nowMonoMs - state.startedMonoMs).coerceAtLeast(0))
            val limit = state.durationLimitMs?.let { " / ${clock(it)}" } ?: " (manual)"
            "Recording… $elapsed$limit"
        }
        is RawNmeaState.Finished -> {
            val r = state.result
            val head = if (r.complete) "Finished" else "Finished: INCOMPLETE"
            "$head — ${termination(r.termination)} after ${clock(r.durationMs)}"
        }
        is RawNmeaState.Failed -> "Not recording — ${state.message}"
    }

    /** Red "data lost" line; null when nothing was lost. */
    fun warning(state: RawNmeaState): String? {
        val r = (state as? RawNmeaState.Finished)?.result
        val dropped = when (state) {
            is RawNmeaState.Recording -> state.stats.droppedLines
            is RawNmeaState.Finished -> state.result.stats.droppedLines
            else -> 0L
        }
        return when {
            r?.writerError != null -> "Data lost: the file could not be written (${r.writerError}). This recording is incomplete."
            dropped > 0 -> "Data lost: $dropped line(s) dropped because the writer fell behind. This recording is incomplete."
            r != null && !r.complete -> "This recording is incomplete (${termination(r.termination)})."
            else -> null
        }
    }

    fun stats(s: RawNmeaStatsSnapshot): String = buildString {
        append("Sentences ").append(s.sentences).append("   Bytes ").append(bytes(s.bytes)).append('\n')
        append("GGA ").append(s.count(SentenceType.GGA))
        append("  RMC ").append(s.count(SentenceType.RMC))
        append("  GST ").append(s.count(SentenceType.GST))
        append("  ETC ").append(s.count(SentenceType.ETC))
        append("  EBP ").append(s.count(SentenceType.EBP))
        append("  Other ").append(s.count(SentenceType.OTHER)).append('\n')
        append("Bad checksum ").append(s.checksumInvalid)
        append("  Malformed ").append(s.malformed)
        append("  Dropped ").append(s.droppedLines)
    }

    fun termination(t: RawNmeaTermination): String = when (t) {
        RawNmeaTermination.DURATION -> "duration reached"
        RawNmeaTermination.SIZE_LIMIT -> "10 MB size limit reached"
        RawNmeaTermination.USER_STOP -> "stopped by you"
        RawNmeaTermination.SOURCE_CHANGED -> "GNSS source or receiver changed"
        RawNmeaTermination.DISCONNECTED -> "receiver disconnected"
        RawNmeaTermination.LIFECYCLE -> "left the Developer page"
        RawNmeaTermination.WRITER_ERROR -> "file write failed"
        RawNmeaTermination.BUFFER_OVERFLOW -> "writer fell behind, lines lost"
    }

    fun recordingDetail(r: RawNmeaFiles.Recording, recordingNow: Boolean): String {
        if (recordingNow) return "${bytes(r.sizeBytes)} · recording…"
        val s = r.summary ?: return "${bytes(r.sizeBytes)} · no metadata (incomplete)"
        val state = when {
            s.complete -> "complete"
            s.termination == null -> "incomplete (interrupted)"
            else -> "INCOMPLETE (" + (runCatching { termination(RawNmeaTermination.valueOf(s.termination)) }.getOrDefault(s.termination)) + ")"
        }
        val src = when (s.provider) { "EXTERNAL_TCP" -> "TCP"; "INTERNAL" -> "tablet GNSS"; else -> s.provider ?: "?" }
        return "${bytes(r.sizeBytes)} · $src · $state"
    }

    fun clock(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
    }
}
