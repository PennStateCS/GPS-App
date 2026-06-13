package com.example.surveyingapp.gnss.model

enum class RtkStatus {
    NONE,
    DGPS,
    FLOAT,
    FIX,
    SINGLE,
    DEAD_RECKONING,
    INVALID;

    /**
     * Returns a quality rank for this RTK status, where higher values indicate better quality.
     *
     * Ranking (highest to lowest):
     * - FIX (4): cm-level accuracy, carrier phase ambiguities resolved
     * - FLOAT (3): dm-level accuracy, ambiguities not fully resolved
     * - DGPS (2): meter-level accuracy with differential corrections
     * - SINGLE (1): standalone positioning without corrections
     * - DEAD_RECKONING (1): inertial/sensor fusion fallback
     * - NONE (0): no fix
     * - INVALID (0): invalid/unknown status
     */
    fun qualityRank(): Int = when (this) {
        FIX            -> 4
        FLOAT          -> 3
        DGPS           -> 2
        SINGLE         -> 1
        DEAD_RECKONING -> 1
        NONE           -> 0
        INVALID        -> 0
    }

    /**
     * Returns true if this status has equal or better quality than [other].
     * Use this for capture policy comparisons instead of comparing enum ordinals.
     */
    fun meetsOrExceeds(other: RtkStatus): Boolean = qualityRank() >= other.qualityRank()
}
