package app.surrealar.domain.model

import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import io.github.pennstatecs.geokit.gnss.capture.CaptureResult
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.projection.Utm
import java.time.Duration

/**
 * Factory + helpers for constructing Coordinate domain models.
 *
 * The live averaged-capture path is [fromCaptureResult] (from a [CaptureResult], whose position
 * fields are non-null). [fromFix] targets the [FixSnapshot] accumulator and is kept for compatibility.
 *
 * Semantics:
 * - altitude = ellipsoidal height (metres)
 * - altitudeMsl + geoidSeparationM are stored when available
 *
 * Missing latitude/longitude/altitude in a [FixSnapshot] are mapped to [Double.NaN] — a poison value,
 * never a plausible measurement. [app.surrealar.domain.coordinates.CoordinateValidator] rejects
 * non-finite lat/lon/altitude, so such a coordinate cannot pass the save gate.
 */
object CoordinateFactory {

    /**
     * Builds a [Coordinate] from a [FixSnapshot].
     *
     * @param provider The display/storage string for the GNSS source (e.g. "fused", "external-tcp").
     *                 Defaults to "fused". Do NOT pass `fix.timestampSource.name` — that gives
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
        // Local copies: FixSnapshot lives in geokit-gnss, so its properties can't be smart-cast here.
        val fixLat = fix.lat
        val fixLon = fix.lon
        val (utmEasting, utmNorthing, utmZone) = if (fixLat != null && fixLon != null &&
            !fixLat.isNaN() && !fixLon.isNaN()) {
            try {
                val utmCoord = Utm.forward(fixLat, fixLon)
                Triple(utmCoord.eastingM, utmCoord.northingM, utmCoord.zoneLabel)
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
        result: CaptureResult,
        captureMethod: String? = null,
        sourceDevice: String? = null,
        modelId: String? = null,
        iconKey: String? = null,
        antennaHeightM: Double = 0.0,
        /** EPSG of the datum the result's lat/lon/height are in (4979/4326 WGS84, 6319 NAD83(2011)). */
        crsEpsg: Int = 4326
    ): Coordinate {
        // Same table as CoordinateMappers, so a captured coordinate round-trips through the DB
        // without its provider degrading.
        val providerStr = app.surrealar.data.local.db.ProviderTokens.toDomain(provider)
        val utm = runCatching { Utm.forward(result.latDeg, result.lonDeg) }.getOrNull()
        // Wall-clock save time for the audit fields (createdAt/updatedAt).
        val capturedAtMs = result.endedAt.toEpochMilli()
        // The coordinate's timestamp is the last accepted fix's own time, so it matches the
        // timestampSource label. Without one, fall back to the device clock and say so.
        val fixTimeMs = result.lastFixTimeUtc?.toEpochMilli()
        val timestampMs = fixTimeMs ?: capturedAtMs
        val timestampSourceName =
            if (fixTimeMs != null) result.timestampSource?.name else TimestampSource.DEVICE.name
        // Antenna/pole offset: the receiver reports the antenna phase-centre altitude; subtract the
        // pole height so the stored altitude is the ground mark. Applied to both ellipsoidal and MSL
        // (geoid separation is a difference, so it is unchanged). Recorded for provenance when > 0.
        val appliedOffset = antennaHeightM.takeIf { it != 0.0 }
        return Coordinate(
            id                  = id,
            name                = name,
            latitude            = result.latDeg,
            longitude           = result.lonDeg,
            altitude            = result.altEllipsoidalM - antennaHeightM,
            timestamp           = timestampMs,
            icon                = iconId,
            color               = color,
            provider            = providerStr,
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
            correctionSource    = deriveCorrectionsSource(result.correctionStationId),
            // Final-fix metadata preserved from the last accepted epoch (no schema change; these
            // Coordinate fields already exist). stdLat/stdLon map from North/East respectively.
            altitudeMsl         = result.altMslM?.let { it - antennaHeightM },
            geoidSeparationM    = result.geoidSeparationM,
            antennaHeightM      = appliedOffset,
            timestampSource     = timestampSourceName,
            multipathIndex      = result.multipathIndex,
            stdLatM             = result.stdDevNorthM,
            stdLonM             = result.stdDevEastM,
            stdAltM             = result.stdDevUpM,
            crsEpsg             = crsEpsg,
            easting             = utm?.eastingM,
            northing            = utm?.northingM,
            utmZone             = utm?.zoneLabel,
            averagedSamples     = result.samples,
            averageDurationMs   = Duration.between(result.startedAt, result.endedAt).toMillis(),
            note                = note,
            captureMethod       = captureMethod,
            sourceDevice        = sourceDevice,
            // v10 explicit model association + audit timestamps
            modelId             = modelId,
            iconKey             = iconKey,
            renderEnabled       = true,
            createdAt           = capturedAtMs,
            updatedAt           = capturedAtMs
        )
    }

    /**
     * A GGA station ID says corrections came from a base, but not over what (NTRIP, LoRa, …), so the
     * source is the existing enum's UNKNOWN; the station itself is kept in correctionStationId.
     * Must be an enum name so it survives the DB mapper (free text used to become null).
     */
    private fun deriveCorrectionsSource(stationId: String?): String? =
        if (stationId.isNullOrBlank()) null else CorrectionSource.UNKNOWN.name
}

/** Quick validation helpers. */
val Coordinate.isValid: Boolean
    get() = !latitude.isNaN() && !longitude.isNaN()
