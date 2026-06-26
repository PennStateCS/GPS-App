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
 *
 * **Ownership:** in production this is constructed exactly once, by
 * [com.example.surveyingapp.SurveyingApp.setupSettings], and exposed through Hilt (and the focused
 * settings interfaces) via [com.example.surveyingapp.SurveyingApp.settingsRepo]. Do not construct it
 * anywhere else in production code — a second instance would open a second Preferences DataStore over
 * the same `app_settings` file and crash. Tests construct it directly over an isolated temp DataStore
 * (the [SettingsLocalDataSource] `DataStore` constructor). See `docs/settings-architecture.md` →
 * Production ownership; enforced by `ArchitectureGuardTest`.
 */
class SettingsRepositoryImpl(private val local: SettingsLocalDataSource) : SettingsRepository {
    private val sourceFlow = local.locationSourceRaw.map { LocationSourceType.fromPrefKey(it) }
    private val connTypeFlow = local.externalConnTypeRaw.map { ExternalConnectionType.fromPrefKey(it) }

    override val locationSource: Flow<LocationSourceType> = sourceFlow
    override val externalConnType: Flow<ExternalConnectionType> = connTypeFlow
    override val externalBtAddress = local.externalBtAddress
    override val externalTcpHost = local.externalTcpHost
    override val externalTcpPort = local.externalTcpPort
    override val externalTcpName = local.externalTcpName
    override val externalReceiverProfile =
        local.externalReceiverProfileRaw.map { com.example.surveyingapp.settings.model.ExternalReceiverProfile.fromPrefKey(it) }

    override val externalReceiverSettings: Flow<com.example.surveyingapp.settings.model.ExternalReceiverSettings> = combine(
        externalReceiverProfile, connTypeFlow, local.externalTcpHost, local.externalTcpPort, local.externalTcpName
    ) { profile, connType, host, port, name ->
        com.example.surveyingapp.settings.model.ExternalReceiverSettings(
            profile = profile,
            connectionType = connType,
            tcpHost = host?.takeIf { it.isNotBlank() } ?: "",
            tcpPort = com.example.surveyingapp.settings.SettingsDefaults.sanitizeTcpPort(port),
            displayName = name?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    override suspend fun setLocationSource(v: LocationSourceType) {
        local.setLocationSourceString(v.prefKey)
    }

    override suspend fun setExternalReceiverProfile(profile: com.example.surveyingapp.settings.model.ExternalReceiverProfile) {
        local.setExternalReceiverProfile(profile.prefKey)
    }

    override suspend fun setExternalReceiverSettings(settings: com.example.surveyingapp.settings.model.ExternalReceiverSettings) {
        local.setExternalReceiverProfile(settings.profile.prefKey)
        local.setExternalConnTypeString(settings.connectionType.prefKey)
        local.setExternalTcp(settings.tcpHost, settings.tcpPort)
        if (settings.displayName.isNotBlank()) local.setExternalTcpName(settings.displayName)
    }

    override suspend fun setExternalConnType(v: ExternalConnectionType) {
        local.setExternalConnTypeString(v.prefKey)
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
            requiredMinStatus = RtkStatus.fromPrefKey(rtkStr, default = RtkStatus.FIX),
            minDurationSec    = minDur,
            maxDurationSec    = maxDur,
            minSamples        = minSamples,
            maxFixAgeSec      = maxFix,
            maxDiffAgeSec     = maxDiff
        )
    }

    override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {
        local.setGnssCaptureRtkStatus(settings.requiredMinStatus.prefKey)
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
                themeMode              = AppThemeMode.fromPrefKey(theme),
                showLiveGnssStatusBar  = showStrip,
                keepScreenAwake        = keepAwake,
                maxBrightnessWhileOpen = maxBright
            )
        }

    override suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        local.setAppThemeModeString(settings.themeMode.prefKey)
        local.setShowLiveGnssStatusBar(settings.showLiveGnssStatusBar)
        local.setKeepScreenAwake(settings.keepScreenAwake)
        local.setMaxBrightnessWhileOpen(settings.maxBrightnessWhileOpen)
    }

    // Stakeout guidance — numeric values are sanitized on read (display/feedback only; never touches
    // coordinates or stakeout distance/bearing math).
    override val stakeoutSettings: Flow<com.example.surveyingapp.settings.model.StakeoutSettings> = combine(
        local.stakeoutToleranceMRaw,
        local.stakeoutWarningAccuracyMRaw,
        local.stakeoutEnableHaptics,
        local.stakeoutEnableAudio,
        combine(local.stakeoutKeepScreenOn, local.stakeoutCompassHeading) { keep, compass -> keep to compass }
    ) { tolerance, warnAcc, haptics, audio, (keepScreenOn, compass) ->
        com.example.surveyingapp.settings.model.StakeoutSettings(
            toleranceMeters = com.example.surveyingapp.settings.SettingsDefaults.sanitizeStakeoutTolerance(tolerance),
            warningAccuracyMeters = com.example.surveyingapp.settings.SettingsDefaults.sanitizeStakeoutWarningAccuracy(warnAcc),
            enableHaptics = haptics,
            enableAudio = audio,
            keepScreenOnDuringStakeout = keepScreenOn,
            guidanceUsesCompassHeading = compass,
        )
    }

    override suspend fun setStakeoutSettings(settings: com.example.surveyingapp.settings.model.StakeoutSettings) {
        local.setStakeoutToleranceM(
            com.example.surveyingapp.settings.SettingsDefaults.sanitizeStakeoutTolerance(settings.toleranceMeters)
        )
        local.setStakeoutWarningAccuracyM(
            com.example.surveyingapp.settings.SettingsDefaults.sanitizeStakeoutWarningAccuracy(settings.warningAccuracyMeters)
        )
        local.setStakeoutEnableHaptics(settings.enableHaptics)
        local.setStakeoutEnableAudio(settings.enableAudio)
        local.setStakeoutKeepScreenOn(settings.keepScreenOnDuringStakeout)
        local.setStakeoutCompassHeading(settings.guidanceUsesCompassHeading)
    }

    // Enum persistence now lives on the enums themselves (stable prefKey + fromPrefKey with legacy
    // name fallback): LocationSourceType, ExternalConnectionType, AppThemeMode, RtkStatus.
}
