package com.example.surveyingapp.domain.model

import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.capture.CaptureResult
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.util.UtmConverter
import java.time.Duration

/**
 * Factory + helpers for constructing Coordinate domain models.
 *
 * The preferred path for live captures is [toEntityFromFix] in CoordinateMappers,
 * which works directly with the typed [Fix] model. This factory targets the legacy
 * [FixSnapshot] accumulator and is kept for compatibility.
 *
 * Semantics:
 * - altitude = ellipsoidal height (metres)
 * - altitudeMsl + geoidSeparationM are stored when available
 */
object CoordinateFactory {

    /**
     * Builds a [Coordinate] from a [FixSnapshot].
     *
     * @param provider The display/storage string for the GNSS source (e.g. "fused", "rs2-tcp").
     *                 Defaults to "fused". Do NOT pass [fix.timestampSource.name] — that gives
     *                 the clock source ("DEVICE", "NMEA_ZDA"), not the GNSS receiver.
     */
    fun fromFix(
        id: String,
        name: String,
        fix: FixSnapshot,
        icon: String,
        color: Int,
        provider: String = "fused",
        note: String? = null,
        averagedSamples: Int? = null,
        averageDurationMs: Long? = null,
        sourceDevice: String? = null,
        appVersion: String? = null
    ): Coordinate {
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
            altitude = fix.altEllipsoidal ?: Double.NaN,
            timestamp = fix.timestampMillis,
            icon = icon,
            color = color,
            provider = provider,
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
     * Builds a [Coordinate] from a completed [CaptureResult].
     *
     * All [CaptureResult] quality fields are mapped to their [Coordinate] counterparts.
     * UTM easting, northing, and zone are computed from the averaged lat/lon.
     *
     * @param provider The GNSS source active during capture.
     */
    fun fromCaptureResult(
        id: String,
        name: String,
        note: String?,
        color: Int,
        iconId: String,
        provider: Provider,
        result: CaptureResult
    ): Coordinate {
        val utm = runCatching { UtmConverter.latLonToUtm(result.latDeg, result.lonDeg) }.getOrNull()
        return Coordinate(
            id                  = id,
            name                = name,
            latitude            = result.latDeg,
            longitude           = result.lonDeg,
            altitude            = result.altEllipsoidalM,
            timestamp           = result.endedAt.toEpochMilli(),
            icon                = iconId,
            color               = color,
            provider            = provider.name,
            rtkStatus           = result.rtkStatus?.name,
            satsUsed            = result.satsUsed,
            satsVisible         = result.satsVisible,
            hdop                = result.hdop,
            vDop                = result.vDop,
            pDop                = result.pDop,
            horizontalAccuracyM = result.hAccM,
            verticalAccuracyM   = result.vAccM,
            correctionAgeS      = result.diffAgeS,
            correctionStationId = result.correctionStationId,
            crsEpsg             = 4326,
            easting             = utm?.easting,
            northing            = utm?.northing,
            utmZone             = utm?.utmZone,
            averagedSamples     = result.samples,
            averageDurationMs   = Duration.between(result.startedAt, result.endedAt).toMillis(),
            note                = note
        )
    }

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
