package com.example.surveyingapp.gnss.source

import kotlinx.coroutines.flow.Flow

/**
 * Interface for GNSS data sources that provide raw NMEA lines.
 * This is part of the GNSS domain layer and abstracts the source of GNSS data.
 */
interface GnssSource {
    /**
     * Human-readable name of this GNSS source (e.g., "Internal GPS", "External RS2", "TCP Stream")
     */
    val name: String

    /**
     * Emits raw NMEA lines without CRLF line endings.
     * Each emission is a single NMEA sentence as a string.
     */
    fun lines(): Flow<String>
}
