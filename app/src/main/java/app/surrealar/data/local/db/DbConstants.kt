package app.surrealar.data.local.db

import app.surrealar.gnss.model.Provider
import java.util.Locale

/**
 * Stable string tokens persisted in `coordinates.provider`. These are storage values, not display
 * labels: the mapper round-trips them to/from the `Provider` enum, so keep each distinct and never
 * repurpose an existing token.
 *
 * The `rs2-…` tokens are what versions before the receiver-neutral Provider wrote. They are never
 * written any more but are still read (see [ProviderTokens.fromDomain]).
 */
object DbConstants {
    const val PROVIDER_FUSED = "fused"
    const val PROVIDER_EXTERNAL_TCP = "external-tcp"
    const val PROVIDER_EXTERNAL_BT = "external-bt"
    const val PROVIDER_REPLAY = "replay"
    const val PROVIDER_MODEL = "model"
    const val PROVIDER_OTHER = "other"

    // Legacy tokens (read only).
    const val PROVIDER_RS2_BT = "rs2-bt"
    const val PROVIDER_RS2_TCP = "rs2-tcp"
    /** Was the general external label (the one live external fixes carried); now [PROVIDER_EXTERNAL_TCP]. */
    const val PROVIDER_RS2_EXTERNAL = "rs2-external"
}

/**
 * The domain-level provider token (Coordinate.provider, backups) ⇄ [Provider]. One table for the
 * mapper and CoordinateFactory so a captured point round-trips without its provider degrading.
 */
object ProviderTokens {

    /** Token written for [p]. */
    fun toDomain(p: Provider): String = when (p) {
        Provider.INTERNAL     -> DbConstants.PROVIDER_FUSED
        Provider.EXTERNAL_TCP -> DbConstants.PROVIDER_EXTERNAL_TCP
        Provider.EXTERNAL_BT  -> DbConstants.PROVIDER_EXTERNAL_BT
        Provider.REPLAY       -> DbConstants.PROVIDER_REPLAY
        Provider.MODEL        -> DbConstants.PROVIDER_MODEL
        Provider.OTHER        -> DbConstants.PROVIDER_OTHER
    }

    /**
     * Reads a domain token, current or legacy. The legacy aliases are exactly the ones this mapper
     * accepted before the rename (`rs2-external`/`rs2-tcp` → external TCP, `rs2-bt` → Bluetooth);
     * anything unrecognised is [Provider.OTHER], as before.
     */
    fun fromDomain(s: String?): Provider = when (s?.trim()?.lowercase(Locale.US)) {
        DbConstants.PROVIDER_FUSED, "internal", "internal_gps"                         -> Provider.INTERNAL
        DbConstants.PROVIDER_EXTERNAL_TCP, "external_tcp",
        DbConstants.PROVIDER_RS2_TCP, "rs2_tcp",
        DbConstants.PROVIDER_RS2_EXTERNAL, "rs2_external"                              -> Provider.EXTERNAL_TCP
        DbConstants.PROVIDER_EXTERNAL_BT, "external_bt",
        DbConstants.PROVIDER_RS2_BT, "rs2_bt"                                          -> Provider.EXTERNAL_BT
        DbConstants.PROVIDER_REPLAY                                                    -> Provider.REPLAY
        DbConstants.PROVIDER_MODEL                                                     -> Provider.MODEL
        else                                                                           -> Provider.OTHER
    }

    /** True for a token that means an external receiver (any connection). */
    fun isExternal(s: String?): Boolean =
        fromDomain(s).let { it == Provider.EXTERNAL_TCP || it == Provider.EXTERNAL_BT }
}
