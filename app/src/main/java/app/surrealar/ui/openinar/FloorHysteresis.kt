package app.surrealar.ui.openinar

import kotlin.math.abs

/**
 * Steadies a ground height that is re-chosen every frame (GroundSnapper), so snapped content doesn't
 * jump when ARCore's plane set flickers or two surfaces trade places as "the floor".
 *
 * - The first known height is taken at once.
 * - Changes within [deadbandM] of the held height are ignored (plane refinement jitter).
 * - A larger change, or the height becoming unknown (null), is adopted only after the new value has
 *   held — within [deadbandM] of itself — for [stableFrames] consecutive updates. A candidate that
 *   wanders or flips back restarts the count.
 *
 * Pure (no ARCore types) so it is JVM-testable. GL thread only.
 */
internal class FloorHysteresis(
    private val deadbandM: Float = DEFAULT_DEADBAND_M,
    private val stableFrames: Int = DEFAULT_STABLE_FRAMES,
) {
    init {
        require(deadbandM >= 0f) { "deadbandM must be >= 0" }
        require(stableFrames >= 1) { "stableFrames must be >= 1" }
    }

    private var hasHeld = false
    private var held: Float? = null
    private var candidate: Float? = null
    private var hasCandidate = false
    private var candidateFrames = 0

    /** The height currently in use (null = unknown). */
    val value: Float? get() = held

    /** Feeds this frame's raw height; returns the height to use. */
    fun update(raw: Float?): Float? {
        if (!hasHeld) {
            if (raw == null) return null      // nothing known yet: nothing to hold
            commit(raw)
            return held
        }
        if (close(raw, held)) {
            clearCandidate()
            return held
        }
        if (hasCandidate && close(raw, candidate)) {
            candidateFrames++
        } else {
            candidate = raw
            hasCandidate = true
            candidateFrames = 1
        }
        if (candidateFrames >= stableFrames) commit(raw)
        return held
    }

    fun reset() {
        hasHeld = false
        held = null
        clearCandidate()
    }

    private fun commit(v: Float?) {
        hasHeld = v != null
        held = v
        clearCandidate()
    }

    private fun clearCandidate() {
        candidate = null
        hasCandidate = false
        candidateFrames = 0
    }

    private fun close(a: Float?, b: Float?): Boolean =
        if (a == null || b == null) a == null && b == null else abs(a - b) <= deadbandM

    companion object {
        /** Plane height refinements smaller than this are ignored. */
        const val DEFAULT_DEADBAND_M = 0.03f
        /** ~0.5 s at ARCore's 30 fps before a different floor is adopted. */
        const val DEFAULT_STABLE_FRAMES = 15
    }
}
