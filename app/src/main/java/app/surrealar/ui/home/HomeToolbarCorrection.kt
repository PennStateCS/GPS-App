package app.surrealar.ui.home

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.ui.toolbar.ToolbarFrame
import app.surrealar.ui.toolbar.ToolbarMapResult
import io.github.pennstatecs.geokit.gnss.model.Provider

/**
 * The toolbar pipeline's stabilised correction state, tagged with what it was computed for, so Home can use it only
 * for the same live fix stream (the toolbar owns the one [app.surrealar.gnss.status.CorrectionHealthTracker]; Home
 * never runs a second one).
 *
 * The toolbar samples its inputs (250 ms) and Home collects the fix directly, so for a moment the two can describe
 * different moments. Within one receiver's stream that lag is harmless (the hysteresis already holds a warning for
 * ≥ 3 s), but across a source or receiver change it is not: the toolbar's last frame may still carry the previous
 * receiver's warning. [forHome] therefore accepts the state only when the frame was computed for the same selected
 * source, the same receiver (profile + host:port) and an external live fix from the same provider; otherwise Home
 * falls back to its raw stale rule until the toolbar catches up (≤ one 250 ms sample).
 */
data class HomeToolbarCorrection(
    val health: CorrectionHealth,
    val recovering: Boolean,
    val source: LocationSourceType,
    val receiverKey: String,
    val fixProvider: Provider?,
    val liveExternal: Boolean,
) {
    /** This state if it describes Home's current live fix stream, else null (use the raw rule). */
    fun forHome(source: LocationSourceType, receiverKey: String, fixProvider: Provider?): HomeToolbarCorrection? =
        takeIf {
            liveExternal && this.source == source && this.receiverKey == receiverKey &&
                fixProvider != null && this.fixProvider == fixProvider
        }

    companion object {
        /** From a toolbar frame; null when the frame shows no rendered live state. */
        fun of(frame: ToolbarFrame?): HomeToolbarCorrection? {
            val state = (frame?.result as? ToolbarMapResult.Render)?.state ?: return null
            val i = frame.inputs
            val liveExternal = i.source != LocationSourceType.INTERNAL && i.fix != null &&
                (i.live == null || i.live.kind == LiveSourceKind.EXTERNAL) && !state.isWaiting && !state.isStale
            return HomeToolbarCorrection(state.correctionHealth, frame.correctionRecovering, i.source, i.receiverKey,
                i.fix?.provider, liveExternal)
        }
    }
}
