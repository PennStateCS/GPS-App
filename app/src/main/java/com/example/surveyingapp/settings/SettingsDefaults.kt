package com.example.surveyingapp.settings

import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.settings.GnssReceiverSettings
import com.example.surveyingapp.settings.model.AppearanceSettings
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.DeveloperSettings
import com.example.surveyingapp.settings.model.ExternalReceiverProfile

/**
 * Single source of truth for settings default values.
 *
 * The canonical defaults for each settings *group* live in that group's data-class constructor
 * (e.g. [GnssCaptureSettings]); this object exposes a default instance of each so DataStore read
 * fallbacks ([com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource]) and any UI
 * code reference one place instead of duplicating literals. Standalone defaults that have no owning
 * data class (location source, connection type, receiver profile, TCP port, mock location) are
 * declared directly here. Enum defaults reuse each enum's own `DEFAULT`.
 *
 * Changing a default here (or in the owning data class) updates every reader.
 */
object SettingsDefaults {

    // ── Standalone defaults (no owning data class) ──────────────────────────────
    val locationSource = LocationSourceType.DEFAULT
    val externalConnectionType = ExternalConnectionType.DEFAULT
    val externalReceiverProfile = ExternalReceiverProfile.DEFAULT

    /**
     * Generic external TCP port fallback used when the port field is empty/unset. Reach profiles
     * apply their own default ([ExternalReceiverProfile.defaultPort] = 9001) when selected.
     */
    const val externalTcpPort = 9000

    const val mockLocationEnabled = false

    /** Current settings-storage schema version. Bump when adding a migration in SettingsMigrationRunner. */
    const val CURRENT_SETTINGS_SCHEMA_VERSION = 1

    // ── Grouped defaults (canonical values live in each data-class constructor) ──
    val appearance = AppearanceSettings()
    val arDisplay = ArDisplaySettings()
    val coordinateDisplay = CoordinateDisplaySettings()
    val developer = DeveloperSettings()
    val gnssReceiver = GnssReceiverSettings()
    val gnssCapture = GnssCaptureSettings()
    val stakeout = com.example.surveyingapp.settings.model.StakeoutSettings()

    /**
     * Default external receiver aggregate. Mirrors the individual defaults above. The connection
     * type default is [ExternalConnectionType.DEFAULT] (TCP) — TCP NMEA is the only implemented
     * external transport, so it is what a fresh install with no saved value gets.
     */
    val externalReceiverSettings = com.example.surveyingapp.settings.model.ExternalReceiverSettings(
        profile = externalReceiverProfile,
        connectionType = externalConnectionType,
        tcpHost = "",
        tcpPort = externalTcpPort,
        displayName = "",
    )

    /** Clamps a TCP port to the valid range, falling back to [externalTcpPort] when absent/invalid. */
    fun sanitizeTcpPort(port: Int?): Int = port?.takeIf { it in 1..65535 } ?: externalTcpPort

    /** Clamps the stakeout tolerance to a sane range (1 mm … 100 m); absent/invalid → default. */
    fun sanitizeStakeoutTolerance(m: Double?): Double =
        m?.takeIf { it.isFinite() && it in 0.001..100.0 } ?: stakeout.toleranceMeters

    /** Clamps the stakeout accuracy-warning threshold (1 mm … 1000 m); absent/invalid → default. */
    fun sanitizeStakeoutWarningAccuracy(m: Double?): Double =
        m?.takeIf { it.isFinite() && it in 0.001..1000.0 } ?: stakeout.warningAccuracyMeters
}
