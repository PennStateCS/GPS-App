package com.example.surveyingapp.domain.model

import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.util.UtmConverter

/**
 * Factory + helpers for constructing Coordinate domain models.
 *
 * Semantics:
 * - altitude = ellipsoidal height (meters)
 * - altitudeMsl + geoidSeparationM are stored when available
 */
object CoordinateFactory {

    fun fromFix(
        id: String,
        name: String,
        fix: FixSnapshot,
        icon: String,
        color: Int,
        note: String? = null,
        averagedSamples: Int? = null,
        averageDurationMs: Long? = null,
        sourceDevice: String? = null,
        appVersion: String? = null
    ): Coordinate {
        // Calculate UTM coordinates from lat/lon when available
        val (utmEasting, utmNorthing, utmZone) = if (fix.lat != null && fix.lon != null &&
            !fix.lat.isNaN() && !fix.lon.isNaN()) {
            try {
                val utmCoord = UtmConverter.latLonToUtm(fix.lat, fix.lon)
                Triple(utmCoord.easting, utmCoord.northing, utmCoord.utmZone)
            } catch (e: Exception) {
                Triple(null, null, null)
            }
        } else {
            Triple(null, null, null)
        }

        return Coordinate(
            id = id,
            name = name,
            latitude = fix.lat ?: Double.NaN,
            longitude = fix.lon ?: Double.NaN,
            altitude = fix.altEllipsoidal ?: Double.NaN,  // ellipsoidal by definition
            timestamp = fix.timestampMillis,
            icon = icon,
            color = color,
            provider = fix.timestampSource.name,
            rtkStatus = fix.rtkStatus,
            satsUsed = fix.satsUsed,
            satsVisible = fix.satellitesInView,
            hdop = fix.hdop,
            vDop = fix.vDop,
            pDop = fix.pDop,
            horizontalAccuracyM = fix.horizontalAccuracyM,
            verticalAccuracyM = fix.verticalAccuracyM,
            correctionSource = deriveCorrectionsSource(fix.correctionStationId),
            correctionAgeS = fix.correctionAgeS,
            correctionStationId = fix.correctionStationId,
            altitudeMsl = fix.altMsl,
            geoidSeparationM = fix.geoidSeparation,
            speedMps = fix.speedMps,
            courseDeg = fix.courseDeg,
            timestampSource = fix.timestampSource.name,
            multipathIndex = fix.multipathIndex,
            crsEpsg = 4326,
            easting = utmEasting,
            northing = utmNorthing,
            utmZone = utmZone,
            note = note,
            averagedSamples = averagedSamples,
            averageDurationMs = averageDurationMs,
            stdLatM = fix.stdLatM,
            stdLonM = fix.stdLonM,
            stdAltM = fix.stdAltM,
            sourceDevice = sourceDevice,
            appVersion = appVersion
        )
    }

    /**
     * Derive correction source description from station ID.
     */
    private fun deriveCorrectionsSource(stationId: String?): String? {
        return when {
            stationId.isNullOrBlank() -> null
            stationId.startsWith("RTCM") -> "RTCM"
            stationId.length == 4 -> "Base Station $stationId"
            else -> "Station $stationId"
        }
    }
}

/** Quick validation helpers. */
val Coordinate.isValid: Boolean
    get() = !latitude.isNaN() && !longitude.isNaN()
