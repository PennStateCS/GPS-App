package com.example.surveyingapp.data.settings.repository

import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSettings
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.settings.ArDisplaySettings
import com.example.surveyingapp.gnss.settings.CoordinateDisplaySettings
import com.example.surveyingapp.gnss.settings.DiagnosticsSettings
import com.example.surveyingapp.gnss.settings.GnssCaptureSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Maps raw DataStore preference primitives to domain enums/models and vice versa.
 */
class SettingsRepositoryImpl(private val local: SettingsLocalDataSource) : SettingsRepository {
    private val sourceFlow = local.locationSourceRaw.map { it.toLocationSourceType() }
    private val connTypeFlow = local.externalConnTypeRaw.map { it.toExternalConnectionType() }

    override val locationSource: Flow<LocationSourceType> = sourceFlow
    override val externalConnType: Flow<ExternalConnectionType> = connTypeFlow
    override val externalBtAddress = local.externalBtAddress
    override val externalTcpHost = local.externalTcpHost
    override val externalTcpPort = local.externalTcpPort
    override val externalTcpName = local.externalTcpName

    // NMEA logging settings
    override val nmeaLoggingEnabled = local.nmeaLoggingEnabled
    override val nmeaLogMaxFileSizeMB = local.nmeaLogMaxFileSizeMB

    override val locationSettings: Flow<LocationSettings>
        get() = combine(
            locationSource,
            externalConnType,
            externalBtAddress,
            externalTcpHost,
            externalTcpPort
        ) { source, connType, btAddr, tcpHost, tcpPort ->
            LocationSettings(
                source = source,
                connectionType = connType,
                btDeviceAddress = btAddr,
                tcpHost = tcpHost,
                tcpPort = tcpPort
            )
        }

    override suspend fun setLocationSource(v: LocationSourceType) {
        local.setLocationSourceString(v.toPrefString())
    }

    override suspend fun setExternalConnType(v: ExternalConnectionType) {
        local.setExternalConnTypeString(v.toPrefString())
    }

    override suspend fun setExternalTcp(host: String, port: Int, name: String) {
        local.setExternalTcp(host, port)
        if (name.isNotBlank()) {
            local.setExternalTcpName(name)
        }
    }

    override suspend fun clearExternalTcp() {
        local.clearExternalTcp()
    }

    // NMEA logging methods
    override suspend fun setNmeaLoggingEnabled(enabled: Boolean) {
        local.setNmeaLoggingEnabled(enabled)
    }

    override suspend fun setNmeaLogMaxFileSizeMB(sizeMB: Int) {
        local.setNmeaLogMaxFileSizeMB(sizeMB)
    }

    // GNSS capture averaging policy
    override val gnssCaptureSettings: Flow<GnssCaptureSettings> = combine(
        local.gnssCaptureRtkStatus,
        local.gnssCaptureMinDurationSec,
        local.gnssCaptureMaxDurationSec,
        local.gnssCaptureMinSamples,
        combine(local.gnssCaptureMaxFixAgeSec, local.gnssCaptureMaxDiffAgeSec) { fix, diff -> fix to diff }
    ) { rtkStr, minDur, maxDur, minSamples, (maxFix, maxDiff) ->
        GnssCaptureSettings(
            requiredMinStatus = rtkStr.toRtkStatus(),
            minDurationSec    = minDur,
            maxDurationSec    = maxDur,
            minSamples        = minSamples,
            maxFixAgeSec      = maxFix,
            maxDiffAgeSec     = maxDiff
        )
    }

    override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {
        local.setGnssCaptureRtkStatus(settings.requiredMinStatus.name)
        local.setGnssCaptureMinDurationSec(settings.minDurationSec)
        local.setGnssCaptureMaxDurationSec(settings.maxDurationSec)
        local.setGnssCaptureMinSamples(settings.minSamples)
        local.setGnssCaptureMaxFixAgeSec(settings.maxFixAgeSec)
        local.setGnssCaptureMaxDiffAgeSec(settings.maxDiffAgeSec)
    }

    // AR Display
    override val arDisplaySettings: Flow<ArDisplaySettings> = combine(
        local.arAltitudeMode,
        local.arDistanceFilterIndex,
        local.arShowDebugOverlay,
        local.arShowLabels,
        combine(local.arShowOffscreenArrows, local.arModelScale) { arrows, scale -> arrows to scale }
    ) { altitude, filterIdx, debug, labels, (arrows, scale) ->
        ArDisplaySettings(
            altitudeMode         = altitude,
            distanceFilterIndex  = filterIdx.coerceIn(0, 3),
            showDebugOverlay     = debug,
            showLabels           = labels,
            showOffscreenArrows  = arrows,
            modelScale           = scale.toFloatOrNull()?.coerceIn(0.1f, 10.0f) ?: 2.0f
        )
    }

    override suspend fun setArDisplaySettings(settings: ArDisplaySettings) {
        local.setArAltitudeMode(settings.altitudeMode)
        local.setArDistanceFilterIndex(settings.distanceFilterIndex.coerceIn(0, 3))
        local.setArShowDebugOverlay(settings.showDebugOverlay)
        local.setArShowLabels(settings.showLabels)
        local.setArShowOffscreenArrows(settings.showOffscreenArrows)
        local.setArModelScale(settings.modelScale.coerceIn(0.1f, 10.0f).toString())
    }

    // Coordinate display & units
    override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = combine(
        local.coordinateDisplayFormat,
        local.unitsDistance,
        local.showAccuracyIndicators,
        local.showRtkStatusBadges,
        combine(local.defaultCoordinateNamePrefix, local.autoIncrementCoordinateNames) { prefix, auto -> prefix to auto }
    ) { format, units, accuracy, rtkBadges, (prefix, autoInc) ->
        CoordinateDisplaySettings(
            format                = format ?: "DECIMAL_DEGREES",
            distanceUnits         = units  ?: "METERS",
            showAccuracyIndicators = accuracy,
            showRtkStatusBadges   = rtkBadges,
            defaultNamePrefix     = prefix ?: "Point",
            autoIncrementNames    = autoInc
        )
    }

    override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) {
        local.setCoordinateDisplayFormat(settings.format)
        local.setUnitsDistance(settings.distanceUnits)
        local.setShowAccuracyIndicators(settings.showAccuracyIndicators)
        local.setShowRtkStatusBadges(settings.showRtkStatusBadges)
        local.setDefaultCoordinateNamePrefix(settings.defaultNamePrefix)
        local.setAutoIncrementCoordinateNames(settings.autoIncrementNames)
    }

    // Diagnostics & logging
    override val diagnosticsSettings: Flow<DiagnosticsSettings> = combine(
        local.nmeaLoggingEnabled,
        local.nmeaLogMaxFileSizeMB,
        local.diagShowRawNmea,
        local.diagShowSatelliteDiag,
        combine(
            local.diagLogRawNmeaLines,
            local.diagLogParsedFixes,
            local.diagLogSatelliteDetails
        ) { raw, fixes, sat -> Triple(raw, fixes, sat) }
    ) { logging, size, showRaw, showSat, (logRaw, logFixes, logSat) ->
        DiagnosticsSettings(
            nmeaLoggingEnabled       = logging,
            nmeaLogMaxFileSizeMB     = size,
            showRawNmea              = showRaw,
            showSatelliteDiagnostics = showSat,
            logRawNmeaLines          = logRaw,
            logParsedFixes           = logFixes,
            logSatelliteDetails      = logSat
        )
    }

    override suspend fun setDiagnosticsSettings(settings: DiagnosticsSettings) {
        local.setNmeaLoggingEnabled(settings.nmeaLoggingEnabled)
        local.setNmeaLogMaxFileSizeMB(settings.nmeaLogMaxFileSizeMB.coerceAtLeast(1))
        local.setDiagShowRawNmea(settings.showRawNmea)
        local.setDiagShowSatelliteDiag(settings.showSatelliteDiagnostics)
        local.setDiagLogRawNmeaLines(settings.logRawNmeaLines)
        local.setDiagLogParsedFixes(settings.logParsedFixes)
        local.setDiagLogSatelliteDetails(settings.logSatelliteDetails)
    }

    // Mock location publishing
    override val mockLocationEnabled: Flow<Boolean> = local.mockLocationEnabled
    override suspend fun setMockLocationEnabled(enabled: Boolean) { local.setMockLocationEnabled(enabled) }

    private fun String.toRtkStatus(): RtkStatus = when (this) {
        "FIX"    -> RtkStatus.FIX
        "FLOAT"  -> RtkStatus.FLOAT
        "DGPS"   -> RtkStatus.DGPS
        "SINGLE" -> RtkStatus.SINGLE
        else     -> RtkStatus.FIX
    }

    private fun String?.toLocationSourceType(): LocationSourceType = when (this) {
        "INTERNAL" -> LocationSourceType.INTERNAL
        "EXTERNAL" -> LocationSourceType.EXTERNAL
        "SIMULATOR" -> LocationSourceType.SIMULATOR
        else -> LocationSourceType.INTERNAL
    }

    private fun LocationSourceType.toPrefString(): String = when (this) {
        LocationSourceType.INTERNAL -> "INTERNAL"
        LocationSourceType.EXTERNAL -> "EXTERNAL"
        LocationSourceType.SIMULATOR -> "SIMULATOR"
    }

    private fun String?.toExternalConnectionType(): ExternalConnectionType = when (this) {
        "BT" -> ExternalConnectionType.BT
        "TCP" -> ExternalConnectionType.TCP
        "USB" -> ExternalConnectionType.USB
        "RADIO" -> ExternalConnectionType.RADIO
        "WIFI" -> ExternalConnectionType.WIFI
        else -> ExternalConnectionType.BT
    }

    private fun ExternalConnectionType.toPrefString(): String = when (this) {
        ExternalConnectionType.BT -> "BT"
        ExternalConnectionType.TCP -> "TCP"
        ExternalConnectionType.USB -> "USB"
        ExternalConnectionType.RADIO -> "RADIO"
        ExternalConnectionType.WIFI -> "WIFI"
    }
}
