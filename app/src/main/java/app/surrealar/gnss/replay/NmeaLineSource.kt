package app.surrealar.gnss.replay

import kotlinx.coroutines.flow.Flow

/**
 * Interface for a raw NMEA-line source used by the **replay/demo** pipeline (NOT the live
 * Internal/External source-switching system — that uses `gnss.bus.adapters.NmeaSource`).
 */
interface NmeaLineSource {
    /**
     * Human-readable name of this source (e.g., "Replay (sample.nmea)").
     */
    val name: String

    /**
     * Emits raw NMEA lines without CRLF line endings.
     * Each emission is a single NMEA sentence as a string.
     */
    fun lines(): Flow<String>
}
