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

    /*
     * Paused while the app is in the background (no 1 s wakeups for nothing). Runs from start() so a
     * hang during cold start, before the first activity's onStart, is still caught.
     */
    private val pauseLock = Object()
    @Volatile private var foreground = true

    /** Call from Application.onCreate on the main thread. */
    fun start() {
        // Robolectric pauses the main looper between tasks: every test would read as a stall.
        if (started || android.os.Build.FINGERPRINT == "robolectric") return
        started = true
        val main = Handler(Looper.getMainLooper())
        val ack = Runnable { lastAckMs = SystemClock.uptimeMillis() }
        lastAckMs = SystemClock.uptimeMillis()
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> setForeground(true)
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> setForeground(false)
                        else -> Unit
                    }
                })
        }.onFailure { DiagnosticsLogger.w(DiagTags.ERROR, "Watchdog: no process lifecycle, runs in background too", it) }
        Thread({
            var detector = Detector()
            while (true) {
                try {
                    if (!foreground) {
                        synchronized(pauseLock) { while (!foreground) pauseLock.wait() }
                        // Back in the foreground: the pause is not a stall. Start the bookkeeping over.
                        lastAckMs = SystemClock.uptimeMillis()
                        detector = Detector()
                    }
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
                    // Loops every tick: a repeating error is logged at most once a minute.
                    runCatching { DiagnosticsLogger.every(DiagTags.ERROR, "watchdog.error", 60_000L, 'W', t) { "Watchdog loop error" } }
                }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    private fun setForeground(value: Boolean) {
        synchronized(pauseLock) {
            foreground = value
            if (value) pauseLock.notifyAll()
        }
    }

    private fun report(e: Detector.Event.Stalled) {
        // As a Throwable so the log writes the frames as continuation lines (messages are one line).
        val where = Throwable("Main thread stack").apply { stackTrace = Looper.getMainLooper().thread.stackTrace }
        DiagnosticsLogger.e(DiagTags.ERROR,
            "Main thread ${if (e.long) "STILL " else ""}blocked for ${e.forMs / 1000.0}s " +
                "(screen=${DiagnosticsLogger.currentScreen ?: "unknown"})", where)
    }
}