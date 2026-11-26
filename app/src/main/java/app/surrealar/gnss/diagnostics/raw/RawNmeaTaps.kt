package app.surrealar.gnss.diagnostics.raw

import app.surrealar.util.DiagnosticsLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The per-line hooks GnssModule hands to the live NMEA sources. The recorder tap goes first and never blocks
 * ([RawNmeaRecorder.offer] is a `trySend`), then the existing hooks run in their original order.
 *
 * The composed hook never throws. geokit's TcpNmeaSource (off-limits) calls `onLine` inside the same `try` as
 * `fuser.accept`, so an exception from any diagnostic hook would drop that sentence before it is parsed. Each
 * hook therefore runs in its own guard: a failing hook is logged (first failure in full, then at most once a
 * minute per hook, never the sentence text) and the remaining hooks and the fuser still get the line.
 */
object RawNmeaTaps {

    /** Repeated hook failures are logged at most this often per hook (after the first, with its stack trace). */
    internal const val HOOK_FAILURE_LOG_INTERVAL_MS = 60_000L

    /** TcpNmeaSource `onLine`: raw recording tap, then the NMEA rate/diagnostics counters, then the RS4 tilt monitor. */
    fun externalOnLine(
        recorder: RawNmeaRecorder,
        recordDiagnostics: (String) -> Unit,
        tiltMonitor: (String) -> Unit,
    ): (String) -> Unit = { line ->
        isolated("external.recorder") { recorder.offer(RawNmeaOrigin.EXTERNAL_TCP, line) }
        isolated("external.diagnostics") { recordDiagnostics(line) }
        isolated("external.tilt") { tiltMonitor(line) }
    }

    /** InternalNmeaSource raw-line hook (the OnNmeaMessageListener message, before fusion). */
    fun internalOnRawLine(recorder: RawNmeaRecorder): (String) -> Unit = { message ->
        isolated("internal.recorder") { recorder.offer(RawNmeaOrigin.INTERNAL, message) }
    }

    /**
     * Runs one diagnostic/recording [hook] so that it can never disturb position processing: any exception is
     * logged (throttled per [name]) and swallowed. Cancellation still propagates.
     */
    inline fun isolated(name: String, hook: () -> Unit) {
        try {
            hook()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            hookFailed(name, e)
        }
    }

    @PublishedApi internal fun hookFailed(name: String, e: Exception) {
        try {
            DiagnosticsLogger.every("NMEA", "nmea.hook.$name", HOOK_FAILURE_LOG_INTERVAL_MS, 'E', throwable = e) {
                "NMEA diagnostic hook failed: hook=$name, sentence still processed, repeats suppressed for 60 s"
            }
        } catch (_: Exception) {
            // Logging must not break the stream either.
        }
    }
}
