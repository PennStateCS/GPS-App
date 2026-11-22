package app.surrealar.ui.openinar

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rate gate for posting UI updates from the GL thread to the main thread.
 *
 * [tryAcquire] allows a post only when at least [minIntervalMs] has passed since the last allowed
 * post AND the previous post has already run (the posted block calls [release] first thing). This
 * keeps a 30-60 fps render loop from queueing a main-thread runnable every frame.
 *
 * Times are a monotonic clock in ms (e.g. `SystemClock.elapsedRealtime()`); pass them in so the
 * gate stays pure and testable.
 */
class UiPostGate(private val minIntervalMs: Long) {
    private val pending = AtomicBoolean(false)
    @Volatile private var lastPostMs: Long = NEVER

    /** True if the caller may post now; marks a post as pending. */
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastPostMs
        if (last != NEVER && nowMs - last < minIntervalMs) return false
        if (!pending.compareAndSet(false, true)) return false
        lastPostMs = nowMs
        return true
    }

    /** Called at the start of the posted block, or when the post could not be queued. */
    fun release() {
        pending.set(false)
    }

    /** Forget all state (new view / session). */
    fun reset() {
        pending.set(false)
        lastPostMs = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
