package com.example.surveyingapp.gnss.repo

import com.example.surveyingapp.gnss.capture.CaptureResult
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import java.util.UUID

/**
 * Builds a CoordinateEntity row from a finished capture.
 * Only fields we can justify from the capture context are populated.
 */
object Mappers {

    fun toEntity(
        name: String,
        note: String?,
        colorArgb: Int,
        iconId: String,
        result: CaptureResult,
        provider: Provider,
        sourceDevice: String?,
        appVersion: String?
    ): CoordinateEntity {
        return CoordinateEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            latitude = result.latDeg,
            longitude = result.lonDeg,
            altitude = result.altEllipsoidalM,

            timestamp = result.endedAt.toEpochMilli(),
            icon = iconId,
            color = colorArgb,

            provider = provider,
            rtkStatus = result.rtkStatus,
            satsUsed = result.satsUsed,
            hdop = result.hdop,
            horizontalAccuracyM = result.hAccM,
            verticalAccuracyM = result.vAccM,
            correctionSource = null,
            correctionAgeS = result.diffAgeS,

            altitudeMsl = null,
            geoidSeparationM = null,
            crsEpsg = 4326,

            easting = null,
            northing = null,
            utmZone = null,

            note = note,
            averagedSamples = result.samples,
            averageDurationMs = result.endedAt.toEpochMilli() - result.startedAt.toEpochMilli(),
            stdLatM = null,
            stdLonM = null,
            stdAltM = null,

            sourceDevice = sourceDevice,
            appVersion = appVersion
        )
    }

}
