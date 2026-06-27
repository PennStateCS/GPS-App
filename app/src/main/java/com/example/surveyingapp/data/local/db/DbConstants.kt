package com.example.surveyingapp.data.local.db

object DbConstants {
    const val PROVIDER_FUSED = "fused"
    const val PROVIDER_RS2_BT = "rs2-bt"
    const val PROVIDER_RS2_TCP = "rs2-tcp"
    // Distinct string for the general/legacy external label so RS2_EXTERNAL round-trips
    // through the domain layer instead of collapsing to RS2_TCP.
    const val PROVIDER_RS2_EXTERNAL = "rs2-external"
}
