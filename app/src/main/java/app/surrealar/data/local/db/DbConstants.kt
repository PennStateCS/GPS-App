package app.surrealar.data.local.db

/**
 * Stable string tokens persisted in `coordinates.provider`. These are storage values, not display
 * labels: the mapper round-trips them to/from the `Provider` enum, so keep each distinct and never
 * repurpose an existing token (e.g. `PROVIDER_RS2_EXTERNAL` stays separate from `PROVIDER_RS2_TCP` so
 * old rows don't collapse to the wrong provider).
 */
object DbConstants {
    const val PROVIDER_FUSED = "fused"
    const val PROVIDER_RS2_BT = "rs2-bt"
    const val PROVIDER_RS2_TCP = "rs2-tcp"
    // Distinct string for the general/legacy external label so RS2_EXTERNAL round-trips
    // through the domain layer instead of collapsing to RS2_TCP.
    const val PROVIDER_RS2_EXTERNAL = "rs2-external"
}
