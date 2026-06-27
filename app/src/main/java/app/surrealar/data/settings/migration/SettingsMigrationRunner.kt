package app.surrealar.data.settings.migration

import app.surrealar.settings.SettingsDefaults

/** Outcome of a settings-migration run (surfaced in diagnostics). */
data class SettingsMigrationResult(
    val fromVersion: Int,
    val toVersion: Int,
    val migrated: Boolean,
    val error: String? = null,
)

/**
 * Runs settings-storage migrations in order and stamps the current schema version.
 *
 * Designed to be **idempotent** and to reuse the existing DataStore (the caller supplies
 * [readVersion]/[writeVersion] backed by the single `SettingsLocalDataSource`) — it never creates a
 * second DataStore instance. Decoupled from DataStore via those lambdas so it is unit-testable.
 *
 * Current state: the v0 → v1 step is a **no-op version stamp**. Legacy enum-name tokens
 * ("INTERNAL", "TCP", …) are already normalized lazily at read/write time via each enum's
 * `fromPrefKey`/`prefKey`, so no rewrite is needed yet. Future format changes (key renames, grouped
 * settings) add an ordered step here and bump [SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION].
 */
class SettingsMigrationRunner(
    private val readVersion: suspend () -> Int?,
    private val writeVersion: suspend (Int) -> Unit,
) {
    suspend fun migrateIfNeeded(): SettingsMigrationResult {
        val result = try {
            val from = readVersion() ?: 0   // absent = pre-versioning install
            val target = SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION
            if (from >= target) {
                SettingsMigrationResult(from, from, migrated = false)
            } else {
                // Ordered migrations would run here for versions (from+1)..target.
                // v1: no structural change (enum tokens normalized lazily on read/write).
                writeVersion(target)
                SettingsMigrationResult(from, target, migrated = true)
            }
        } catch (e: Exception) {
            SettingsMigrationResult(
                fromVersion = -1,
                toVersion = SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION,
                migrated = false,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
        lastResult = result
        return result
    }

    companion object {
        /** Result of the most recent migration this session (for the diagnostic snapshot). */
        @Volatile
        var lastResult: SettingsMigrationResult? = null
            private set
    }
}
