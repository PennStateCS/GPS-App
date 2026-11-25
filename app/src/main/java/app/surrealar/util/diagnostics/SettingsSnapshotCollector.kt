package app.surrealar.util.diagnostics

import android.content.Context
import app.surrealar.data.settings.datastore.appDataStore
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.flow.first

/**
 * Redaction rules shared by the settings snapshot and the SETTINGS change log.
 *  - sensitive keys (passwords, NTRIP credentials, API keys, tokens) → "(set)" / "(empty)"
 *  - keys on [ALLOWLIST] → their value
 *  - anything else → "(not included)"
 */
object SettingsRedaction {

    /** Persisted keys whose values are safe to show. New keys stay hidden until added here. */
    val ALLOWLIST: Set<String> = setOf(
        "location_source", "external_connection_type", "external_tcp_host", "external_tcp_port",
        "external_tcp_name", "external_receiver_profile", "external_compensated_nmea_output", "settings_schema_version",
        "default_coordinate_name_prefix", "auto_increment_coordinate_names", "show_accuracy_indicators",
        "gnss_capture_rtk_status", "gnss_capture_min_duration_sec", "gnss_capture_max_duration_sec",
        "gnss_capture_min_samples", "gnss_capture_max_fix_age_sec", "gnss_capture_max_diff_age_sec",
        "gnss_capture_preset", "gnss_capture_precision_target_m",
        "coord_horizontal_system", "coord_output_datum", "coord_linear_unit", "coord_vertical_reference",
        "coord_external_receiver_datum", "coord_nsrs2022_beta_preview",
        "ar_altitude_mode_v2", "ar_distance_filter_index", "ar_show_debug_overlay", "ar_show_labels",
        "ar_show_offscreen_arrows", "ar_model_scale_v2", "ar_antenna_above_camera_m", "ar_debug_tools_enabled",
        "high_accuracy", "antenna_height_m", "internal_antenna_height_m", "dev_tools_enabled",
        "pole_height_arp_m", "camera_below_receiver_m", "phase_center_offset_override_m", "pole_height_checked",
        "mock_location_enabled", "ar_visibility_mode", "ar_visibility_customized", "ar_visible_ids",
        "app_theme_mode", "show_live_gnss_status_bar", "keep_screen_awake", "max_brightness_while_open",
        "map_default_type", "map_default_grid_mode", "map_default_label_mode", "map_show_my_location_default",
        "map_tools_open_default", "map_drawer_expanded_default",
        "stakeout_tolerance_m", "stakeout_warning_accuracy_m", "stakeout_enable_haptics", "stakeout_enable_audio",
        "stakeout_keep_screen_on", "stakeout_compass_heading",
    )

    private val SENSITIVE = Regex("(?i)(pass(word|wd)?|pwd|secret|token|api_?key|credential|auth|ntrip_?(user|login|mount))")

    fun isSensitive(key: String) = SENSITIVE.containsMatchIn(key)

    /** What may be shown for [value] stored under [key]. */
    fun describe(key: String, value: Any?): String = when {
        isSensitive(key) -> if (value == null || value.toString().isEmpty()) "(empty)" else "(set)"
        key !in ALLOWLIST -> "(not included)"
        value is Set<*> -> "${value.size} item(s)"
        else -> value.toString()
    }
}

/**
 * The settings-snapshot.txt section, generated from the settings store (DataStore `app_settings`)
 * so it can't go stale: every allowlisted key with its stored value or "(default)", plus every other
 * stored key as "<key>: (not included)". Sensitive values are "(set)"/"(empty)".
 */
object SettingsSnapshotCollector {

    /** Pure renderer: [stored] = key name → value for the keys that have been saved. */
    fun render(stored: Map<String, Any?>, extras: List<Pair<String, String>> = emptyList()): String = buildString {
        appendLine("=== Current Settings Snapshot ===")
        appendLine("Generated from the settings store. \"(default)\" = never changed (the app uses its built-in default);")
        appendLine("\"[changed]\" = a value has been saved (it may still equal the default).")
        appendLine("Passwords, NTRIP credentials, API keys and tokens show only as (set) / (empty).")
        appendLine()
        val keys = (SettingsRedaction.ALLOWLIST + stored.keys).sorted()
        val width = (keys.maxOfOrNull { it.length } ?: 0).coerceAtMost(40)
        appendLine("--- Settings (${stored.size} changed, ${keys.size - stored.size} default) ---")
        for (k in keys) {
            val shown = when {
                k in stored -> SettingsRedaction.describe(k, stored[k]) + "  [changed]"
                SettingsRedaction.isSensitive(k) -> "(empty)"
                k in SettingsRedaction.ALLOWLIST -> "(default)"
                else -> "(not included)"
            }
            appendLine("${k.padEnd(width)} : $shown")
        }
        if (extras.isNotEmpty()) {
            appendLine()
            appendLine("--- Other ---")
            extras.forEach { (k, v) -> appendLine("${k.padEnd(width)} : $v") }
        }
    }

    suspend fun collect(context: Context): String {
        val stored = context.applicationContext.appDataStore.data.first().asMap().mapKeys { it.key.name }
        val mig = app.surrealar.data.settings.migration.SettingsMigrationRunner.lastResult
        val logFiles = DiagnosticsLogger.logFiles()
        val extras = listOf(
            "maps_api_key" to SettingsRedaction.describe("maps_api_key", readMapsApiKey(context)),
            "settings_last_migration" to when {
                mig == null -> "not run yet this session"
                mig.error != null -> "ERROR (${mig.error})"
                mig.migrated -> "migrated v${mig.fromVersion} -> v${mig.toVersion}"
                else -> "up to date (v${mig.toVersion})"
            },
            "diagnostic_log_files" to "${logFiles.size} (${logFiles.sumOf { it.length() } / 1024} KB)",
        )
        return render(stored, extras)
    }

    private fun readMapsApiKey(context: Context): String? = try {
        context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY")
    } catch (_: Exception) { null }
}