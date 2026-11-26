package app.surrealar.ui.home

import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Provider
import java.util.Locale

/**
 * Which source actually produced the live position on Home, separate from the source the user SELECTED.
 *
 * The selected source and saved receiver are persisted settings; they are not proof of what is streaming. During
 * startup, a reconnect or a receiver switch they can disagree with the live fix (e.g. External selected while the
 * last fix still came from tablet GNSS, or no fix yet). Home attaches external identity and RTK metrics only when
 * [Resolved.live] is [Live.EXTERNAL] — a fix whose provider is an external receiver.
 *
 * Pure (no Android, no flows) so it is unit-tested, and so it can later be replaced by a "live positioning source"
 * flow from the receiver-selection core without touching [HomeFieldStatusMapper].
 */
object HomeLiveSource {

    /** Where the current live fix came from. */
    enum class Live {
        /** No valid live fix from the selected source. */
        NONE,
        /** Tablet (device) GNSS. */
        INTERNAL,
        /** An external receiver (TCP / Bluetooth). */
        EXTERNAL,
        /** NMEA replay (developer feature; never captured). */
        REPLAY,
    }

    /** Connection state of the selected external receiver, as Home words it. */
    enum class Link {
        /** Tablet GNSS selected: no external link applies. */
        NOT_APPLICABLE,
        /** A connection attempt (first connect, test before switch of the saved receiver) is running. */
        CONNECTING,
        /** The link dropped and the adapter is retrying. */
        RECONNECTING,
        /** The TCP link is up (this alone does not mean positions are arriving). */
        CONNECTED,
        /** External selected but no link is up and nothing is being attempted (or it failed). */
        UNAVAILABLE,
    }

    /**
     * @property live where the live fix came from (after rejecting a fix from a source that is not the selected one)
     * @property link state of the selected external receiver's connection
     * @property identityConfirmed the receiver-reported identity belongs to the receiver producing the live fix
     */
    data class Resolved(val live: Live, val link: Link, val identityConfirmed: Boolean)

    /**
     * @param externalSelected the user selected an external receiver (persisted setting)
     * @param fixProvider provider of the current live fix (null = no live fix)
     * @param fixValid the live fix has valid coordinates (fix liveness = valid lat/lon, never wall-clock age)
     * @param externalProviderActive the GNSS coordinator's active provider is the external TCP adapter
     * @param link the external adapter's link state (null = no adapter / unknown)
     * @param attemptInProgress a connection test / switch is running for the saved receiver (or none is saved)
     * @param identityHost host the receiver-reported identity was read from (null = none reported)
     * @param savedHost host of the saved (selected) receiver (null/blank = none)
     */
    fun resolve(
        externalSelected: Boolean,
        fixProvider: Provider?,
        fixValid: Boolean,
        externalProviderActive: Boolean,
        link: ConnectionState?,
        attemptInProgress: Boolean,
        identityHost: String?,
        savedHost: String?,
    ): Resolved {
        val rawLive = when {
            fixProvider == null || !fixValid -> Live.NONE
            fixProvider == Provider.INTERNAL -> Live.INTERNAL
            fixProvider == Provider.EXTERNAL_TCP || fixProvider == Provider.EXTERNAL_BT -> Live.EXTERNAL
            fixProvider == Provider.REPLAY -> Live.REPLAY
            else -> Live.NONE // MODEL / OTHER: not a live positioning source for Home
        }
        // A fix from a source other than the selected one is not shown as the selected source's position
        // (e.g. the tablet GNSS fix still live while the external receiver connects).
        val live = when {
            externalSelected && rawLive == Live.INTERNAL -> Live.NONE
            !externalSelected && rawLive == Live.EXTERNAL -> Live.NONE
            else -> rawLive
        }
        val linkState = when {
            !externalSelected -> Link.NOT_APPLICABLE
            externalProviderActive && link == ConnectionState.CONNECTED -> Link.CONNECTED
            externalProviderActive && link == ConnectionState.RECONNECTING -> Link.RECONNECTING
            externalProviderActive && link == ConnectionState.CONNECTING -> Link.CONNECTING
            attemptInProgress -> Link.CONNECTING
            else -> Link.UNAVAILABLE
        }
        val identityConfirmed = live == Live.EXTERNAL && externalProviderActive &&
            !identityHost.isNullOrBlank() && !savedHost.isNullOrBlank() && norm(identityHost) == norm(savedHost)
        return Resolved(live, linkState, identityConfirmed)
    }

    private fun norm(h: String) = h.trim().lowercase(Locale.ROOT)
}
