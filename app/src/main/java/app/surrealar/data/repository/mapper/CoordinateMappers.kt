package app.surrealar.data.repository.mapper

import app.surrealar.data.local.db.ProviderTokens
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.domain.model.CorrectionSource
import app.surrealar.domain.model.Coordinate
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.util.Locale
import java.util.UUID

// ---------- Helpers: enum <-> domain string ----------

private fun providerEnumToDomainString(p: Provider): String = ProviderTokens.toDomain(p)

// Current and legacy ("rs2-…") tokens; see ProviderTokens.fromDomain.
private fun providerDomainStringToEnum(s: String?): Provider = ProviderTokens.fromDomain(s)

private fun rtkEnumToDomainString(rtk: RtkStatus?): String? = rtk?.name
// Same aliases as the Room converter ("RTK_FIXED", "FIXED", "DR", …), so a status string written by
// any path maps instead of silently becoming null.
private fun rtkDomainStringToEnum(s: String?): RtkStatus? =
    app.surrealar.data.local.db.Converters.toRtkStatus(s)

private fun corrEnumToDomainString(c: CorrectionSource?): String? = c?.name

/**
 * Exact enum names map directly. Anything else non-blank — e.g. the station descriptions older
 * captures produced ("Base Station 1116", "RTCM", "Station …") — means corrections were applied
 * from a source whose transport wasn't reported: [CorrectionSource.UNKNOWN], not null.
 */
private fun corrDomainStringToEnum(s: String?): CorrectionSource? {
    val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { CorrectionSource.valueOf(v.uppercase(Locale.US)) }.getOrNull()
        ?: CorrectionSource.UNKNOWN
}

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
    modelPlacementOrigin = modelPlacementOrigin,
    modelMoveEastM = modelMoveEastM,
    modelMoveNorthM = modelMoveNorthM,
    modelMoveUpM = modelMoveUpM,
    sourceFrame = sourceFrame,
    receiverFrame = receiverFrame,
    coordinateEpoch = coordinateEpoch,
    orthometricHeightSource = orthometricHeightSource,
    orthometricModel = orthometricModel,
)

/**
 * Maps a domain [Coordinate] back to a [CoordinateEntity] for DB writes.
 *
 * Invariants preserved here:
 * - the provider string round-trips through [Provider] without degrading (legacy `rs2-…` tokens are
 *   read as the matching receiver-neutral value);
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
        modelPlacementOrigin = modelPlacementOrigin,
        modelMoveEastM = modelMoveEastM,
        modelMoveNorthM = modelMoveNorthM,
        modelMoveUpM = modelMoveUpM,
        sourceFrame = sourceFrame,
        receiverFrame = receiverFrame,
        coordinateEpoch = coordinateEpoch,
        orthometricHeightSource = orthometricHeightSource,
        orthometricModel = orthometricModel,
    )
