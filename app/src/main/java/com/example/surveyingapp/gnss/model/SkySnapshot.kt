package com.example.surveyingapp.gnss.model

import java.time.Instant

enum class SkySource { NMEA_GSV, PLATFORM_API, PROPRIETARY, UNKNOWN }

/**
 * Holds everything the skyplot and signal-strength charts need for one receiver update.
 *
 * `satellites` is the single source of truth. The properties below it are derived
 * views computed lazily — they exist to keep UI code concise without duplicating
 * grouping logic everywhere.
 */
data class SatInfo(
    val constellation: Constellation,
    val svid: Int,                  // platform/NMEA SVID (meaning varies by constellation)
    val elevationDeg: Double?,      // may be null on some APIs
    val azimuthDeg: Double?,        // may be null on some APIs
    val cn0DbHz: Double?,           // carrier-to-noise density in dB-Hz (higher = stronger signal)
    val usedInFix: Boolean? = null  // true if this satellite contributed to the position solution
)

/**
 * Snapshot of the visible sky at one point in time.
 *
 * Keep it cohesive: `satellites` is the source of truth;
 * aggregated maps are derived lazily to avoid drift.
 */
data class SkySnapshot(
    val satellites: List<SatInfo> = emptyList(),
    val epoch: Instant = Instant.EPOCH,
    val source: SkySource = SkySource.UNKNOWN,
    val smoothWindowMs: Long? = null
) {
    // --- Derived views (computed once, cached by lazy) ---
    val totalVisible: Int get() = satellites.size

    val totalUsed: Int get() = satellites.count { it.usedInFix == true }

    val visibleByConstellation: Map<Constellation, Int> by lazy {
        satellites.groupingBy { it.constellation }.eachCount()
    }

    val usedByConstellation: Map<Constellation, Int> by lazy {
        satellites.filter { it.usedInFix == true }
            .groupingBy { it.constellation }
            .eachCount()
    }

    /** Map SVID -> CN0 for quick bar-plot lookups (only if cn0 is present). */
    val cn0BySvid: Map<Int, Double> by lazy {
        satellites.mapNotNull { sat -> sat.cn0DbHz?.let { sat.svid to it } }.toMap()
    }

    companion object {
        fun empty(now: Instant = Instant.now()) =
            SkySnapshot(satellites = emptyList(), epoch = now, source = SkySource.UNKNOWN)
    }
}
