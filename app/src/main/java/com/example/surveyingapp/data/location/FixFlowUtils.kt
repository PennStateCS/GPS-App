package com.example.surveyingapp.domain.location

import com.example.surveyingapp.domain.model.Fix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.sample

/**
 * For UI maps: reduce jitter & workload without losing responsiveness.
 * - sample to ~5 Hz (200 ms)
 * - avoid redraws when position changes are sub-cm at typical latitudes
 */
fun Flow<Fix>.forMapUi(): Flow<Fix> =
    this
        .sample(200) // ~5 Hz is smooth enough for camera & markers
        .distinctUntilChangedBy { f ->
            // Bucket lat/lon into ~1 cm bins:
            // 1 deg lat ~ 111,320 m ⇒ 1e-7 deg ≈ 1.11 cm; use 1e-7 binning
            Pair(
                (f.lat * 1e7).toLong(),
                (f.lon * 1e7).toLong()
            )
        }
