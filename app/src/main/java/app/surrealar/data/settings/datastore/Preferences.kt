package app.surrealar.data.settings.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.surrealar.settings.SettingsDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Local raw persistence ONLY (no domain enums/models here)
private const val DATASTORE_NAME = "app_settings"
/**
 * A corrupt settings file (battery died mid-write) would otherwise throw on every read and crash every
 * launch until the user cleared app data. Fall back to defaults and log it instead.
 */
val settingsCorruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { e ->
    app.surrealar.util.DiagnosticsLogger.e(app.surrealar.util.diagnostics.DiagTags.SETTINGS,"Settings reset to defaults: settings file corrupt", e)
    androidx.datastore.preferences.core.emptyPreferences()
}

val Context.appDataStore by preferencesDataStore(name = DATASTORE_NAME, corruptionHandler = settingsCorruptionHandler)

/**
 * Typed DataStore preference keys for the single `app_settings` store. Each key name is a persisted
 * token — renaming one silently drops the user's saved value (handle key changes via
 * `SettingsMigrationRunner`). These are raw storage keys only; defaults and domain types live in
 * `SettingsDefaults` and the settings repository.
 */
object SettingsKeys {
    val LOCATION_SOURCE = stringPreferencesKey("location_source")
    val EXTERNAL_CONN_TYPE = stringPreferencesKey("external_connection_type")
    val EXTERNAL_TCP_HOST = stringPreferencesKey("external_tcp_host")
    val EXTERNAL_TCP_PORT = intPreferencesKey("external_tcp_port")
    val EXTERNAL_TCP_NAME = stringPreferencesKey("external_tcp_name")
    val EXTERNAL_RECEIVER_PROFILE = stringPreferencesKey("external_receiver_profile")
    /** Phase 4 WP C: Emlid Flow "compensated NMEA output" as declared by the user (RS4 / RS4 Pro). */
    val EXTERNAL_COMPENSATED_NMEA_OUTPUT = stringPreferencesKey("external_compensated_nmea_output")
    /** Phase 5: receivers that connected successfully, JSON list (newest first, at most 8); see RecentReceiversCodec. */
    val EXTERNAL_RECENT_RECEIVERS = stringPreferencesKey("external_recent_receivers")
    /**
     * Phase 5: who chose [EXTERNAL_RECEIVER_PROFILE]: "user", "receiver" or "default". Absent on installs from
     * before Phase 5; see ProfileOrigin.resolve (absent + a stored profile = "user").
     */
    val EXTERNAL_RECEIVER_PROFILE_ORIGIN = stringPreferencesKey("external_receiver_profile_origin")

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
    // Coordinate system (how coordinates are expressed; stored coordinates are never changed)
    val COORD_HORIZONTAL_SYSTEM   = stringPreferencesKey("coord_horizontal_system")
    val COORD_OUTPUT_DATUM        = stringPreferencesKey("coord_output_datum")
    val COORD_LINEAR_UNIT         = stringPreferencesKey("coord_linear_unit")
    val COORD_VERTICAL_REFERENCE  = stringPreferencesKey("coord_vertical_reference")
    val COORD_EXTERNAL_RX_DATUM   = stringPreferencesKey("coord_external_receiver_datum")
    /** Phase 3: "Modernized NSRS 2022 (beta preview)" opt-in; absent = OFF. */
    val COORD_NSRS2022_PREVIEW    = booleanPreferencesKey("coord_nsrs2022_beta_preview")
    /** Phase 4 WP X: SPCS2022 (beta) zone as the CRS registry id "spcs2022:<zone code>"; absent = none chosen. */
    val COORD_SPCS2022_ZONE       = stringPreferencesKey("coord_spcs2022_zone")
    /**
     * Phase 4 stabilization: the last SPCS83 zone (EPSG) chosen, kept while another system is selected so that
     * returning to State Plane restores it. The State Plane pref value ("spcs83:<EPSG>") stays authoritative
     * while State Plane is selected; absent = none remembered (the model default).
     */
    val COORD_SPCS83_ZONE_EPSG    = intPreferencesKey("coord_spcs83_zone_epsg")
    /** Points CSV export option: include the "Position reference" column in every export; absent = false (six columns). */
    val EXPORT_CSV_POSITION_REFERENCE = booleanPreferencesKey("export_csv_position_reference")
    val GNSS_CAPTURE_PRESET            = stringPreferencesKey("gnss_capture_preset")
    /** Custom precision-based stop target (m); absent or <= 0 = off. */
    val GNSS_CAPTURE_PRECISION_TARGET_M = doublePreferencesKey("gnss_capture_precision_target_m")

    // AR Display settings
    // v2: default became AUTO (per point: RTK-grade stored height, else terrain). New key so devices that
// had STORED/TERRAIN saved start on AUTO.
val AR_ALTITUDE_MODE               = stringPreferencesKey("ar_altitude_mode_v2")
    val AR_DISTANCE_FILTER_INDEX       = intPreferencesKey("ar_distance_filter_index")
    val AR_SHOW_DEBUG_OVERLAY          = booleanPreferencesKey("ar_show_debug_overlay")
    val AR_SHOW_LABELS                 = booleanPreferencesKey("ar_show_labels")
    val AR_SHOW_OFFSCREEN_ARROWS       = booleanPreferencesKey("ar_show_offscreen_arrows")
    // v2: the default changed 2.0 → 1.0 (true scale). A new key so devices that stored the old
// default ("2.0" — every AR settings save wrote it) start at true scale instead of keeping 2×.
val AR_MODEL_SCALE                 = stringPreferencesKey("ar_model_scale_v2")
// RETIRED 2026-11: the AR "antenna above tablet" value moved to the Receiver page as
// CAMERA_BELOW_RECEIVER_M. Still read once to convert an existing value (see SettingsRepositoryImpl).
val AR_ANTENNA_ABOVE_CAMERA_M      = stringPreferencesKey("ar_antenna_above_camera_m")
    val AR_DEBUG_TOOLS_ENABLED         = booleanPreferencesKey("ar_debug_tools_enabled")

    // GNSS receiver
    val HIGH_ACCURACY = booleanPreferencesKey("high_accuracy")
    // RETIRED 2026-11: pole height to the antenna phase centre. Read as a fallback for POLE_HEIGHT_ARP_M
    // (and to flag that the user should re-check it); never written any more.
    val ANTENNA_HEIGHT_M = doublePreferencesKey("antenna_height_m")
    val INTERNAL_ANTENNA_HEIGHT_M = doublePreferencesKey("internal_antenna_height_m")
    // External antenna setup (2026-11): heights measured to the bottom of the receiver.
    val POLE_HEIGHT_ARP_M = doublePreferencesKey("pole_height_arp_m")
    val CAMERA_BELOW_RECEIVER_M = doublePreferencesKey("camera_below_receiver_m")
    val PHASE_CENTER_OFFSET_OVERRIDE_M = doublePreferencesKey("phase_center_offset_override_m")
    val POLE_HEIGHT_CHECKED = booleanPreferencesKey("pole_height_checked")

    // Developer settings
    val DEV_TOOLS_ENABLED = booleanPreferencesKey("dev_tools_enabled")

    // Mock location publishing
    val MOCK_LOCATION_ENABLED          = booleanPreferencesKey("mock_location_enabled")

    // AR model visibility (AR-only; separate from coordinate renderEnabled / map selection)
    val AR_VISIBILITY_MODE             = stringPreferencesKey("ar_visibility_mode")
    val AR_VISIBILITY_CUSTOMIZED       = booleanPreferencesKey("ar_visibility_customized")
    val AR_VISIBLE_IDS                 = stringSetPreferencesKey("ar_visible_ids")

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
/** Stored values behind [app.surrealar.gnss.settings.GnssReceiverSettings]' external-antenna fields; null = not stored. */
data class ExternalAntennaRaw(
    val poleHeightM: Double?,
    val legacyAntennaHeightM: Double?,
    val cameraBelowReceiverM: Double?,
    val legacyAntennaAboveCameraM: String?,
    val phaseCenterOffsetOverrideM: Double?,
    val poleHeightChecked: Boolean,
)

class SettingsLocalDataSource(private val dataStore: DataStore<Preferences>) {

    /** Production constructor — uses the app-wide DataStore singleton (single instance per file). */
    constructor(context: Context) : this(context.appDataStore)

    /** Writes several keys in one DataStore transaction, so a settings group is saved all-or-nothing. */
    suspend fun editAll(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { transform(it) }
    }

    val locationSourceRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] }
    val externalConnTypeRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] }
    val externalTcpHost: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }
    val externalTcpName: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_NAME] }
    val externalReceiverProfileRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE] }
    val externalCompensatedNmeaOutputRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_COMPENSATED_NMEA_OUTPUT] }
    val externalRecentReceiversRaw: Flow<String?> = dataStore.data.map { it[SettingsKeys.EXTERNAL_RECENT_RECEIVERS] }
    /** The stored origin token and whether a profile is stored at all, from one snapshot. */
    val externalReceiverProfileOriginRaw: Flow<Pair<String?, Boolean>> = dataStore.data.map {
        it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE_ORIGIN] to (it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE] != null)
    }
    suspend fun setExternalRecentReceivers(json: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_RECENT_RECEIVERS] = json } }
    /** Profile and its origin in one transaction. */
    suspend fun setExternalReceiverProfileWithOrigin(prefKey: String, origin: String) { dataStore.edit {
        it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE] = prefKey; it[SettingsKeys.EXTERNAL_RECEIVER_PROFILE_ORIGIN] = origin } }
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
    val gnssCapturePreset:          Flow<String>  = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_PRESET]           ?: SettingsDefaults.gnssCapture.preset.prefKey }
    // Coordinate system flows (null = never set → model defaults)
    val coordHorizontalSystem:  Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_HORIZONTAL_SYSTEM] }
    val coordOutputDatum:       Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_OUTPUT_DATUM] }
    val coordLinearUnit:        Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_LINEAR_UNIT] }
    val coordVerticalReference: Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_VERTICAL_REFERENCE] }
    val coordExternalRxDatum:   Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_EXTERNAL_RX_DATUM] }
    val coordNsrs2022Preview:   Flow<Boolean> = dataStore.data.map { it[SettingsKeys.COORD_NSRS2022_PREVIEW] ?: false }
    val coordSpcs2022Zone:      Flow<String?> = dataStore.data.map { it[SettingsKeys.COORD_SPCS2022_ZONE] }
    val coordSpcs83ZoneEpsg:    Flow<Int?>    = dataStore.data.map { it[SettingsKeys.COORD_SPCS83_ZONE_EPSG] }
    val exportCsvPositionReference: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.EXPORT_CSV_POSITION_REFERENCE] ?: false }
    suspend fun setExportCsvPositionReference(v: Boolean) { dataStore.edit { it[SettingsKeys.EXPORT_CSV_POSITION_REFERENCE] = v } }
    val gnssCapturePrecisionTargetM: Flow<Double?> = dataStore.data.map { it[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M]?.takeIf { v -> v > 0.0 } }

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
    /** Raw stored external-antenna values, from one snapshot (converted in SettingsRepositoryImpl). */
    val externalAntennaRaw: Flow<ExternalAntennaRaw> = dataStore.data.map {
        ExternalAntennaRaw(
            poleHeightM = it[SettingsKeys.POLE_HEIGHT_ARP_M],
            legacyAntennaHeightM = it[SettingsKeys.ANTENNA_HEIGHT_M],
            cameraBelowReceiverM = it[SettingsKeys.CAMERA_BELOW_RECEIVER_M],
            legacyAntennaAboveCameraM = it[SettingsKeys.AR_ANTENNA_ABOVE_CAMERA_M],
            phaseCenterOffsetOverrideM = it[SettingsKeys.PHASE_CENTER_OFFSET_OVERRIDE_M],
            poleHeightChecked = it[SettingsKeys.POLE_HEIGHT_CHECKED] ?: false,
        )
    }
    val internalAntennaHeightM: Flow<Double> = dataStore.data.map { it[SettingsKeys.INTERNAL_ANTENNA_HEIGHT_M] ?: SettingsDefaults.gnssReceiver.internalAntennaHeightM }

    // Developer settings
    val developerToolsEnabled: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.DEV_TOOLS_ENABLED] ?: SettingsDefaults.developer.developerToolsEnabled }

    // Mock location
    val mockLocationEnabled: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.MOCK_LOCATION_ENABLED] ?: SettingsDefaults.mockLocationEnabled }

    // AR model visibility (AR-only)
    val arVisibilityMode: Flow<String> = dataStore.data.map { it[SettingsKeys.AR_VISIBILITY_MODE] ?: "SELECTED" }
    val arVisibilityCustomized: Flow<Boolean> = dataStore.data.map { it[SettingsKeys.AR_VISIBILITY_CUSTOMIZED] ?: false }
    val arVisibleIds: Flow<Set<String>> = dataStore.data.map { it[SettingsKeys.AR_VISIBLE_IDS] ?: emptySet() }

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
    suspend fun setExternalCompensatedNmeaOutput(prefKey: String) { dataStore.edit { it[SettingsKeys.EXTERNAL_COMPENSATED_NMEA_OUTPUT] = prefKey } }
    suspend fun clearExternalTcp() { dataStore.edit { prefs ->
        prefs.remove(SettingsKeys.EXTERNAL_TCP_HOST); prefs.remove(SettingsKeys.EXTERNAL_TCP_PORT); prefs.remove(SettingsKeys.EXTERNAL_TCP_NAME) } }

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
    suspend fun setGnssCapturePreset(v: String)         { dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_PRESET]           = v } }
    /**
     * Writes the coordinate system in one edit; [nsrs2022Preview] null leaves the preview key untouched. The
     * SPCS2022 zone key is written only with [writeSpcs2022Zone] (null [spcs2022Zone] then removes it).
     */
    suspend fun setCoordinateSystem(horizontal: String, datum: String, unit: String, vertical: String, externalRxDatum: String,
                                    nsrs2022Preview: Boolean? = null, spcs2022Zone: String? = null, writeSpcs2022Zone: Boolean = false,
                                    spcs83ZoneEpsg: Int? = null) {
        dataStore.edit {
            it[SettingsKeys.COORD_HORIZONTAL_SYSTEM] = horizontal
            if (spcs83ZoneEpsg != null) it[SettingsKeys.COORD_SPCS83_ZONE_EPSG] = spcs83ZoneEpsg
            it[SettingsKeys.COORD_OUTPUT_DATUM] = datum
            it[SettingsKeys.COORD_LINEAR_UNIT] = unit
            it[SettingsKeys.COORD_VERTICAL_REFERENCE] = vertical
            it[SettingsKeys.COORD_EXTERNAL_RX_DATUM] = externalRxDatum
            if (nsrs2022Preview != null) it[SettingsKeys.COORD_NSRS2022_PREVIEW] = nsrs2022Preview
            if (writeSpcs2022Zone) {
                if (spcs2022Zone.isNullOrBlank()) it.remove(SettingsKeys.COORD_SPCS2022_ZONE)
                else it[SettingsKeys.COORD_SPCS2022_ZONE] = spcs2022Zone
            }
        }
    }
    suspend fun setGnssCapturePrecisionTargetM(v: Double?) { dataStore.edit { prefs -> if (v != null && v > 0.0) { prefs[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M] = v } else { prefs.remove(SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M) } } }

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
    suspend fun setInternalAntennaHeightM(meters: Double) { dataStore.edit { it[SettingsKeys.INTERNAL_ANTENNA_HEIGHT_M] = meters } }

    // Developer settings setter
    suspend fun setDeveloperToolsEnabled(enabled: Boolean) { dataStore.edit { it[SettingsKeys.DEV_TOOLS_ENABLED] = enabled } }

    // Mock location setter
    suspend fun setMockLocationEnabled(v: Boolean) { dataStore.edit { it[SettingsKeys.MOCK_LOCATION_ENABLED] = v } }

    // AR model visibility setters
    suspend fun setArVisibilityMode(v: String)       { dataStore.edit { it[SettingsKeys.AR_VISIBILITY_MODE] = v } }
    suspend fun setArVisibilityCustomized(v: Boolean) { dataStore.edit { it[SettingsKeys.AR_VISIBILITY_CUSTOMIZED] = v } }
    suspend fun setArVisibleIds(v: Set<String>)      { dataStore.edit { it[SettingsKeys.AR_VISIBLE_IDS] = v } }

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
