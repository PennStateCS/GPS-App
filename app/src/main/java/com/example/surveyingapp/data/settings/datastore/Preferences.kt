package com.example.surveyingapp.data.settings.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Local raw persistence ONLY (no domain enums/models here)
private const val DATASTORE_NAME = "app_settings"
val Context.appDataStore by preferencesDataStore(name = DATASTORE_NAME)

object SettingsKeys {
    val LOCATION_SOURCE = stringPreferencesKey("location_source")
    val EXTERNAL_CONN_TYPE = stringPreferencesKey("external_connection_type")
    val EXTERNAL_BT_ADDR = stringPreferencesKey("external_bt_device_address")
    val EXTERNAL_TCP_HOST = stringPreferencesKey("external_tcp_host")
    val EXTERNAL_TCP_PORT = intPreferencesKey("external_tcp_port")
    val EXTERNAL_TCP_NAME = stringPreferencesKey("external_tcp_name")

    // Coordinate display
    val DEFAULT_COORDINATE_NAME_PREFIX = stringPreferencesKey("default_coordinate_name_prefix")
    val AUTO_INCREMENT_COORDINATE_NAMES = booleanPreferencesKey("auto_increment_coordinate_names")
    val SHOW_ACCURACY_INDICATORS = booleanPreferencesKey("show_accuracy_indicators")

    // GNSS capture averaging policy settings
    val GNSS_CAPTURE_RTK_STATUS        = stringPreferencesKey("gnss_capture_rtk_status")
    val GNSS_CAPTURE_MIN_DURATION_SEC  = intPreferencesKey("gnss_capture_min_duration_sec")
    val GNSS_CAPTURE_MAX_DURATION_SEC  = intPreferencesKey("gnss_capture_max_duration_sec")
    val GNSS_CAPTURE_MIN_SAMPLES       = intPreferencesKey("gnss_capture_min_samples")
    val GNSS_CAPTURE_MAX_FIX_AGE_SEC   = intPreferencesKey("gnss_capture_max_fix_age_sec")
    val GNSS_CAPTURE_MAX_DIFF_AGE_SEC  = intPreferencesKey("gnss_capture_max_diff_age_sec")

    // AR Display settings
    val AR_ALTITUDE_MODE               = stringPreferencesKey("ar_altitude_mode")
    val AR_DISTANCE_FILTER_INDEX       = intPreferencesKey("ar_distance_filter_index")
    val AR_SHOW_DEBUG_OVERLAY          = booleanPreferencesKey("ar_show_debug_overlay")
    val AR_SHOW_LABELS                 = booleanPreferencesKey("ar_show_labels")
    val AR_SHOW_OFFSCREEN_ARROWS       = booleanPreferencesKey("ar_show_offscreen_arrows")
    val AR_MODEL_SCALE                 = stringPreferencesKey("ar_model_scale")
    val AR_DEBUG_TOOLS_ENABLED         = booleanPreferencesKey("ar_debug_tools_enabled")

    // GNSS receiver
    val HIGH_ACCURACY = booleanPreferencesKey("high_accuracy")

    // Developer settings
    val DEV_TOOLS_ENABLED = booleanPreferencesKey("dev_tools_enabled")

    // Mock location publishing
    val MOCK_LOCATION_ENABLED          = booleanPreferencesKey("mock_location_enabled")

    // Appearance
    val APP_THEME_MODE                 = stringPreferencesKey("app_theme_mode")
    val SHOW_LIVE_GNSS_STATUS_BAR      = booleanPreferencesKey("show_live_gnss_status_bar")
    val KEEP_SCREEN_AWAKE              = booleanPreferencesKey("keep_screen_awake")
    val MAX_BRIGHTNESS_WHILE_OPEN      = booleanPreferencesKey("max_brightness_while_open")
}

/**
 * Thin DataStore accessor exposing raw primitive flows. Mapping to domain types lives in repository impl.
 */
class SettingsLocalDataSource(private val context: Context) {
    val locationSourceRaw: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] }
    val externalConnTypeRaw: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] }
    val externalBtAddress: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_ADDR] }
    val externalTcpHost: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }
    val externalTcpName: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_NAME] }

    // Coordinate display
    val defaultCoordinateNamePrefix: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] }
    val autoIncrementCoordinateNames: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] ?: true }
    val showAccuracyIndicators: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.SHOW_ACCURACY_INDICATORS] ?: true }

    // GNSS capture averaging policy flows (defaults mirror GnssCaptureSettings)
    val gnssCaptureRtkStatus:       Flow<String>  = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_RTK_STATUS]       ?: "FIX" }
    val gnssCaptureMinDurationSec:  Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MIN_DURATION_SEC]  ?: 60   }
    val gnssCaptureMaxDurationSec:  Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_DURATION_SEC]  ?: 120  }
    val gnssCaptureMinSamples:      Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MIN_SAMPLES]       ?: 150  }
    val gnssCaptureMaxFixAgeSec:    Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_FIX_AGE_SEC]   ?: 3    }
    val gnssCaptureMaxDiffAgeSec:   Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_DIFF_AGE_SEC]  ?: 10   }

    // AR Display settings flows
    val arAltitudeMode:           Flow<String>  = context.appDataStore.data.map { it[SettingsKeys.AR_ALTITUDE_MODE]         ?: "STORED" }
    val arDistanceFilterIndex:    Flow<Int>     = context.appDataStore.data.map { it[SettingsKeys.AR_DISTANCE_FILTER_INDEX]  ?: 1       }
    val arShowDebugOverlay:       Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AR_SHOW_DEBUG_OVERLAY]     ?: false   }
    val arShowLabels:             Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AR_SHOW_LABELS]            ?: true    }
    val arShowOffscreenArrows:    Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AR_SHOW_OFFSCREEN_ARROWS]  ?: true    }
    val arModelScale:             Flow<String>  = context.appDataStore.data.map { it[SettingsKeys.AR_MODEL_SCALE]            ?: "2.0"   }
    val arDebugToolsEnabled:      Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AR_DEBUG_TOOLS_ENABLED]   ?: false   }

    // GNSS receiver
    val highAccuracy: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.HIGH_ACCURACY] ?: true }

    // Developer settings
    val developerToolsEnabled: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DEV_TOOLS_ENABLED] ?: false }

    // Mock location
    val mockLocationEnabled: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.MOCK_LOCATION_ENABLED] ?: false }

    suspend fun setLocationSourceString(v: String) { context.appDataStore.edit { it[SettingsKeys.LOCATION_SOURCE] = v } }
    suspend fun setExternalConnTypeString(v: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_CONN_TYPE] = v } }
    suspend fun setExternalTcp(host: String, port: Int) { context.appDataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_TCP_HOST] = host; prefs[SettingsKeys.EXTERNAL_TCP_PORT] = port } }
    suspend fun setExternalTcpName(name: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_TCP_NAME] = name } }
    suspend fun clearExternalTcp() { context.appDataStore.edit { prefs ->
        prefs.remove(SettingsKeys.EXTERNAL_TCP_HOST); prefs.remove(SettingsKeys.EXTERNAL_TCP_PORT); prefs.remove(SettingsKeys.EXTERNAL_TCP_NAME) } }
    suspend fun setExternalBtAddress(address: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_BT_ADDR] = address } }

    // Coordinate display setters
    suspend fun setDefaultCoordinateNamePrefix(prefix: String) { context.appDataStore.edit { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] = prefix } }
    suspend fun setAutoIncrementCoordinateNames(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] = enabled } }
    suspend fun setShowAccuracyIndicators(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.SHOW_ACCURACY_INDICATORS] = enabled } }

    // GNSS capture averaging policy setters
    suspend fun setGnssCaptureRtkStatus(v: String)      { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_RTK_STATUS]       = v } }
    suspend fun setGnssCaptureMinDurationSec(v: Int)    { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MIN_DURATION_SEC]  = v } }
    suspend fun setGnssCaptureMaxDurationSec(v: Int)    { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_DURATION_SEC]  = v } }
    suspend fun setGnssCaptureMinSamples(v: Int)        { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MIN_SAMPLES]       = v } }
    suspend fun setGnssCaptureMaxFixAgeSec(v: Int)      { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_FIX_AGE_SEC]   = v } }
    suspend fun setGnssCaptureMaxDiffAgeSec(v: Int)     { context.appDataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_DIFF_AGE_SEC]  = v } }

    // AR Display setters
    suspend fun setArAltitudeMode(v: String)            { context.appDataStore.edit { it[SettingsKeys.AR_ALTITUDE_MODE]          = v } }
    suspend fun setArDistanceFilterIndex(v: Int)        { context.appDataStore.edit { it[SettingsKeys.AR_DISTANCE_FILTER_INDEX]  = v } }
    suspend fun setArShowDebugOverlay(v: Boolean)       { context.appDataStore.edit { it[SettingsKeys.AR_SHOW_DEBUG_OVERLAY]     = v } }
    suspend fun setArShowLabels(v: Boolean)             { context.appDataStore.edit { it[SettingsKeys.AR_SHOW_LABELS]            = v } }
    suspend fun setArShowOffscreenArrows(v: Boolean)    { context.appDataStore.edit { it[SettingsKeys.AR_SHOW_OFFSCREEN_ARROWS]  = v } }
    suspend fun setArModelScale(v: String)              { context.appDataStore.edit { it[SettingsKeys.AR_MODEL_SCALE]            = v } }
    suspend fun setArDebugToolsEnabled(v: Boolean)      { context.appDataStore.edit { it[SettingsKeys.AR_DEBUG_TOOLS_ENABLED]   = v } }

    // GNSS receiver setter
    suspend fun setHighAccuracy(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.HIGH_ACCURACY] = enabled } }

    // Developer settings setter
    suspend fun setDeveloperToolsEnabled(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.DEV_TOOLS_ENABLED] = enabled } }

    // Mock location setter
    suspend fun setMockLocationEnabled(v: Boolean) { context.appDataStore.edit { it[SettingsKeys.MOCK_LOCATION_ENABLED] = v } }

    // Appearance
    val appThemeModeRaw: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.APP_THEME_MODE] ?: "SYSTEM" }
    suspend fun setAppThemeModeString(v: String) { context.appDataStore.edit { it[SettingsKeys.APP_THEME_MODE] = v } }

    val showLiveGnssStatusBar: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.SHOW_LIVE_GNSS_STATUS_BAR] ?: true }
    suspend fun setShowLiveGnssStatusBar(v: Boolean) { context.appDataStore.edit { it[SettingsKeys.SHOW_LIVE_GNSS_STATUS_BAR] = v } }

    val keepScreenAwake: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.KEEP_SCREEN_AWAKE] ?: false }
    suspend fun setKeepScreenAwake(v: Boolean) { context.appDataStore.edit { it[SettingsKeys.KEEP_SCREEN_AWAKE] = v } }

    val maxBrightnessWhileOpen: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.MAX_BRIGHTNESS_WHILE_OPEN] ?: false }
    suspend fun setMaxBrightnessWhileOpen(v: Boolean) { context.appDataStore.edit { it[SettingsKeys.MAX_BRIGHTNESS_WHILE_OPEN] = v } }
}
