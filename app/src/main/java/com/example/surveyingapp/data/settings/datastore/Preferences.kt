package com.example.surveyingapp.data.settings.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.surveyingapp.settings.SettingsDefaults
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
    val EXTERNAL_RECEIVER_PROFILE = stringPreferencesKey("external_receiver_profile")

    // Settings storage schema version (for migrations). Absent = pre-versioning install (treated as 0).
    val SETTINGS_SCHEMA_VERSION = intPreferencesKey("settings_schema_version")

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

    // Map display defaults
    val MAP_DEFAULT_TYPE               = stringPreferencesKey("map_default_type")
    val MAP_DEFAULT_GRID_MODE          = stringPreferencesKey("map_default_grid_mode")
    val MAP_DEFAULT_LABEL_MODE         = stringPreferencesKey("map_default_label_mode")
    val MAP_SHOW_MY_LOCATION_DEFAULT   = booleanPreferencesKey("map_show_my_location_default")
    val MAP_TOOLS_OPEN_DEFAULT         = booleanPreferencesKey("map_tools_open_default")
    val MAP_DRAWER_EXPANDED_DEFAULT    = booleanPreferencesKey("map_drawer_expanded_default")

    // Stakeout guidance
    val STAKEOUT_TOLERANCE_M           = doublePreferencesKey("stakeout_tolerance_m")
    val STAKEOUT_WARNING_ACCURACY_M    = doublePreferencesKey("stakeout_warning_accuracy_m")
    val STAKEOUT_ENABLE_HAPTICS        = booleanPreferencesKey("stakeout_enable_haptics")
    val STAKEOUT_ENABLE_AUDIO          = booleanPreferencesKey("stakeout_enable_audio")
    val STAKEOUT_KEEP_SCREEN_ON        = booleanPreferencesKey("stakeout_keep_screen_on")
    val STAKEOUT_COMPASS_HEADING       = booleanPreferencesKey("stakeout_compass_heading")
}

/**
 * Thin DataStore accessor exposing raw primitive flows. Mapping to domain types lives in repository impl.
 */
class SettingsLocalDataSource(private val dataStore: DataStore<Preferences>) {

    /** Production constructor — uses the app-wide DataStore singleton (single instance per file). */
    constructor(context: Context) : this(context.appDataStore)

    val locationSourceRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] }
    val externalConnTypeRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] }
    val externalBtAddress: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_BT_ADDR] }
    val externalTcpHost: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }
    val externalTcpName: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_NAME] }
    val externalReceiverProfileRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE] }
    val schemaVersion: Flow<Int?> = dataStore.data.map { it[SettingsKeys.SETTINGS_SCHEMA_VERSION] }
    suspend fun setSchemaVersion(v: Int) { dataStore.edit { it[SettingsKeys.SETTINGS_SCHEMA_VERSION] = v } }

    // Coordinate display
    val defaultCoordinateNamePrefix: Flow<String?> = dataStore.data.map { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] }
    val autoIncrementCoordinateNames: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] ?: SettingsDefaults.coordinateDisplay.autoIncrementNames }
    val showAccuracyIndicators: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.SHOW_ACCURACY_INDICATORS] ?: SettingsDefaults.coordinateDisplay.showAccuracyIndicators }

    // GNSS capture averaging policy flows (defaults centralized in SettingsDefaults.gnssCapture)
    val gnssCaptureRtkStatus:       Flow<String>  = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_RTK_STATUS]       ?: SettingsDefaults.gnssCapture.requiredMinStatus.prefKey }
    val gnssCaptureMinDurationSec:  Flow<Int>     = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MIN_DURATION_SEC]  ?: SettingsDefaults.gnssCapture.minDurationSec }
    val gnssCaptureMaxDurationSec:  Flow<Int>     = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_DURATION_SEC]  ?: SettingsDefaults.gnssCapture.maxDurationSec }
    val gnssCaptureMinSamples:      Flow<Int>     = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MIN_SAMPLES]       ?: SettingsDefaults.gnssCapture.minSamples }
    val gnssCaptureMaxFixAgeSec:    Flow<Int>     = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_FIX_AGE_SEC]   ?: SettingsDefaults.gnssCapture.maxFixAgeSec }
    val gnssCaptureMaxDiffAgeSec:   Flow<Int>     = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_MAX_DIFF_AGE_SEC]  ?: SettingsDefaults.gnssCapture.maxDiffAgeSec }

    // AR Display settings flows (defaults centralized in SettingsDefaults.arDisplay)
    val arAltitudeMode:           Flow<String>  = dataStore.data.map { it[SettingsKeys.AR_ALTITUDE_MODE]         ?: SettingsDefaults.arDisplay.altitudeMode }
    val arDistanceFilterIndex:    Flow<Int>     = dataStore.data.map { it[SettingsKeys.AR_DISTANCE_FILTER_INDEX]  ?: SettingsDefaults.arDisplay.distanceFilterIndex }
    val arShowDebugOverlay:       Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AR_SHOW_DEBUG_OVERLAY]     ?: SettingsDefaults.arDisplay.showDebugOverlay }
    val arShowLabels:             Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AR_SHOW_LABELS]            ?: SettingsDefaults.arDisplay.showLabels }
    val arShowOffscreenArrows:    Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AR_SHOW_OFFSCREEN_ARROWS]  ?: SettingsDefaults.arDisplay.showOffscreenArrows }
    val arModelScale:             Flow<String>  = dataStore.data.map { it[SettingsKeys.AR_MODEL_SCALE]            ?: SettingsDefaults.arDisplay.modelScale.toString() }
    val arDebugToolsEnabled:      Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AR_DEBUG_TOOLS_ENABLED]   ?: SettingsDefaults.arDisplay.showArDebugTools }

    // GNSS receiver
    val highAccuracy: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.HIGH_ACCURACY] ?: SettingsDefaults.gnssReceiver.highAccuracy }

    // Developer settings
    val developerToolsEnabled: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.DEV_TOOLS_ENABLED] ?: SettingsDefaults.developer.developerToolsEnabled }

    // Mock location
    val mockLocationEnabled: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MOCK_LOCATION_ENABLED] ?: SettingsDefaults.mockLocationEnabled }

    // Stakeout guidance (defaults centralized in SettingsDefaults.stakeout). Raw values; the
    // repository sanitizes the numeric ones before exposing the typed model.
    val stakeoutToleranceMRaw:        Flow<Double?>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_TOLERANCE_M] }
    val stakeoutWarningAccuracyMRaw:  Flow<Double?>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_WARNING_ACCURACY_M] }
    val stakeoutEnableHaptics:        Flow<Boolean>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_ENABLE_HAPTICS] ?: SettingsDefaults.stakeout.enableHaptics }
    val stakeoutEnableAudio:          Flow<Boolean>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_ENABLE_AUDIO] ?: SettingsDefaults.stakeout.enableAudio }
    val stakeoutKeepScreenOn:         Flow<Boolean>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_KEEP_SCREEN_ON] ?: SettingsDefaults.stakeout.keepScreenOnDuringStakeout }
    val stakeoutCompassHeading:       Flow<Boolean>  = dataStore.data.map { it[SettingsKeys.STAKEOUT_COMPASS_HEADING] ?: SettingsDefaults.stakeout.guidanceUsesCompassHeading }

    // Map display defaults (raw tokens; repository resolves enums + map type).
    val mapDefaultTypeRaw:        Flow<String?> = dataStore.data.map { it[SettingsKeys.MAP_DEFAULT_TYPE] }
    val mapDefaultGridModeRaw:    Flow<String?> = dataStore.data.map { it[SettingsKeys.MAP_DEFAULT_GRID_MODE] }
    val mapDefaultLabelModeRaw:   Flow<String?> = dataStore.data.map { it[SettingsKeys.MAP_DEFAULT_LABEL_MODE] }
    val mapShowMyLocationDefault: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MAP_SHOW_MY_LOCATION_DEFAULT] ?: SettingsDefaults.map.showMyLocationByDefault }
    val mapToolsOpenDefault:      Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MAP_TOOLS_OPEN_DEFAULT] ?: SettingsDefaults.map.keepMapToolsOpenByDefault }
    val mapDrawerExpandedDefault: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MAP_DRAWER_EXPANDED_DEFAULT] ?: SettingsDefaults.map.mapPointsDrawerExpandedByDefault }

    suspend fun setLocationSourceString(v: String) { dataStore.edit { it[SettingsKeys.LOCATION_SOURCE] = v } }
    suspend fun setExternalConnTypeString(v: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_CONN_TYPE] = v } }
    suspend fun setExternalTcp(host: String, port: Int) { dataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_TCP_HOST] = host; prefs[SettingsKeys.EXTERNAL_TCP_PORT] = port } }
    suspend fun setExternalTcpName(name: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_TCP_NAME] = name } }
    suspend fun setExternalReceiverProfile(prefKey: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE] = prefKey } }
    suspend fun clearExternalTcp() { dataStore.edit { prefs ->
        prefs.remove(SettingsKeys.EXTERNAL_TCP_HOST); prefs.remove(SettingsKeys.EXTERNAL_TCP_PORT); prefs.remove(SettingsKeys.EXTERNAL_TCP_NAME) } }
    suspend fun setExternalBtAddress(address: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_BT_ADDR] = address } }

    // Coordinate display setters
    suspend fun setDefaultCoordinateNamePrefix(prefix: String) { dataStore.edit { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] = prefix } }
    suspend fun setAutoIncrementCoordinateNames(enabled: Boolean) { dataStore.edit { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] = enabled } }
    suspend fun setShowAccuracyIndicators(enabled: Boolean) { dataStore.edit { it[SettingsKeys.SHOW_ACCURACY_INDICATORS] = enabled } }

    // GNSS capture averaging policy setters
    suspend fun setGnssCaptureRtkStatus(v: String)      { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_RTK_STATUS]       = v } }
    suspend fun setGnssCaptureMinDurationSec(v: Int)    { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MIN_DURATION_SEC]  = v } }
    suspend fun setGnssCaptureMaxDurationSec(v: Int)    { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_DURATION_SEC]  = v } }
    suspend fun setGnssCaptureMinSamples(v: Int)        { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MIN_SAMPLES]       = v } }
    suspend fun setGnssCaptureMaxFixAgeSec(v: Int)      { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_FIX_AGE_SEC]   = v } }
    suspend fun setGnssCaptureMaxDiffAgeSec(v: Int)     { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_MAX_DIFF_AGE_SEC]  = v } }

    // AR Display setters
    suspend fun setArAltitudeMode(v: String)            { dataStore.edit { it[SettingsKeys.AR_ALTITUDE_MODE]          = v } }
    suspend fun setArDistanceFilterIndex(v: Int)        { dataStore.edit { it[SettingsKeys.AR_DISTANCE_FILTER_INDEX]  = v } }
    suspend fun setArShowDebugOverlay(v: Boolean)       { dataStore.edit { it[SettingsKeys.AR_SHOW_DEBUG_OVERLAY]     = v } }
    suspend fun setArShowLabels(v: Boolean)             { dataStore.edit { it[SettingsKeys.AR_SHOW_LABELS]            = v } }
    suspend fun setArShowOffscreenArrows(v: Boolean)    { dataStore.edit { it[SettingsKeys.AR_SHOW_OFFSCREEN_ARROWS]  = v } }
    suspend fun setArModelScale(v: String)              { dataStore.edit { it[SettingsKeys.AR_MODEL_SCALE]            = v } }
    suspend fun setArDebugToolsEnabled(v: Boolean)      { dataStore.edit { it[SettingsKeys.AR_DEBUG_TOOLS_ENABLED]   = v } }

    // GNSS receiver setter
    suspend fun setHighAccuracy(enabled: Boolean) { dataStore.edit { it[SettingsKeys.HIGH_ACCURACY] = enabled } }

    // Developer settings setter
    suspend fun setDeveloperToolsEnabled(enabled: Boolean) { dataStore.edit { it[SettingsKeys.DEV_TOOLS_ENABLED] = enabled } }

    // Mock location setter
    suspend fun setMockLocationEnabled(v: Boolean) { dataStore.edit { it[SettingsKeys.MOCK_LOCATION_ENABLED] = v } }

    // Appearance
    val appThemeModeRaw: Flow<String> = dataStore.data.map { it[SettingsKeys.APP_THEME_MODE] ?: SettingsDefaults.appearance.themeMode.prefKey }
    suspend fun setAppThemeModeString(v: String) { dataStore.edit { it[SettingsKeys.APP_THEME_MODE] = v } }

    val showLiveGnssStatusBar: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.SHOW_LIVE_GNSS_STATUS_BAR] ?: SettingsDefaults.appearance.showLiveGnssStatusBar }
    suspend fun setShowLiveGnssStatusBar(v: Boolean) { dataStore.edit { it[SettingsKeys.SHOW_LIVE_GNSS_STATUS_BAR] = v } }

    val keepScreenAwake: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.KEEP_SCREEN_AWAKE] ?: SettingsDefaults.appearance.keepScreenAwake }
    suspend fun setKeepScreenAwake(v: Boolean) { dataStore.edit { it[SettingsKeys.KEEP_SCREEN_AWAKE] = v } }

    val maxBrightnessWhileOpen: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MAX_BRIGHTNESS_WHILE_OPEN] ?: SettingsDefaults.appearance.maxBrightnessWhileOpen }
    suspend fun setMaxBrightnessWhileOpen(v: Boolean) { dataStore.edit { it[SettingsKeys.MAX_BRIGHTNESS_WHILE_OPEN] = v } }

    // Stakeout guidance setters. Numeric values are stored as given; sanitization happens on read in
    // the repository (mirrors how an out-of-range TCP port is tolerated but sanitized on read).
    suspend fun setStakeoutToleranceM(v: Double)        { dataStore.edit { it[SettingsKeys.STAKEOUT_TOLERANCE_M] = v } }
    suspend fun setStakeoutWarningAccuracyM(v: Double)  { dataStore.edit { it[SettingsKeys.STAKEOUT_WARNING_ACCURACY_M] = v } }
    suspend fun setStakeoutEnableHaptics(v: Boolean)    { dataStore.edit { it[SettingsKeys.STAKEOUT_ENABLE_HAPTICS] = v } }
    suspend fun setStakeoutEnableAudio(v: Boolean)      { dataStore.edit { it[SettingsKeys.STAKEOUT_ENABLE_AUDIO] = v } }
    suspend fun setStakeoutKeepScreenOn(v: Boolean)     { dataStore.edit { it[SettingsKeys.STAKEOUT_KEEP_SCREEN_ON] = v } }
    suspend fun setStakeoutCompassHeading(v: Boolean)   { dataStore.edit { it[SettingsKeys.STAKEOUT_COMPASS_HEADING] = v } }

    // Map display defaults setters (enum-backed values stored as stable tokens, never enum.name).
    suspend fun setMapDefaultType(token: String)        { dataStore.edit { it[SettingsKeys.MAP_DEFAULT_TYPE] = token } }
    suspend fun setMapDefaultGridMode(token: String)    { dataStore.edit { it[SettingsKeys.MAP_DEFAULT_GRID_MODE] = token } }
    suspend fun setMapDefaultLabelMode(token: String)   { dataStore.edit { it[SettingsKeys.MAP_DEFAULT_LABEL_MODE] = token } }
    suspend fun setMapShowMyLocationDefault(v: Boolean) { dataStore.edit { it[SettingsKeys.MAP_SHOW_MY_LOCATION_DEFAULT] = v } }
    suspend fun setMapToolsOpenDefault(v: Boolean)      { dataStore.edit { it[SettingsKeys.MAP_TOOLS_OPEN_DEFAULT] = v } }
    suspend fun setMapDrawerExpandedDefault(v: Boolean) { dataStore.edit { it[SettingsKeys.MAP_DRAWER_EXPANDED_DEFAULT] = v } }
}
