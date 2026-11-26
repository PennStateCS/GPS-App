package app.surrealar.receiver.selection

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import java.util.Locale

/** Which kind of source is producing the live position right now. */
enum class LiveSourceKind {
    /** No usable live fix (nothing yet, a switch or reconnect in progress, or the fix is not attributable). */
    NONE,
    /** The device's own GNSS. */
    DEVICE,
    /** The external receiver in [LiveSourceState.external]. */
    EXTERNAL,
    /** NMEA replay (never captured). */
    REPLAY,
}

/** The external receiver that produced [LiveSourceState.fix]. */
data class LiveExternalSource(
    /** The endpoint the external adapter is connected to (== the saved receiver). */
    val target: ReceiverTarget,
    /** The saved display name for that endpoint (null when none). */
    val name: String?,
    /** What that receiver reported (fresh, verified for this host); null when it reported nothing (yet). */
    val identity: ReceiverIdentity?,
)

/**
 * Why the selected receiver is not (yet) the live source, or what is changing. Independent of [LiveSourceKind]: e.g.
 * the previous receiver (or device GNSS) stays live while another one is [Testing].
 */
sealed interface SourceTransition {
    val target: ReceiverTarget?

    /** Test before switch running for [target]; the current source stays live. */
    data class Testing(override val target: ReceiverTarget) : SourceTransition
    /** [target] passed its test; its settings are being saved and it is being activated. */
    data class Switching(override val target: ReceiverTarget) : SourceTransition
    /** External is active; the adapter is connecting to [target] (no data from it yet). */
    data class Connecting(override val target: ReceiverTarget?) : SourceTransition
    /** External is active; the link to [target] dropped and is retrying. */
    data class Reconnecting(override val target: ReceiverTarget?) : SourceTransition
    /**
     * External is active and the link to [target] is up, but there is no position from it yet (receiver without a
     * fix, or the first fix after a switch / reconnect has not arrived; an earlier receiver's fix is never shown).
     */
    data class WaitingForPosition(override val target: ReceiverTarget?) : SourceTransition
    /** The last connect to [target] failed; nothing was changed (the previous source stays). */
    data class ConnectFailed(override val target: ReceiverTarget, val error: ConnectError) : SourceTransition
    /**
     * The saved selection is External ([target] = saved receiver, null when none) but External is not the active
     * provider: the startup restore has not run yet, the receiver was not restored, or replay is running.
     */
    data class ExternalNotActive(override val target: ReceiverTarget?) : SourceTransition
}

/**
 * The actual live positioning source, as opposed to the selected (persisted) receiver (stabilization item 4).
 *
 * - [fix] is the fix from [FixSwitchboard.currentFix][app.surrealar.gnss.bus.FixSwitchboard.currentFix] only when it
 *   is attributable to [kind]; otherwise null. An external fix is attributable only when External is the active
 *   provider, the link is CONNECTED, the adapter's endpoint is the saved receiver, and the fix arrived after the last
 *   switch / reconnect / endpoint change (a fix still held from the previous receiver is never attributed).
 * - [external] (name, identity) is set ONLY when [kind] is [LiveSourceKind.EXTERNAL]; identity only when the receiver's
 *   report is for the connected host. RTK metrics come from [fix].
 * - [transition] says what is in progress or why the selection is not the live source.
 */
data class LiveSourceState(
    val kind: LiveSourceKind = LiveSourceKind.NONE,
    val fix: Fix? = null,
    val external: LiveExternalSource? = null,
    val transition: SourceTransition? = null,
)

/** Pure derivation of [LiveSourceState] (testable without coroutines). */
object LiveSourceRules {

    data class Input(
        val activeProvider: ProviderChoice,
        /** FixSwitchboard.currentFix. */
        val fix: Fix?,
        /** The fix that was current at the last fence (switch, reconnect, endpoint change): never attributed. */
        val fencedFix: Fix?,
        val link: ConnectionState?,
        /** The endpoint the external adapter last connected to (null: none yet / unknown). */
        val connectedEndpoint: ReceiverTarget?,
        /** False when no endpoint information is available at all (then it is not required). */
        val endpointKnown: Boolean,
        /** GnssSourceCoordinator.activeExternalTarget. */
        val activeExternalTarget: ReceiverTarget?,
        val selectedSource: LocationSourceType,
        val savedTarget: ReceiverTarget?,
        val savedName: String?,
        val attempt: ConnectionAttempt,
        val identity: ReceiverIdentity?,
    )

    fun derive(i: Input): LiveSourceState {
        val fix = i.fix?.takeUnless { it === i.fencedFix }
        val externalTarget = i.activeExternalTarget ?: i.savedTarget
        var kind = LiveSourceKind.NONE
        var liveFix: Fix? = null
        var external: LiveExternalSource? = null
        var linkTransition: SourceTransition? = null

        when (i.activeProvider) {
            ProviderChoice.INTERNAL -> if (fix != null && fix.provider == Provider.INTERNAL) {
                kind = LiveSourceKind.DEVICE; liveFix = fix
            }
            ProviderChoice.REPLAY -> if (fix != null && fix.provider == Provider.REPLAY) {
                kind = LiveSourceKind.REPLAY; liveFix = fix
            }
            ProviderChoice.EXTERNAL_TCP -> {
                val endpointMatches = !i.endpointKnown ||
                    (i.connectedEndpoint != null && sameTarget(i.connectedEndpoint, externalTarget))
                val savedMatches = i.savedTarget == null || sameTarget(i.savedTarget, externalTarget)
                val externalFix = fix != null && (fix.provider == Provider.EXTERNAL_TCP || fix.provider == Provider.EXTERNAL_BT)
                when {
                    i.link != ConnectionState.CONNECTED -> linkTransition = when (i.link) {
                        ConnectionState.RECONNECTING, ConnectionState.ERROR -> SourceTransition.Reconnecting(externalTarget)
                        else -> SourceTransition.Connecting(externalTarget)
                    }
                    !endpointMatches || !savedMatches || externalTarget == null -> linkTransition = SourceTransition.Connecting(externalTarget)
                    !externalFix -> linkTransition = SourceTransition.WaitingForPosition(externalTarget)
                    else -> {
                        kind = LiveSourceKind.EXTERNAL
                        liveFix = fix
                        external = LiveExternalSource(
                            target = externalTarget,
                            name = i.savedName?.takeIf { it.isNotBlank() && i.savedTarget != null },
                            identity = i.identity?.takeIf { it.host.trim().equals(externalTarget.host.trim(), ignoreCase = true) },
                        )
                    }
                }
            }
        }

        val transition: SourceTransition? = when (val a = i.attempt) {
            is ConnectionAttempt.Testing -> SourceTransition.Testing(a.target)
            is ConnectionAttempt.Switching -> SourceTransition.Switching(a.target)
            else -> linkTransition
                ?: (a as? ConnectionAttempt.Failed)?.takeIf { kind != LiveSourceKind.EXTERNAL }
                    ?.let { SourceTransition.ConnectFailed(it.target, it.error) }
                ?: if (i.activeProvider != ProviderChoice.EXTERNAL_TCP && i.selectedSource == LocationSourceType.EXTERNAL)
                    SourceTransition.ExternalNotActive(i.savedTarget) else null
        }
        return LiveSourceState(kind, liveFix, external, transition)
    }

    private fun sameTarget(a: ReceiverTarget, b: ReceiverTarget?): Boolean =
        b != null && a.port == b.port && a.host.trim().lowercase(Locale.ROOT) == b.host.trim().lowercase(Locale.ROOT)
}
