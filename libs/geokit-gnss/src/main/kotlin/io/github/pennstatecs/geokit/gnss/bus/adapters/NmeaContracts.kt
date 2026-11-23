package io.github.pennstatecs.geokit.gnss.bus.adapters

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared NMEA-pipeline contracts and data types used by BOTH the internal and external GNSS
 * adapters. Kept in this neutral `gnss.bus.adapters` package (alongside [NmeaFuser]) so that
 * `gnss.internal` and `gnss.external` depend on a common contract rather than on each other.
 */

/**
 * Minimum contract for a source that can publish GNSS fixes (what a host's switchboard binds to).
 */
public interface SourceAdapter {
    /** Fixes from this source, in receive order. */
    public val fixes: SharedFlow<Fix>
}

/**
 * Optional contract for adapters that publish satellite sky state.
 */
public interface SkyProvider {
    /** The latest satellite snapshot from this source. */
    public val sky: StateFlow<SkySnapshot>
}

/**
 * Optional contract for adapters that need explicit lifecycle control.
 */
public interface Startable {
    /** Starts the adapter (opens its connection / registers listeners). */
    public fun start()
    /** Stops the adapter and releases its resources; safe to call when already stopped. */
    public fun stop()
}

/**
 * Contract for an NMEA data pipeline that wraps a physical or virtual connection.
 *
 * Implementations are responsible for opening the connection, forwarding raw lines
 * through [NmeaFuser], and publishing the resulting [Fix] and [GsvMessage] streams.
 * The external adapter owns reconnection; the source itself makes a single attempt.
 *
 * To add support for a new transport (e.g. u-blox over Bluetooth), implement this
 * interface and hand the instance to the host's source adapter (which owns reconnection).
 */
public interface NmeaSource : Startable {
    /** Fixes produced by this source's [NmeaFuser]. */
    public fun parsedFixes(): SharedFlow<Fix>
    /** Completed GSV epochs (satellites in view) from this source's [NmeaFuser]. */
    public fun gsvStream(): SharedFlow<GsvMessage>
    /** Emits once for every raw NMEA sentence received, before parsing. */
    public fun rawNmeaEvents(): SharedFlow<Unit>

    /** Cumulative NMEA sentence/fix-rate stats from this source's fuser (diagnostics; no coordinates). */
    public fun nmeaTimingStats(): NmeaFuser.FuserTimingStats
    /**
     * Extra (non-fix) sentences seen by this source's fuser, per tag: what the host's extra parsers
     * (e.g. a receiver's proprietary messages) produced. Diagnostics only.
     */
    public fun nmeaExtraStats(): NmeaFuser.ExtraSentenceStats

    /**
     * False once the underlying connection has ended (remote closed, connect failed, stopped).
     * Sources without a connection concept keep the default `true`.
     */
    public fun isConnectionActive(): Boolean = true
}

/**
 * Optional interface for adapters that expose a raw-NMEA activity stream.
 * A host can forward it so observers can distinguish "socket connected but no parsed fix" from
 * "no NMEA data flowing at all."
 */
public interface RawNmeaProvider {
    /** Emits once per raw NMEA line received, before parsing. */
    public val rawNmea: SharedFlow<Unit>
}

/** Satellite data entry from a single GSV sentence, normalised for SatelliteInventory. */
public data class GsvMessage(
    val constellation: String,
    val entries: List<GsvEntry>
)

/**
 * One satellite within a [GsvMessage], normalised for `SatelliteInventory`. Elevation/azimuth are
 * degrees and [snrDbHz] is C/N0 in dB-Hz; these are null when the receiver omitted them. [usedInFix]
 * reflects whether the satellite contributed to the position solution, when that is known.
 */
public data class GsvEntry(
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Double?,
    val usedInFix: Boolean
)
