package com.example.surveyingapp.domain.repository

import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.settings.GnssReceiverSettings
import com.example.surveyingapp.settings.model.AppearanceSettings
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.DeveloperSettings
import com.example.surveyingapp.settings.model.ExternalReceiverProfile
import com.example.surveyingapp.settings.model.ExternalReceiverSettings
import com.example.surveyingapp.settings.model.StakeoutSettings
import kotlinx.coroutines.flow.Flow

/**
 * Focused (role-segregated) settings interfaces.
 *
 * Each interface exposes ONE settings area so a consumer can depend only on what it actually uses
 * instead of the broad aggregate [SettingsRepository]. They are pure subsets: [SettingsRepository]
 * extends all of them, every member lives in exactly one of these, and they are all backed by the
 * single [com.example.surveyingapp.data.settings.repository.SettingsRepositoryImpl] over the one
 * Preferences DataStore (see `di/SettingsModule.kt`). Prefer these in new ViewModels/consumers; the
 * aggregate remains for screens that genuinely touch several areas. See `docs/settings-architecture.md`.
 */

/** Selected location/GNSS source (Internal / External / Simulator). */
interface LocationSourceSettingsRepository {
    val locationSource: Flow<LocationSourceType>
    suspend fun setLocationSource(v: LocationSourceType)
}

/** External GNSS receiver configuration (profile, transport, TCP host/port/name). */
interface ExternalReceiverSettingsRepository {
    val externalConnType: Flow<ExternalConnectionType>
    val externalBtAddress: Flow<String?>
    val externalTcpHost: Flow<String?>
    val externalTcpPort: Flow<Int?>
    val externalTcpName: Flow<String?>
    val externalReceiverProfile: Flow<ExternalReceiverProfile>
    /** Typed, sanitized aggregate of the external receiver settings (preferred over the individual flows). */
    val externalReceiverSettings: Flow<ExternalReceiverSettings>

    suspend fun setExternalReceiverProfile(profile: ExternalReceiverProfile)
    suspend fun setExternalReceiverSettings(settings: ExternalReceiverSettings)
    suspend fun setExternalConnType(v: ExternalConnectionType)
    suspend fun setExternalTcp(host: String, port: Int, name: String = "")
    suspend fun clearExternalTcp()
}

/** GNSS capture/averaging acceptance policy. */
interface GnssCaptureSettingsRepository {
    val gnssCaptureSettings: Flow<GnssCaptureSettings>
    suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings)
}

/** AR overlay display preferences. */
interface ArDisplaySettingsRepository {
    val arDisplaySettings: Flow<ArDisplaySettings>
    suspend fun setArDisplaySettings(settings: ArDisplaySettings)
}

/** Coordinate list/detail display preferences. */
interface CoordinateDisplaySettingsRepository {
    val coordinateDisplaySettings: Flow<CoordinateDisplaySettings>
    suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings)
}

/** Mock-location publishing toggle. */
interface MockLocationSettingsRepository {
    val mockLocationEnabled: Flow<Boolean>
    suspend fun setMockLocationEnabled(enabled: Boolean)
}

/** GNSS receiver runtime preferences (e.g. high-accuracy mode). */
interface GnssReceiverSettingsRepository {
    val gnssReceiverSettings: Flow<GnssReceiverSettings>
    suspend fun setGnssReceiverSettings(settings: GnssReceiverSettings)
}

/** Developer/debug tools toggle. */
interface DeveloperSettingsRepository {
    val developerSettings: Flow<DeveloperSettings>
    suspend fun setDeveloperSettings(settings: DeveloperSettings)
}

/** Theme/appearance preferences (theme mode, status bar, screen-awake, brightness). */
interface AppearanceSettingsRepository {
    val appearanceSettings: Flow<AppearanceSettings>
    suspend fun setAppearanceSettings(settings: AppearanceSettings)
}

/** Stakeout guidance preferences (tolerance, accuracy warning, haptics/audio, keep-screen-on, heading). */
interface StakeoutSettingsRepository {
    val stakeoutSettings: Flow<StakeoutSettings>
    suspend fun setStakeoutSettings(settings: StakeoutSettings)
}
