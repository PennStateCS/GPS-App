package app.surrealar.gnss.diagnostics.raw

/**
 * The per-line hooks GnssModule hands to the live NMEA sources. The recorder tap goes first and never throws
 * or blocks ([RawNmeaRecorder.offer] is a `trySend`), so a line that makes a later hook throw (TcpNmeaSource
 * then drops that sentence) is still recorded as received; the existing hooks run unchanged and in their
 * original order.
 */
object RawNmeaTaps {

    /** TcpNmeaSource `onLine`: raw recording tap, then the NMEA rate/diagnostics counters, then the RS4 tilt monitor. */
    fun externalOnLine(
        recorder: RawNmeaRecorder,
        recordDiagnostics: (String) -> Unit,
        tiltMonitor: (String) -> Unit,
    ): (String) -> Unit = { line ->
        recorder.offer(RawNmeaOrigin.EXTERNAL_TCP, line)
        recordDiagnostics(line)
        tiltMonitor(line)
    }

    /** InternalNmeaSource raw-line hook (the OnNmeaMessageListener message, before fusion). */
    fun internalOnRawLine(recorder: RawNmeaRecorder): (String) -> Unit = { message ->
        recorder.offer(RawNmeaOrigin.INTERNAL, message)
    }
}
