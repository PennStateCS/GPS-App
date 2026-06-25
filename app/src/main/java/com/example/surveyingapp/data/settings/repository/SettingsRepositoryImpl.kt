package com.example.surveyingapp.data.settings.repository

import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.DeveloperSettings
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.settings.model.AppThemeMode
import com.example.surveyingapp.settings.model.AppearanceSettings
import com.example.surveyingapp.gnss.settings.GnssReceiverSettings
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
        combine(local.arShowOffscreenArrows, local.arModelScale, local.arDebugToolsEnabled) { arrows, scale, debug -> Triple(arrows, scale, debug) }
    ) { altitude, filterIdx, debug, labels, (arrows, scale, debugTools) ->
        ArDisplaySettings(
            altitudeMode         = altitude,
            distanceFilterIndex  = filterIdx.coerceIn(0, 3),
            showDebugOverlay     = debug,
            showLabels           = labels,
            showOffscreenArrows  = arrows,
            modelScale           = scale.toFloatOrNull()?.coerceIn(0.1f, 10.0f) ?: 2.0f,
            showArDebugTools     = debugTools
        )
    }

    override suspend fun setArDisplaySettings(settings: ArDisplaySettings) {
        local.setArAltitudeMode(settings.altitudeMode)
        local.setArDistanceFilterIndex(settings.distanceFilterIndex.coerceIn(0, 3))
        local.setArShowDebugOverlay(settings.showDebugOverlay)
        local.setArShowLabels(settings.showLabels)
        local.setArShowOffscreenArrows(settings.showOffscreenArrows)
        local.setArModelScale(settings.modelScale.coerceIn(0.1f, 10.0f).toString())
        local.setArDebugToolsEnabled(settings.showArDebugTools)
    }

    // Coordinate display
    override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = combine(
        local.showAccuracyIndicators,
        local.defaultCoordinateNamePrefix,
        local.autoIncrementCoordinateNames
    ) { accuracy, prefix, autoInc ->
        CoordinateDisplaySettings(
            showAccuracyIndicators = accuracy,
            defaultNamePrefix      = prefix ?: "Point",
            autoIncrementNames     = autoInc
        )
    }

    override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) {
        local.setShowAccuracyIndicators(settings.showAccuracyIndicators)
        local.setDefaultCoordinateNamePrefix(settings.defaultNamePrefix)
        local.setAutoIncrementCoordinateNames(settings.autoIncrementNames)
    }

    // Mock location publishing
    override val mockLocationEnabled: Flow<Boolean> = local.mockLocationEnabled
    override suspend fun setMockLocationEnabled(enabled: Boolean) { local.setMockLocationEnabled(enabled) }

    // GNSS receiver settings
    override val gnssReceiverSettings: Flow<GnssReceiverSettings> =
        local.highAccuracy.map { GnssReceiverSettings(highAccuracy = it) }

    override suspend fun setGnssReceiverSettings(settings: GnssReceiverSettings) {
        local.setHighAccuracy(settings.highAccuracy)
    }

    // Developer settings
    override val developerSettings: Flow<DeveloperSettings> =
        local.developerToolsEnabled.map { DeveloperSettings(developerToolsEnabled = it) }

    override suspend fun setDeveloperSettings(settings: DeveloperSettings) {
        local.setDeveloperToolsEnabled(settings.developerToolsEnabled)
    }

    // Appearance
    override val appearanceSettings: Flow<AppearanceSettings> =
        combine(
            local.appThemeModeRaw,
            local.showLiveGnssStatusBar,
            local.keepScreenAwake,
            local.maxBrightnessWhileOpen
        ) { theme, showStrip, keepAwake, maxBright ->
            AppearanceSettings(
                themeMode              = theme.toAppThemeMode(),
                showLiveGnssStatusBar  = showStrip,
                keepScreenAwake        = keepAwake,
                maxBrightnessWhileOpen = maxBright
            )
        }

    override suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        local.setAppThemeModeString(settings.themeMode.name)
        local.setShowLiveGnssStatusBar(settings.showLiveGnssStatusBar)
        local.setKeepScreenAwake(settings.keepScreenAwake)
        local.setMaxBrightnessWhileOpen(settings.maxBrightnessWhileOpen)
    }

    private fun String.toAppThemeMode(): AppThemeMode = when (this) {
        "LIGHT"  -> AppThemeMode.LIGHT
        "DARK"   -> AppThemeMode.DARK
        else     -> AppThemeMode.SYSTEM
    }

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
