package app.surrealar.data.settings.repository

import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.data.settings.datastore.SettingsLocalDataSource
import app.surrealar.data.settings.datastore.SettingsKeys as K
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.ExternalConnectionType
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.DeveloperSettings
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.settings.model.AppearanceSettings
import app.surrealar.gnss.settings.GnssReceiverSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Maps raw DataStore preference primitives to domain enums/models and vice versa.
 *
 * **Ownership:** in production this is constructed exactly once, by
 * [app.surrealar.SurRealApplication.setupSettings], and exposed through Hilt (and the focused
 * settings interfaces) via [app.surrealar.SurRealApplication.settingsRepo]. Do not construct it
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
    override val externalTcpHost = local.externalTcpHost
    override val externalTcpPort = local.externalTcpPort
    override val externalTcpName = local.externalTcpName
    override val externalReceiverProfile =
        local.externalReceiverProfileRaw.map { app.surrealar.settings.model.ExternalReceiverProfile.fromPrefKey(it) }

    override val externalReceiverSettings: Flow<app.surrealar.settings.model.ExternalReceiverSettings> = combine(
        externalReceiverProfile, connTypeFlow, local.externalTcpHost, local.externalTcpPort, local.externalTcpName
    ) { profile, connType, host, port, name ->
        app.surrealar.settings.model.ExternalReceiverSettings(
            profile = profile,
            connectionType = connType,
            tcpHost = host?.takeIf { it.isNotBlank() } ?: "",
            tcpPort = app.surrealar.settings.SettingsDefaults.sanitizeTcpPort(port),
            displayName = name?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    override suspend fun setLocationSource(v: LocationSourceType) {
        local.setLocationSourceString(v.prefKey)
    }

    override suspend fun setExternalReceiverProfile(profile: app.surrealar.settings.model.ExternalReceiverProfile) {
        local.setExternalReceiverProfile(profile.prefKey)
    }

    override val compensatedNmeaOutput: Flow<app.surrealar.settings.model.CompensatedOutputSetting> =
        local.externalCompensatedNmeaOutputRaw.map { app.surrealar.settings.model.CompensatedOutputSetting.fromPrefKey(it) }

    override suspend fun setCompensatedNmeaOutput(value: app.surrealar.settings.model.CompensatedOutputSetting) {
        local.setExternalCompensatedNmeaOutput(value.prefKey)
    }

    override suspend fun setExternalReceiverSettings(settings: app.surrealar.settings.model.ExternalReceiverSettings) {
        local.editAll {
            it[K.EXTERNAL_RECEIVER_PROFILE] = settings.profile.prefKey
            it[K.EXTERNAL_CONN_TYPE] = settings.connectionType.prefKey
            it[K.EXTERNAL_TCP_HOST] = settings.tcpHost
            it[K.EXTERNAL_TCP_PORT] = settings.tcpPort
            if (settings.displayName.isNotBlank()) it[K.EXTERNAL_TCP_NAME] = settings.displayName
        }
    }

    override suspend fun setExternalConnType(v: ExternalConnectionType) {
        local.setExternalConnTypeString(v.prefKey)
    }

    override suspend fun setExternalTcp(host: String, port: Int, name: String) {
        local.editAll { prefs ->
            val previousHost = prefs[K.EXTERNAL_TCP_HOST]
            prefs[K.EXTERNAL_TCP_HOST] = host
            prefs[K.EXTERNAL_TCP_PORT] = port
            if (name.isNotBlank()) {
                prefs[K.EXTERNAL_TCP_NAME] = name
            } else if (previousHost != null && !previousHost.equals(host, ignoreCase = true)) {
                // Different receiver and no name yet: drop the old receiver's name so it can't be shown
                // on Home or stamped into saved points' source device until the new one is resolved.
                prefs[K.EXTERNAL_TCP_NAME] = ""
            }
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
        combine(
            local.gnssCaptureMaxFixAgeSec, local.gnssCaptureMaxDiffAgeSec,
            local.gnssCapturePreset, local.gnssCapturePrecisionTargetM
        ) { fix, diff, preset, target -> CaptureExtras(fix, diff, preset, target) }
    ) { rtkStr, minDur, maxDur, minSamples, x ->
        GnssCaptureSettings(
            requiredMinStatus = RtkStatus.fromPrefKey(rtkStr, default = RtkStatus.FIX),
            minDurationSec    = minDur,
            maxDurationSec    = maxDur,
            minSamples        = minSamples,
            maxFixAgeSec      = x.maxFix,
            maxDiffAgeSec     = x.maxDiff,
            preset            = io.github.pennstatecs.geokit.gnss.capture.CapturePreset.fromPrefKey(x.preset),
            precisionTargetM  = x.target
        )
    }

    private data class CaptureExtras(val maxFix: Int, val maxDiff: Int, val preset: String, val target: Double?)

    override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {
        local.editAll {
            it[K.GNSS_CAPTURE_RTK_STATUS] = settings.requiredMinStatus.prefKey
            it[K.GNSS_CAPTURE_MIN_DURATION_SEC] = settings.minDurationSec
            it[K.GNSS_CAPTURE_MAX_DURATION_SEC] = settings.maxDurationSec
            it[K.GNSS_CAPTURE_MIN_SAMPLES] = settings.minSamples
            it[K.GNSS_CAPTURE_MAX_FIX_AGE_SEC] = settings.maxFixAgeSec
            it[K.GNSS_CAPTURE_MAX_DIFF_AGE_SEC] = settings.maxDiffAgeSec
            it[K.GNSS_CAPTURE_PRESET] = settings.preset.prefKey
            val target = settings.precisionTargetM
            if (target != null && target > 0.0) it[K.GNSS_CAPTURE_PRECISION_TARGET_M] = target
            else it.remove(K.GNSS_CAPTURE_PRECISION_TARGET_M)
        }
    }

    // AR Display
    override val arDisplaySettings: Flow<ArDisplaySettings> = combine(
        local.arAltitudeMode,
        local.arDistanceFilterIndex,
        local.arShowDebugOverlay,
        local.arShowLabels,
        combine(local.arShowOffscreenArrows, local.arModelScale, local.arDebugToolsEnabled) {
            arrows, scale, debug -> listOf<Any>(arrows, scale, debug)
        }
    ) { altitude, filterIdx, debug, labels, rest ->
        val arrows = rest[0] as Boolean
        val scale = rest[1] as String
        val debugTools = rest[2] as Boolean
        ArDisplaySettings(
            altitudeMode         = altitude,
            distanceFilterIndex  = filterIdx.coerceIn(0, 3),
            showDebugOverlay     = debug,
            showLabels           = labels,
            showOffscreenArrows  = arrows,
            modelScale           = scale.toFloatOrNull()?.coerceIn(0.1f, 10.0f) ?: ArDisplaySettings().modelScale,
            showArDebugTools     = debugTools,
        )
    }

    override suspend fun setArDisplaySettings(settings: ArDisplaySettings) {
        local.editAll {
            it[K.AR_ALTITUDE_MODE] = settings.altitudeMode
            it[K.AR_DISTANCE_FILTER_INDEX] = settings.distanceFilterIndex.coerceIn(0, 3)
            it[K.AR_SHOW_DEBUG_OVERLAY] = settings.showDebugOverlay
            it[K.AR_SHOW_LABELS] = settings.showLabels
            it[K.AR_SHOW_OFFSCREEN_ARROWS] = settings.showOffscreenArrows
            it[K.AR_MODEL_SCALE] = settings.modelScale.coerceIn(0.1f, 10.0f).toString()
            it[K.AR_DEBUG_TOOLS_ENABLED] = settings.showArDebugTools
        }
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
        local.editAll {
            it[K.SHOW_ACCURACY_INDICATORS] = settings.showAccuracyIndicators
            it[K.DEFAULT_COORDINATE_NAME_PREFIX] = settings.defaultNamePrefix
            it[K.AUTO_INCREMENT_COORDINATE_NAMES] = settings.autoIncrementNames
        }
    }

    // Coordinate system (expression of coordinates; unset keys → model defaults)
    override val coordinateSystemSettings: Flow<app.surrealar.settings.model.CoordinateSystemSettings> = combine(
        local.coordHorizontalSystem, local.coordOutputDatum, local.coordLinearUnit,
        local.coordVerticalReference, local.coordExternalRxDatum,
    ) { h, d, u, v, rx ->
        val (horizontal, zone) = app.surrealar.settings.model.CoordinateSystemSettings.parseHorizontal(h)
        app.surrealar.settings.model.CoordinateSystemSettings(
            horizontal = horizontal,
            spcsZoneEpsg = zone,
            datum = app.surrealar.settings.model.OutputDatum.fromPrefKey(d),
            unit = app.surrealar.settings.model.LinearUnit.fromPrefKey(u),
            vertical = app.surrealar.settings.model.VerticalReference.fromPrefKey(v),
            externalReceiverDatum = app.surrealar.settings.model.ReceiverDatum.fromPrefKey(rx),
        )
    }.combine(local.coordNsrs2022Preview) { s, preview -> s.copy(nsrs2022Preview = preview) }
        .combine(local.coordSpcs2022Zone) { s, zone -> if (zone == null) s else s.copy(spcs2022Zone = zone) }

    override suspend fun setCoordinateSystemSettings(settings: app.surrealar.settings.model.CoordinateSystemSettings) {
        local.setCoordinateSystem(
            settings.horizontalPrefValue, settings.datum.prefKey, settings.unit.prefKey,
            settings.vertical.prefKey, settings.externalReceiverDatum.prefKey,
            nsrs2022Preview = settings.nsrs2022Preview,
            spcs2022Zone = settings.spcs2022Zone, writeSpcs2022Zone = true,
        )
    }

    // Mock location publishing
    override val mockLocationEnabled: Flow<Boolean> = local.mockLocationEnabled
    override suspend fun setMockLocationEnabled(enabled: Boolean) { local.setMockLocationEnabled(enabled) }

    // AR model visibility (AR-only)
    override val arVisibilityMode: Flow<String> = local.arVisibilityMode
    override val arVisibilityCustomized: Flow<Boolean> = local.arVisibilityCustomized
    override val arVisibleIds: Flow<Set<String>> = local.arVisibleIds
    override suspend fun setArVisibilityMode(mode: String) { local.setArVisibilityMode(mode) }
    override suspend fun setArVisibilityCustomized(customized: Boolean) { local.setArVisibilityCustomized(customized) }
    override suspend fun setArVisibleIds(ids: Set<String>) { local.setArVisibleIds(ids) }

    // GNSS receiver settings
    // External antenna values stored before 2026-11 are converted on read, so nothing is migrated in
    // place: the old pole height (which included the phase-centre offset) is kept as the pole height
    // and flagged for the user to check; the old AR "antenna above tablet" value becomes the camera
    // distance minus the profile's phase-centre offset, so AR keeps the same total. The first save
    // writes the new keys.
    override val gnssReceiverSettings: Flow<GnssReceiverSettings> =
        combine(
            local.highAccuracy, local.internalAntennaHeightM, local.externalAntennaRaw, externalReceiverProfile,
        ) { highAccuracy, internalAntennaHeightM, raw, profile ->
            val defaults = app.surrealar.settings.SettingsDefaults.gnssReceiver
            val phaseCenterOffsetM = raw.phaseCenterOffsetOverrideM ?: profile.phaseCenterOffsetM ?: 0.0
            val legacyCamera = raw.legacyAntennaAboveCameraM?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it != 0.0 }
                ?.let { (it - phaseCenterOffsetM).coerceIn(-3.0, 3.0) }
            GnssReceiverSettings(
                highAccuracy = highAccuracy,
                poleHeightM = raw.poleHeightM ?: raw.legacyAntennaHeightM ?: defaults.poleHeightM,
                internalAntennaHeightM = internalAntennaHeightM,
                cameraBelowReceiverM = raw.cameraBelowReceiverM ?: legacyCamera ?: defaults.cameraBelowReceiverM,
                phaseCenterOffsetOverrideM = raw.phaseCenterOffsetOverrideM,
                poleHeightNeedsCheck = raw.legacyAntennaHeightM != null && !raw.poleHeightChecked,
            )
        }

    override suspend fun setGnssReceiverSettings(settings: GnssReceiverSettings) {
        local.editAll {
            it[K.HIGH_ACCURACY] = settings.highAccuracy
            it[K.POLE_HEIGHT_ARP_M] = settings.poleHeightM
            it[K.INTERNAL_ANTENNA_HEIGHT_M] = settings.internalAntennaHeightM
            it[K.CAMERA_BELOW_RECEIVER_M] = settings.cameraBelowReceiverM.coerceIn(-3.0, 3.0)
            val override = settings.phaseCenterOffsetOverrideM
            if (override != null) it[K.PHASE_CENTER_OFFSET_OVERRIDE_M] = override
            else it.remove(K.PHASE_CENTER_OFFSET_OVERRIDE_M)
            it[K.POLE_HEIGHT_CHECKED] = !settings.poleHeightNeedsCheck
        }
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
        local.editAll {
            it[K.APP_THEME_MODE] = settings.themeMode.prefKey
            it[K.SHOW_LIVE_GNSS_STATUS_BAR] = settings.showLiveGnssStatusBar
            it[K.KEEP_SCREEN_AWAKE] = settings.keepScreenAwake
            it[K.MAX_BRIGHTNESS_WHILE_OPEN] = settings.maxBrightnessWhileOpen
        }
    }

    // Stakeout guidance — numeric values are sanitized on read (display/feedback only; never touches
    // coordinates or stakeout distance/bearing math).
    override val stakeoutSettings: Flow<app.surrealar.settings.model.StakeoutSettings> = combine(
        local.stakeoutToleranceMRaw,
        local.stakeoutWarningAccuracyMRaw,
        local.stakeoutEnableHaptics,
        local.stakeoutEnableAudio,
        combine(local.stakeoutKeepScreenOn, local.stakeoutCompassHeading) { keep, compass -> keep to compass }
    ) { tolerance, warnAcc, haptics, audio, (keepScreenOn, compass) ->
        app.surrealar.settings.model.StakeoutSettings(
            toleranceMeters = app.surrealar.settings.SettingsDefaults.sanitizeStakeoutTolerance(tolerance),
            warningAccuracyMeters = app.surrealar.settings.SettingsDefaults.sanitizeStakeoutWarningAccuracy(warnAcc),
            enableHaptics = haptics,
            enableAudio = audio,
            keepScreenOnDuringStakeout = keepScreenOn,
            guidanceUsesCompassHeading = compass,
        )
    }

    override suspend fun setStakeoutSettings(settings: app.surrealar.settings.model.StakeoutSettings) {
        local.editAll {
            it[K.STAKEOUT_TOLERANCE_M] =
                app.surrealar.settings.SettingsDefaults.sanitizeStakeoutTolerance(settings.toleranceMeters)
            it[K.STAKEOUT_WARNING_ACCURACY_M] =
                app.surrealar.settings.SettingsDefaults.sanitizeStakeoutWarningAccuracy(settings.warningAccuracyMeters)
            it[K.STAKEOUT_ENABLE_HAPTICS] = settings.enableHaptics
            it[K.STAKEOUT_ENABLE_AUDIO] = settings.enableAudio
            it[K.STAKEOUT_KEEP_SCREEN_ON] = settings.keepScreenOnDuringStakeout
            it[K.STAKEOUT_COMPASS_HEADING] = settings.guidanceUsesCompassHeading
        }
    }

    // Map display defaults — enum-backed values resolve via stable tokens (legacy name fallback).
    override val mapSettings: Flow<app.surrealar.ui.rendermap.MapSettings> = combine(
        local.mapDefaultTypeRaw,
        local.mapDefaultGridModeRaw,
        local.mapDefaultLabelModeRaw,
        local.mapShowMyLocationDefault,
        combine(local.mapToolsOpenDefault, local.mapDrawerExpandedDefault) { tools, drawer -> tools to drawer }
    ) { typeToken, gridToken, labelToken, showLoc, (toolsOpen, drawerExpanded) ->
        app.surrealar.ui.rendermap.MapSettings(
            defaultMapType = app.surrealar.ui.rendermap.MapTypeTokens.fromToken(typeToken),
            defaultGridMode = app.surrealar.ui.rendermap.MapGridMode.fromPrefKey(gridToken),
            defaultPointLabelMode = app.surrealar.ui.rendermap.PointLabelMode.fromPrefKey(labelToken),
            showMyLocationByDefault = showLoc,
            keepMapToolsOpenByDefault = toolsOpen,
            mapPointsDrawerExpandedByDefault = drawerExpanded,
        )
    }

    override suspend fun setMapSettings(settings: app.surrealar.ui.rendermap.MapSettings) {
        local.editAll {
            it[K.MAP_DEFAULT_TYPE] = app.surrealar.ui.rendermap.MapTypeTokens.toToken(settings.defaultMapType)
            it[K.MAP_DEFAULT_GRID_MODE] = settings.defaultGridMode.prefKey
            it[K.MAP_DEFAULT_LABEL_MODE] = settings.defaultPointLabelMode.prefKey
            it[K.MAP_SHOW_MY_LOCATION_DEFAULT] = settings.showMyLocationByDefault
            it[K.MAP_TOOLS_OPEN_DEFAULT] = settings.keepMapToolsOpenByDefault
            it[K.MAP_DRAWER_EXPANDED_DEFAULT] = settings.mapPointsDrawerExpandedByDefault
        }
    }

    // Enum persistence now lives on the enums themselves (stable prefKey + fromPrefKey with legacy
    // name fallback): LocationSourceType, ExternalConnectionType, AppThemeMode, RtkStatus.
}
