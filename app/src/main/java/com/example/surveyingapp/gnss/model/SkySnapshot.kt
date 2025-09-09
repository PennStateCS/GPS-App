package com.example.surveyingapp.gnss.model

/**
 * Snapshot of the sky from GSV or platform GNSS APIs.
 * Kept separate from Fix so screens can update with lively bars even if no new fix.
 */
data class SkySnapshot(
    val visibleByConstellation: Map<String, Int>, // e.g., {"GPS": 12, "GLO": 8, "GAL": 10, "BDS": 12}
    val usedByConstellation: Map<String, Int>,    // subset used in solution
    val snrBySvid: Map<Int, Double>,              // smoothed SNR per space vehicle
    val geometry: List<SkyGeometry> = emptyList() // normalized satellite geometry
)
