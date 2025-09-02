package com.example.surveyingapp.data.repository.mapper

import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.domain.model.Coordinate

/**
 * Mapping extensions between persistence (Room) and domain models.
 */
fun CoordinateEntity.toDomain() = Coordinate(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    timestamp = timestamp,
    icon = icon,
    color = color,
    provider = provider,
    rtkStatus = rtkStatus,
    satsUsed = satsUsed,
    hdop = hdop,
    horizontalAccuracyM = horizontalAccuracyM,
    verticalAccuracyM = verticalAccuracyM,
    correctionSource = correctionSource,
    correctionAgeS = correctionAgeS,
    altitudeMsl = altitudeMsl,
    geoidSeparationM = geoidSeparationM,
    crsEpsg = crsEpsg,
    easting = easting,
    northing = northing,
    utmZone = utmZone,
    note = note,
    averagedSamples = averagedSamples,
    averageDurationMs = averageDurationMs,
    stdLatM = stdLatM,
    stdLonM = stdLonM,
    stdAltM = stdAltM,
    sourceDevice = sourceDevice,
    appVersion = appVersion
)

fun Coordinate.toEntity() = CoordinateEntity(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    timestamp = timestamp,
    icon = icon,
    color = color,
    provider = provider,
    rtkStatus = rtkStatus,
    satsUsed = satsUsed,
    hdop = hdop,
    horizontalAccuracyM = horizontalAccuracyM,
    verticalAccuracyM = verticalAccuracyM,
    correctionSource = correctionSource,
    correctionAgeS = correctionAgeS,
    altitudeMsl = altitudeMsl,
    geoidSeparationM = geoidSeparationM,
    crsEpsg = crsEpsg,
    easting = easting,
    northing = northing,
    utmZone = utmZone,
    note = note,
    averagedSamples = averagedSamples,
    averageDurationMs = averageDurationMs,
    stdLatM = stdLatM,
    stdLonM = stdLonM,
    stdAltM = stdAltM,
    sourceDevice = sourceDevice,
    appVersion = appVersion
)
