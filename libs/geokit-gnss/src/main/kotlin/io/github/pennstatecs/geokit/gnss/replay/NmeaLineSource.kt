package io.github.pennstatecs.geokit.gnss.replay

import kotlinx.coroutines.flow.Flow

/**
 * Interface for a raw NMEA-line source used by the **replay/demo** pipeline ([ReplayAdapter]), NOT
 * the live source-switching system — that uses `gnss.bus.adapters.NmeaSource`.
 */
public interface NmeaLineSource {
    /**
     * Human-readable name of this source (e.g., "Replay (sample.nmea)").
     */
    public val name: String

    /**
     * Emits raw NMEA lines without CRLF line endings.
     * Each emission is a single NMEA sentence as a string.
     */
    public fun lines(): Flow<String>
}
