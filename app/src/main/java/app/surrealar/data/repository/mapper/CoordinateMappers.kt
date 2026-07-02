package app.surrealar.data.repository.mapper

import app.surrealar.data.local.db.DbConstants
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.domain.model.CorrectionSource
import app.surrealar.domain.model.Coordinate
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import java.util.Locale
import java.util.UUID

// ---------- Helpers: enum <-> domain string ----------

private fun providerEnumToDomainString(p: Provider): String = when (p) {
    Provider.INTERNAL     -> DbConstants.PROVIDER_FUSED        // "fused"
    Provider.RS2_EXTERNAL -> DbConstants.PROVIDER_RS2_EXTERNAL // "rs2-external" — distinct so it survives round-trip
    Provider.RS2_BT       -> DbConstants.PROVIDER_RS2_BT       // "rs2-bt"
    Provider.RS2_TCP      -> DbConstants.PROVIDER_RS2_TCP      // "rs2-tcp"
    Provider.OTHER        -> "other"
    Provider.MODEL        -> "model"
}

private fun providerDomainStringToEnum(s: String?): Provider = when (s?.lowercase(Locale.US)) {
    DbConstants.PROVIDER_FUSED, "internal", "fused", "internal_gps" -> Provider.INTERNAL
    DbConstants.PROVIDER_RS2_BT, "rs2-bt", "rs2_bt"                -> Provider.RS2_BT
    DbConstants.PROVIDER_RS2_TCP, "rs2-tcp", "rs2_tcp"             -> Provider.RS2_TCP
    "rs2-external", "rs2_external"                                  -> Provider.RS2_EXTERNAL
    "model"                                                         -> Provider.MODEL
    else                                                            -> Provider.OTHER
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
 *
 * Requires a real ellipsoidal height: the stored `altitude` column is non-nullable, and a missing
 * altitude must not be silently saved as `0.0` (that is indistinguishable from a true sea-level
 * measurement and corrupts AR/survey placement). Callers must gate on `fix.altEllipsoidalM != null`
 * before capture; passing a fix without it throws rather than fabricating a value.
 */
fun toEntityFromFix(
    id: String = UUID.randomUUID().toString(),
    name: String,
    icon: String,
    color: Int,
    note: String?,
    fix: Fix
): CoordinateEntity = CoordinateEntity(
    id = id,
    name = name,
    latitude = fix.latDeg,
    longitude = fix.lonDeg,
    altitude = requireNotNull(fix.altEllipsoidalM) {
        "Cannot save a coordinate without ellipsoidal height; capture must wait for altitude " +
            "instead of storing 0.0."
    },
    timestamp = fix.timeUtc.toEpochMilli(),
    icon = icon,
    color = color,

    provider = fix.provider,
    rtkStatus = fix.rtkStatus,
    satsUsed = fix.satsUsed,
    satsVisible = fix.satsVisible,
    hdop = fix.hDop,
    vDop = fix.vDop,
    pDop = fix.pDop,
    horizontalAccuracyM = fix.hAccM,
    verticalAccuracyM = fix.vAccM,
    correctionSource = null,                    // CorrectionSource enum not carried in Fix
    correctionAgeS = fix.diffAgeS,
    correctionStationId = fix.correctionStationId,
    speedMps = fix.speedMps,
    courseDeg = fix.courseDeg,
    timestampSource = fix.timestampSource.name,
    multipathIndex = fix.multipathIndex,

    altitudeMsl = fix.altMslM,
    geoidSeparationM = fix.geoidSeparationM,
    crsEpsg = 4326,

    easting = null,
    northing = null,
    utmZone = null,

    note = note,
    averagedSamples = null,
    averageDurationMs = null,
    stdLatM = fix.stdDevNorthM,
    stdLonM = fix.stdDevEastM,
    stdAltM = fix.stdDevUpM,

    sourceDevice = null,
    appVersion = null
)

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

/** Map a DB row back to the domain Coordinate. */
fun CoordinateEntity.toDomain(): Coordinate = Coordinate(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    timestamp = timestamp,
    icon = icon,
    color = color,

    provider = providerEnumToDomainString(provider),
    rtkStatus = rtkEnumToDomainString(rtkStatus),
    satsUsed = satsUsed,
    satsVisible = satsVisible,
    hdop = hdop,
    vDop = vDop,
    pDop = pDop,
    horizontalAccuracyM = horizontalAccuracyM,
    verticalAccuracyM = verticalAccuracyM,
    correctionSource = corrEnumToDomainString(correctionSource),
    correctionAgeS = correctionAgeS,
    correctionStationId = correctionStationId,
    speedMps = speedMps,
    courseDeg = courseDeg,
    timestampSource = timestampSource,
    multipathIndex = multipathIndex,

    altitudeMsl = altitudeMsl,
    geoidSeparationM = geoidSeparationM,
    antennaHeightM = antennaHeightM,
    crsEpsg = crsEpsg,

    easting = easting,
    northing = northing,
    utmZone = utmZone,

    note = note,
    captureMethod = captureMethod,
    averagedSamples = averagedSamples,
    averageDurationMs = averageDurationMs,
    stdLatM = stdLatM,
    stdLonM = stdLonM,
    stdAltM = stdAltM,

    sourceDevice = sourceDevice,
    appVersion = appVersion,

    // v10 fields
    modelId = modelId,
    iconKey = iconKey,
    renderEnabled = renderEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelScale = modelScale,
    modelYawDeg = modelYawDeg,
    modelPitchDeg = modelPitchDeg,
    modelRollDeg = modelRollDeg,
    modelVerticalOffsetM = modelVerticalOffsetM,
    modelOriginOffsetXM = modelOriginOffsetXM,
    modelOriginOffsetYM = modelOriginOffsetYM,
    modelOriginOffsetZM = modelOriginOffsetZM,
    modelPlacementOrigin = modelPlacementOrigin
)

/**
 * Maps a domain [Coordinate] back to a [CoordinateEntity] for DB writes.
 *
 * Invariants preserved here:
 * - the provider string round-trips through [Provider] without degrading (notably `rs2-external`
 *   stays `RS2_EXTERNAL`, not `RS2_TCP`);
 * - `createdAt`/`updatedAt` fall back to `timestamp` when unset (0), so rows written through paths
 *   that don't set audit times (e.g. legacy import) still get sensible values.
 *
 * Every field must round-trip with [toDomain]; changing one side requires changing the other.
 */
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

        provider = providerDomainStringToEnum(provider),
        rtkStatus = rtkDomainStringToEnum(rtkStatus),
        satsUsed = satsUsed,
        satsVisible = satsVisible,
        hdop = hdop,
        vDop = vDop,
        pDop = pDop,
        horizontalAccuracyM = horizontalAccuracyM,
        verticalAccuracyM = verticalAccuracyM,
        correctionSource = corrDomainStringToEnum(correctionSource),
        correctionAgeS = correctionAgeS,
        correctionStationId = correctionStationId,
        speedMps = speedMps,
        courseDeg = courseDeg,
        timestampSource = timestampSource,
        multipathIndex = multipathIndex,

        altitudeMsl = altitudeMsl,
        geoidSeparationM = geoidSeparationM,
        antennaHeightM = antennaHeightM,
        crsEpsg = crsEpsg,

        easting = easting,
        northing = northing,
        utmZone = utmZone,

        note = note,
        captureMethod = captureMethod,
        averagedSamples = averagedSamples,
        averageDurationMs = averageDurationMs,
        stdLatM = stdLatM,
        stdLonM = stdLonM,
        stdAltM = stdAltM,

        sourceDevice = sourceDevice,
        appVersion = appVersion,

        // v10 fields. createdAt/updatedAt fall back to `timestamp` so rows written through
        // paths that don't set them yet (e.g. import) still get sensible audit times.
        modelId = modelId,
        iconKey = iconKey,
        renderEnabled = renderEnabled,
        createdAt = if (createdAt != 0L) createdAt else timestamp,
        updatedAt = if (updatedAt != 0L) updatedAt else timestamp,
        modelScale = modelScale,
        modelYawDeg = modelYawDeg,
        modelPitchDeg = modelPitchDeg,
        modelRollDeg = modelRollDeg,
        modelVerticalOffsetM = modelVerticalOffsetM,
        modelOriginOffsetXM = modelOriginOffsetXM,
        modelOriginOffsetYM = modelOriginOffsetYM,
        modelOriginOffsetZM = modelOriginOffsetZM,
        modelPlacementOrigin = modelPlacementOrigin
    )
