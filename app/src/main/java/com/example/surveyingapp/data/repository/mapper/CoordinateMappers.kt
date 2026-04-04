package com.example.surveyingapp.data.repository.mapper

import com.example.surveyingapp.data.local.db.DbConstants
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.domain.model.CorrectionSource
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import java.util.Locale
import java.util.UUID

// ---------- Helpers: enum <-> domain string ----------

private fun providerEnumToDomainString(p: Provider): String = when (p) {
    Provider.INTERNAL     -> DbConstants.PROVIDER_FUSED   // "fused"
    Provider.RS2_EXTERNAL -> DbConstants.PROVIDER_RS2_TCP // "rs2-tcp" (or could be a new constant)
    Provider.RS2_BT       -> DbConstants.PROVIDER_RS2_BT  // "rs2-bt"
    Provider.RS2_TCP      -> DbConstants.PROVIDER_RS2_TCP // "rs2-tcp"
    Provider.OTHER        -> "other"
}

private fun providerDomainStringToEnum(s: String?): Provider = when (s?.lowercase(Locale.US)) {
    DbConstants.PROVIDER_FUSED, "internal", "fused" -> Provider.INTERNAL
    DbConstants.PROVIDER_RS2_BT, "rs2-bt"          -> Provider.RS2_BT
    DbConstants.PROVIDER_RS2_TCP, "rs2-tcp"        -> Provider.RS2_TCP
    "rs2-external"                                  -> Provider.RS2_EXTERNAL
    else                                            -> Provider.OTHER
}

private fun rtkEnumToDomainString(rtk: RtkStatus?): String? = rtk?.name
private fun rtkDomainStringToEnum(s: String?): RtkStatus? =
    s?.let { runCatching { RtkStatus.valueOf(it.uppercase(Locale.US)) }.getOrNull() }

private fun corrEnumToDomainString(c: CorrectionSource?): String? = c?.name
private fun corrDomainStringToEnum(s: String?): CorrectionSource? =
    s?.let { runCatching { CorrectionSource.valueOf(it.uppercase(Locale.US)) }.getOrNull() }

// ---------- Builders / Mappers ----------

/**
 * Build a CoordinateEntity from a captured point + the latest Fix metadata.
 * Use when user creates a brand-new point from a live fix.
 */
fun toEntityFromFix(
    id: String = UUID.randomUUID().toString(),
    name: String,
    icon: String,
    color: Int,
    note: String?,
    fix: Fix
): CoordinateEntity {
    val ellipAlt = fix.altEllipsoidalM ?: 0.0
    val mslAlt = fix.altMslM ?: 0.0

    return CoordinateEntity(
        id = id,
        name = name,
        latitude = fix.latDeg,
        longitude = fix.lonDeg,
        altitude = ellipAlt,                       // store ellipsoidal as primary
        timestamp = fix.timeUtc.toEpochMilli(),    // Convert Instant to Long
        icon = icon,
        color = color,

        // ENTITY expects enums:
        provider = fix.provider,                   // Provider enum
        rtkStatus = fix.rtkStatus,                 // RtkStatus? enum
        satsUsed = fix.satsUsed,
        hdop = fix.hDop,
        horizontalAccuracyM = fix.hAccM,
        verticalAccuracyM = fix.vAccM,
        correctionSource = null,                   // Not available in Fix class
        correctionAgeS = fix.diffAgeS,

        altitudeMsl = mslAlt,
        geoidSeparationM = fix.geoidSeparationM,
        crsEpsg = 4326,                           // Default to WGS84

        easting = null,
        northing = null,
        utmZone = null,

        note = note,
        averagedSamples = null,
        averageDurationMs = null,
        stdLatM = null,                           // Not available in Fix class
        stdLonM = null,                           // Not available in Fix class
        stdAltM = null,                           // Not available in Fix class

        sourceDevice = null,
        appVersion = null
    )
}

/**
 * TEMP compatibility wrapper, in case other call sites still use the old name.
 * Prefer toEntityFromFix(...) to avoid confusion with the Coordinate extension.
 */
@Deprecated("Use toEntityFromFix(...)")
fun toEntity(
    id: String = UUID.randomUUID().toString(),
    name: String,
    icon: String,
    color: Int,
    note: String?,
    fix: Fix
): CoordinateEntity = toEntityFromFix(id, name, icon, color, note, fix)

/** Map DB row back to your domain Coordinate (domain uses strings). */
fun CoordinateEntity.toDomain(): Coordinate = Coordinate(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,                // ellipsoidal stored as altitude
    timestamp = timestamp,
    icon = icon,
    color = color,

    // Convert enums -> strings for domain:
    provider = providerEnumToDomainString(provider),
    rtkStatus = rtkEnumToDomainString(rtkStatus),
    satsUsed = satsUsed,
    hdop = hdop,
    horizontalAccuracyM = horizontalAccuracyM,
    verticalAccuracyM = verticalAccuracyM,
    correctionSource = corrEnumToDomainString(correctionSource),
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

/** New point/update coming from the domain Coordinate (strings) to the DB (enums). */
fun Coordinate.toEntity(): CoordinateEntity =
    CoordinateEntity(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        timestamp = timestamp,
        icon = icon,
        color = color,

        // Convert strings -> enums for entity:
        provider = providerDomainStringToEnum(provider),
        rtkStatus = rtkDomainStringToEnum(rtkStatus),
        satsUsed = satsUsed,
        hdop = hdop,
        horizontalAccuracyM = horizontalAccuracyM,
        verticalAccuracyM = verticalAccuracyM,
        correctionSource = corrDomainStringToEnum(correctionSource),
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
