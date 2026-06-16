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
    val EXTERNAL_BT_NAME = stringPreferencesKey("external_bt_device_name")
    val EXTERNAL_TCP_HOST = stringPreferencesKey("external_tcp_host")
    val EXTERNAL_TCP_PORT = intPreferencesKey("external_tcp_port")
    val EXTERNAL_TCP_NAME = stringPreferencesKey("external_tcp_name")

    // USB/Serial connection settings
    val EXTERNAL_USB_DEVICE_PATH = stringPreferencesKey("external_usb_device_path")
    val EXTERNAL_SERIAL_BAUD_RATE = intPreferencesKey("external_serial_baud_rate")

    // Radio connection settings
    val EXTERNAL_RADIO_FREQUENCY = stringPreferencesKey("external_radio_frequency")
    val EXTERNAL_RADIO_CHANNEL = intPreferencesKey("external_radio_channel")

    // Connection behavior settings
    val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    val CONNECTION_TIMEOUT_SECONDS = intPreferencesKey("connection_timeout_seconds")
    val MAX_RECONNECT_ATTEMPTS = intPreferencesKey("max_reconnect_attempts")

    // Data quality settings
    val VALIDATE_NMEA_CHECKSUM = booleanPreferencesKey("validate_nmea_checksum")
    val REQUIRE_MIN_SATELLITES = intPreferencesKey("require_min_satellites")
    val MAX_HDOP_THRESHOLD = stringPreferencesKey("max_hdop_threshold")
    val REQUIRE_RTK_FIXED = booleanPreferencesKey("require_rtk_fixed")

    // NMEA logging settings
    val NMEA_LOGGING_ENABLED = booleanPreferencesKey("nmea_logging_enabled")
    val NMEA_LOG_MAX_FILE_SIZE_MB = intPreferencesKey("nmea_log_max_file_size_mb")

    // Survey project settings
    val CURRENT_PROJECT_ID = stringPreferencesKey("current_project_id")
    val DEFAULT_COORDINATE_NAME_PREFIX = stringPreferencesKey("default_coordinate_name_prefix")
    val AUTO_INCREMENT_COORDINATE_NAMES = booleanPreferencesKey("auto_increment_coordinate_names")

    // UI preferences
    val COORDINATE_DISPLAY_FORMAT = stringPreferencesKey("coordinate_display_format")
    val UNITS_DISTANCE = stringPreferencesKey("units_distance")
    val UNITS_AREA = stringPreferencesKey("units_area")
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

    // Coordinate display — new key (other keys already existed above)
    val SHOW_RTK_STATUS_BADGES         = booleanPreferencesKey("show_rtk_status_badges")

    // GNSS receiver — high accuracy
    val HIGH_ACCURACY = booleanPreferencesKey("high_accuracy")

    // Developer settings
    val DEV_TOOLS_ENABLED = booleanPreferencesKey("dev_tools_enabled")

    // Advanced GNSS receiver — mock location publishing
    val MOCK_LOCATION_ENABLED          = booleanPreferencesKey("mock_location_enabled")

    // Diagnostics & logging — granular toggles (NMEA_LOGGING_* keys already existed above)
    val DIAG_SHOW_RAW_NMEA             = booleanPreferencesKey("diag_show_raw_nmea")
    val DIAG_SHOW_SATELLITE_DIAG       = booleanPreferencesKey("diag_show_satellite_diagnostics")
    val DIAG_LOG_RAW_NMEA_LINES        = booleanPreferencesKey("diag_log_raw_nmea_lines")
    val DIAG_LOG_PARSED_FIXES          = booleanPreferencesKey("diag_log_parsed_fixes")
    val DIAG_LOG_SATELLITE_DETAILS     = booleanPreferencesKey("diag_log_satellite_details")
}

/**
 * Thin DataStore accessor exposing raw primitive flows. Mapping to domain types lives in repository impl.
 */
class SettingsLocalDataSource(private val context: Context) {
    val locationSourceRaw: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] }
    val externalConnTypeRaw: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] }
    val externalBtAddress: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_ADDR] }
    val externalBtName: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_NAME] }
    val externalTcpHost: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }
    val externalTcpName: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_NAME] }
    val externalUsbDevicePath: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_USB_DEVICE_PATH] }
    val externalSerialBaudRate: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_SERIAL_BAUD_RATE] }
    val externalRadioFrequency: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_RADIO_FREQUENCY] }
    val externalRadioChannel: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_RADIO_CHANNEL] }

    // Connection behavior settings
    val autoReconnect: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AUTO_RECONNECT] ?: false }
    val connectionTimeoutSeconds: Flow<Int> = context.appDataStore.data.map { it[SettingsKeys.CONNECTION_TIMEOUT_SECONDS] ?: 30 }
    val maxReconnectAttempts: Flow<Int> = context.appDataStore.data.map { it[SettingsKeys.MAX_RECONNECT_ATTEMPTS] ?: 3 }

    // Data quality settings
    val validateNmeaChecksum: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.VALIDATE_NMEA_CHECKSUM] ?: false }
    val requireMinSatellites: Flow<Int> = context.appDataStore.data.map { it[SettingsKeys.REQUIRE_MIN_SATELLITES] ?: 4 }
    val maxHdopThreshold: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.MAX_HDOP_THRESHOLD] ?: "2.0" }
    val requireRtkFixed: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.REQUIRE_RTK_FIXED] ?: false }

    // NMEA logging flows
    val nmeaLoggingEnabled: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.NMEA_LOGGING_ENABLED] ?: false }
    val nmeaLogMaxFileSizeMB: Flow<Int> = context.appDataStore.data.map { it[SettingsKeys.NMEA_LOG_MAX_FILE_SIZE_MB] ?: 5 }

    // Survey project settings
    val currentProjectId: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.CURRENT_PROJECT_ID] }
    val defaultCoordinateNamePrefix: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] }
    val autoIncrementCoordinateNames: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] ?: false }

    // UI preferences
    val coordinateDisplayFormat: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.COORDINATE_DISPLAY_FORMAT] }
    val unitsDistance: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.UNITS_DISTANCE] }
    val unitsArea: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.UNITS_AREA] }
    val showAccuracyIndicators: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.SHOW_ACCURACY_INDICATORS] ?: false }

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

    // Coordinate display — supplemental key
    val showRtkStatusBadges:      Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.SHOW_RTK_STATUS_BADGES]   ?: true    }

    // Diagnostics — granular toggle flows
    val diagShowRawNmea:          Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DIAG_SHOW_RAW_NMEA]           ?: false }
    val diagShowSatelliteDiag:    Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DIAG_SHOW_SATELLITE_DIAG]     ?: false }
    val diagLogRawNmeaLines:      Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DIAG_LOG_RAW_NMEA_LINES]      ?: false }
    val diagLogParsedFixes:       Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DIAG_LOG_PARSED_FIXES]        ?: false }
    val diagLogSatelliteDetails:  Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DIAG_LOG_SATELLITE_DETAILS]   ?: false }

    suspend fun setLocationSourceString(v: String) { context.appDataStore.edit { it[SettingsKeys.LOCATION_SOURCE] = v } }
    suspend fun setExternalConnTypeString(v: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_CONN_TYPE] = v } }
    suspend fun setExternalTcp(host: String, port: Int) { context.appDataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_TCP_HOST] = host; prefs[SettingsKeys.EXTERNAL_TCP_PORT] = port } }
    suspend fun setExternalTcpName(name: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_TCP_NAME] = name } }
    suspend fun clearExternalTcp() { context.appDataStore.edit { prefs ->
        prefs.remove(SettingsKeys.EXTERNAL_TCP_HOST); prefs.remove(SettingsKeys.EXTERNAL_TCP_PORT); prefs.remove(SettingsKeys.EXTERNAL_TCP_NAME) } }
    suspend fun setExternalBtAddress(address: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_BT_ADDR] = address } }
    suspend fun setExternalBtName(name: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_BT_NAME] = name } }
    suspend fun setExternalUsbDevicePath(path: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_USB_DEVICE_PATH] = path } }
    suspend fun setExternalSerialBaudRate(baudRate: Int) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_SERIAL_BAUD_RATE] = baudRate } }
    suspend fun setExternalRadioFrequency(frequency: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_RADIO_FREQUENCY] = frequency } }
    suspend fun setExternalRadioChannel(channel: Int) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_RADIO_CHANNEL] = channel } }

    // Connection behavior setters
    suspend fun setAutoReconnect(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.AUTO_RECONNECT] = enabled } }
    suspend fun setConnectionTimeoutSeconds(seconds: Int) { context.appDataStore.edit { it[SettingsKeys.CONNECTION_TIMEOUT_SECONDS] = seconds } }
    suspend fun setMaxReconnectAttempts(attempts: Int) { context.appDataStore.edit { it[SettingsKeys.MAX_RECONNECT_ATTEMPTS] = attempts } }

    // Data quality setters
    suspend fun setValidateNmeaChecksum(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.VALIDATE_NMEA_CHECKSUM] = enabled } }
    suspend fun setRequireMinSatellites(minSatellites: Int) { context.appDataStore.edit { it[SettingsKeys.REQUIRE_MIN_SATELLITES] = minSatellites } }
    suspend fun setMaxHdopThreshold(threshold: String) { context.appDataStore.edit { it[SettingsKeys.MAX_HDOP_THRESHOLD] = threshold } }
    suspend fun setRequireRtkFixed(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.REQUIRE_RTK_FIXED] = enabled } }

    // NMEA logging setters
    suspend fun setNmeaLoggingEnabled(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.NMEA_LOGGING_ENABLED] = enabled } }
    suspend fun setNmeaLogMaxFileSizeMB(sizeMB: Int) { context.appDataStore.edit { it[SettingsKeys.NMEA_LOG_MAX_FILE_SIZE_MB] = sizeMB } }

    // Survey project settings
    suspend fun setCurrentProjectId(projectId: String) { context.appDataStore.edit { it[SettingsKeys.CURRENT_PROJECT_ID] = projectId } }
    suspend fun setDefaultCoordinateNamePrefix(prefix: String) { context.appDataStore.edit { it[SettingsKeys.DEFAULT_COORDINATE_NAME_PREFIX] = prefix } }
    suspend fun setAutoIncrementCoordinateNames(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.AUTO_INCREMENT_COORDINATE_NAMES] = enabled } }

    // UI preferences
    suspend fun setCoordinateDisplayFormat(format: String) { context.appDataStore.edit { it[SettingsKeys.COORDINATE_DISPLAY_FORMAT] = format } }
    suspend fun setUnitsDistance(units: String) { context.appDataStore.edit { it[SettingsKeys.UNITS_DISTANCE] = units } }
    suspend fun setUnitsArea(units: String) { context.appDataStore.edit { it[SettingsKeys.UNITS_AREA] = units } }
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

    // Coordinate display setter
    suspend fun setShowRtkStatusBadges(v: Boolean)      { context.appDataStore.edit { it[SettingsKeys.SHOW_RTK_STATUS_BADGES]    = v } }

    // GNSS receiver high accuracy
    val highAccuracy: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.HIGH_ACCURACY] ?: true }
    suspend fun setHighAccuracy(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.HIGH_ACCURACY] = enabled } }

    // Developer settings
    val developerToolsEnabled: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.DEV_TOOLS_ENABLED] ?: false }
    suspend fun setDeveloperToolsEnabled(enabled: Boolean) { context.appDataStore.edit { it[SettingsKeys.DEV_TOOLS_ENABLED] = enabled } }

    // Mock location flow and setter
    val mockLocationEnabled: Flow<Boolean> = context.appDataStore.data.map { it[SettingsKeys.MOCK_LOCATION_ENABLED] ?: false }
    suspend fun setMockLocationEnabled(v: Boolean) { context.appDataStore.edit { it[SettingsKeys.MOCK_LOCATION_ENABLED] = v } }

    // Diagnostics setters
    suspend fun setDiagShowRawNmea(v: Boolean)          { context.appDataStore.edit { it[SettingsKeys.DIAG_SHOW_RAW_NMEA]           = v } }
    suspend fun setDiagShowSatelliteDiag(v: Boolean)    { context.appDataStore.edit { it[SettingsKeys.DIAG_SHOW_SATELLITE_DIAG]     = v } }
    suspend fun setDiagLogRawNmeaLines(v: Boolean)      { context.appDataStore.edit { it[SettingsKeys.DIAG_LOG_RAW_NMEA_LINES]      = v } }
    suspend fun setDiagLogParsedFixes(v: Boolean)       { context.appDataStore.edit { it[SettingsKeys.DIAG_LOG_PARSED_FIXES]        = v } }
    suspend fun setDiagLogSatelliteDetails(v: Boolean)  { context.appDataStore.edit { it[SettingsKeys.DIAG_LOG_SATELLITE_DETAILS]   = v } }
}
