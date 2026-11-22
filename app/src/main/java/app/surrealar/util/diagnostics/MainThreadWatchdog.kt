package app.surrealar.util.diagnostics

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.surrealar.util.DiagnosticsLogger

/**
 * Main-thread watchdog. A background thread posts a tiny task to the main thread every second; when
 * the main thread hasn't run one for [STALL_MS] it writes the main thread's stack (and once more if
 * the stall lasts [LONG_STALL_MS]) to the error log, then a "recovered" line when it runs again. An
 * ANR the user force-stops leaves no trace anywhere else, so this is what tells us where it hung.
 */
object MainThreadWatchdog {
    const val STALL_MS = 5_000L
    const val LONG_STALL_MS = 30_000L
    private const val TICK_MS = 1_000L

    /** Pure stall bookkeeping (tested): what to report at time [now] given the last main-thread ack. */
    class Detector(private val stallMs: Long = STALL_MS, private val longStallMs: Long = LONG_STALL_MS) {
        sealed interface Event {
            data class Stalled(val forMs: Long, val long: Boolean) : Event
            data class Recovered(val afterMs: Long) : Event
        }
        private var reported = 0          // 0 none, 1 stall reported, 2 long stall reported
        private var stallStart = 0L

        fun check(now: Long, lastAck: Long): Event? {
            val late = now - lastAck
            return when {
                late >= stallMs && reported == 0 -> { reported = 1; stallStart = lastAck; Event.Stalled(late, long = false) }
                late >= longStallMs && reported == 1 -> { reported = 2; Event.Stalled(late, long = true) }
                late < stallMs && reported > 0 -> { reported = 0; Event.Recovered(now - stallStart) }
                else -> null
            }
        }
    }

    @Volatile private var lastAckMs = 0L
    @Volatile private var started = false

    fun start() {
        // Robolectric pauses the main looper between tasks: every test would read as a stall.
        if (started || android.os.Build.FINGERPRINT == "robolectric") return
        started = true
        val main = Handler(Looper.getMainLooper())
        val ack = Runnable { lastAckMs = SystemClock.uptimeMillis() }
        lastAckMs = SystemClock.uptimeMillis()
        Thread({
            val detector = Detector()
            while (true) {
                try {
                    main.post(ack)
                    Thread.sleep(TICK_MS)
                    if (Debug.isDebuggerConnected()) { lastAckMs = SystemClock.uptimeMillis(); continue }
                    when (val e = detector.check(SystemClock.uptimeMillis(), lastAckMs)) {
                        is Detector.Event.Stalled -> report(e)
                        is Detector.Event.Recovered ->
                            DiagnosticsLogger.w(DiagTags.ERROR, "Main thread recovered after ${e.afterMs / 1000.0}s " +
                                "(screen=${DiagnosticsLogger.currentScreen ?: "unknown"})")
                        null -> Unit
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    runCatching { DiagnosticsLogger.w(DiagTags.ERROR, "Watchdog error: ${t.message}") }
                }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    private fun report(e: Detector.Event.Stalled) {
        // As a Throwable so the log writes the frames as continuation lines (messages are one line).
        val where = Throwable("Main thread stack").apply { stackTrace = Looper.getMainLooper().thread.stackTrace }
        DiagnosticsLogger.e(DiagTags.ERROR,
            "Main thread ${if (e.long) "STILL " else ""}blocked for ${e.forMs / 1000.0}s " +
                "(screen=${DiagnosticsLogger.currentScreen ?: "unknown"})", where)
    }
}