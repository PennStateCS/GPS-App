package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.ChecksumStatus
import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.SentenceType

/** Which live per-line hook a recorded line came from. Replay has no hook and is never recorded. */
enum class RawNmeaOrigin(val connectionType: String, val tapPoint: String) {
    /** External receiver over TCP: TcpNmeaSource's `onLine` (GnssModule). */
    EXTERNAL_TCP("TCP", "TcpNmeaSource.onLine (after BufferedReader.readLine + String.trim; only lines starting with '$')"),
    /** Tablet GNSS: Android's OnNmeaMessageListener message, as InternalNmeaSource receives it. */
    INTERNAL("INTERNAL", "InternalNmeaSource OnNmeaMessageListener message (trailing CR/LF removed)"),
}

/** Why a recording ended (stored token in the companion JSON; never rename). */
enum class RawNmeaTermination(val completesRecording: Boolean) {
    DURATION(true),
    SIZE_LIMIT(true),
    USER_STOP(true),
    SOURCE_CHANGED(false),
    DISCONNECTED(false),
    LIFECYCLE(false),
    WRITER_ERROR(false),
    BUFFER_OVERFLOW(false),
}

/** Duration choices on the Developer page; null seconds = Manual (stopped by the user or the size limit). */
enum class RawNmeaDuration(val seconds: Int?, val label: String) {
    S10(10, "10 s"),
    S30(30, "30 s"),
    S60(60, "60 s"),
    MANUAL(null, "Manual");

    companion object { val DEFAULT = S30 }
}

/**
 * Source / receiver context written to the companion JSON. Receiver-reported fields are null unless the
 * receiver actually reported them; nothing is inferred. Never contains host/IP, credentials or HTTP bodies.
 */
data class RawNmeaSourceMetadata(
    val profileKey: String? = null,
    val profileLabel: String? = null,
    /** Sanitized into the file name ("Internal" for the tablet GNSS). */
    val fileLabel: String,
    val reportedModel: String? = null,
    val reportedFirmware: String? = null,
    val reportedSerial: String? = null,
    /** "ENABLED" / "DISABLED" / "UNKNOWN" as set by the user; null when not applicable (internal GNSS). */
    val compensatedOutput: String? = null,
    val appVersion: String? = null,
)

/** Running statistics of one recording; mutated only by the writer. */
class RawNmeaStats {
    var sentences: Long = 0; private set
    var bytes: Long = 0; private set
    val byType: LongArray = LongArray(SentenceType.entries.size)
    var checksumValid: Long = 0; private set
    var checksumInvalid: Long = 0; private set
    var malformed: Long = 0; private set

    fun add(line: String, writtenBytes: Int) {
        val r = NmeaLineClassifier.classify(line)
        sentences++
        bytes += writtenBytes
        byType[r.type.ordinal]++
        when (r.checksum) {
            ChecksumStatus.VALID -> checksumValid++
            ChecksumStatus.INVALID -> checksumInvalid++
            ChecksumStatus.MALFORMED -> malformed++
        }
    }

    fun snapshot(dropped: Long): RawNmeaStatsSnapshot = RawNmeaStatsSnapshot(
        sentences = sentences, bytes = bytes,
        byType = SentenceType.entries.associateWith { byType[it.ordinal] },
        checksumValid = checksumValid, checksumInvalid = checksumInvalid, malformed = malformed,
        droppedLines = dropped,
    )
}

data class RawNmeaStatsSnapshot(
    val sentences: Long = 0,
    val bytes: Long = 0,
    val byType: Map<SentenceType, Long> = SentenceType.entries.associateWith { 0L },
    val checksumValid: Long = 0,
    val checksumInvalid: Long = 0,
    val malformed: Long = 0,
    val droppedLines: Long = 0,
) {
    fun count(t: SentenceType): Long = byType[t] ?: 0L
}

/** A finished recording's outcome. */
data class RawNmeaResult(
    val nmeaFile: java.io.File,
    val metadataFile: java.io.File,
    val termination: RawNmeaTermination,
    val complete: Boolean,
    val stats: RawNmeaStatsSnapshot,
    val durationMs: Long,
    val writerError: String? = null,
)

/** Recorder state for the UI. */
sealed interface RawNmeaState {
    data object Idle : RawNmeaState
    data class Recording(
        val origin: RawNmeaOrigin,
        val nmeaFile: java.io.File,
        val startedMonoMs: Long,
        val durationLimitMs: Long?,
        val stats: RawNmeaStatsSnapshot,
    ) : RawNmeaState
    data class Finished(val result: RawNmeaResult) : RawNmeaState
    data class Failed(val message: String) : RawNmeaState
}

/** Whether a live source with a raw NMEA hook is active. */
sealed interface RawNmeaAvailability {
    data class Available(val origin: RawNmeaOrigin) : RawNmeaAvailability
    data class Unavailable(val reason: String) : RawNmeaAvailability
}
