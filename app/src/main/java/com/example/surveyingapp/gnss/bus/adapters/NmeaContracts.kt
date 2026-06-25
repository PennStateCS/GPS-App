package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.bus.Startable
import com.example.surveyingapp.gnss.model.Fix
import kotlinx.coroutines.flow.SharedFlow

/**
 * Shared NMEA-pipeline contracts and data types used by BOTH the internal and external GNSS
 * adapters. Kept in this neutral `gnss.bus.adapters` package (alongside [NmeaFuser]) so that
 * `gnss.internal` and `gnss.external` depend on a common contract rather than on each other.
 */

/**
 * Contract for an NMEA data pipeline that wraps a physical or virtual connection.
 *
 * Implementations are responsible for opening the connection, forwarding raw lines
 * through [NmeaFuser], and publishing the resulting [Fix] and [GsvMessage] streams.
 * The external adapter owns reconnection; the source itself makes a single attempt.
 *
 * To add support for a new transport (e.g. u-blox over Bluetooth), implement this
 * interface and pass the instance to a new external adapter registered in AppModule.
 */
interface NmeaSource : Startable {
    fun parsedFixes(): SharedFlow<Fix>
    fun gsvStream(): SharedFlow<GsvMessage>
    /** Emits once for every raw NMEA sentence received, before parsing. */
    fun rawNmeaEvents(): SharedFlow<Unit>
}

/**
 * Optional interface for adapters that expose a raw-NMEA activity stream.
 * [com.example.surveyingapp.gnss.bus.FixSwitchboard] forwards it so observers can distinguish
 * "socket connected but no parsed fix" from "no NMEA data flowing at all."
 */
interface RawNmeaProvider {
    val rawNmea: SharedFlow<Unit>
}

/** Satellite data entry from a single GSV sentence, normalised for SatelliteInventory. */
data class GsvMessage(
    val constellation: String,
    val entries: List<GsvEntry>
)

data class GsvEntry(
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Double?,
    val usedInFix: Boolean
)
