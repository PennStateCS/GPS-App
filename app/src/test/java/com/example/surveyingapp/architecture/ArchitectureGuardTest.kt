package com.example.surveyingapp.architecture

import org.junit.Assert.fail
import java.io.File
import org.junit.Test

/**
 * Lightweight, dependency-free architecture guards.
 *
 * These scan the Kotlin sources under `app/src/main/java` for patterns we have deliberately moved
 * away from (service-locator access, direct DB/repository construction, provider activation outside
 * the coordinator, raw API-key logging, exact live-coordinate logging). Each rule carries an
 * allowlist of the files where the pattern is currently still expected; every allowlist entry is
 * documented inline as either PERMANENT (intentional) or TEMPORARY (tracked for a follow-up
 * cleanup). When a follow-up removes a usage, tighten the corresponding allowlist so the guard
 * keeps newcomers from reintroducing it.
 *
 * Pure JUnit + java.io.File — no architecture-test libraries are pulled in.
 */
class ArchitectureGuardTest {

    private data class Violation(val file: String, val line: Int, val text: String)

    private val sourceRoot: File by lazy { locateSourceRoot() }

    /** Finds `app/src/main/java` regardless of the unit-test working directory. */
    private fun locateSourceRoot(): File {
        val direct = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        if (direct != null) return direct
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            listOf(File(dir, "app/src/main/java"), File(dir, "src/main/java"))
                .firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("ArchitectureGuardTest could not locate src/main/java from ${System.getProperty("user.dir")}")
    }

    /**
     * Scans every `.kt` file (excluding [allowFiles], matched by relative path) for [pattern].
     * Comment-only lines and the trailing `//` portion of code lines are ignored so documentation
     * references don't trip the guard. Lines matching [excludeLine] are skipped (e.g. redacted uses).
     */
    private fun scan(
        pattern: Regex,
        allowFiles: Set<String> = emptySet(),
        excludeLine: Regex? = null,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(sourceRoot).path.replace('\\', '/')
                if (rel in allowFiles) return@forEach
                file.readLines().forEachIndexed { idx, raw ->
                    val trimmed = raw.trimStart()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    val code = raw.substringBefore("//")
                    if (excludeLine != null && excludeLine.containsMatchIn(code)) return@forEachIndexed
                    if (pattern.containsMatchIn(code)) {
                        violations.add(Violation(rel, idx + 1, raw.trim()))
                    }
                }
            }
        return violations
    }

    private fun enforce(rule: String, preferred: String, violations: List<Violation>) {
        if (violations.isEmpty()) return
        val msg = buildString {
            appendLine("Architecture guard failed: $rule")
            appendLine("Preferred: $preferred")
            appendLine("Offending lines (add to the rule's allowlist only if intentional):")
            violations.forEach { appendLine("  ${it.file}:${it.line}  ->  ${it.text}") }
        }
        fail(msg)
    }

    // ── Rule 1: service-locator access to the settings singleton ────────────────
    //
    // GUIDANCE (not enforced): new ViewModels/consumers should inject the narrowest *focused*
    // settings interface they need (e.g. GnssCaptureSettingsRepository, ArDisplaySettingsRepository,
    // ExternalReceiverSettingsRepository) rather than the broad aggregate SettingsRepository. All of
    // them resolve to the same SettingsRepositoryImpl singleton (see di/SettingsModule.kt). This is
    // intentionally NOT a hard rule while the aggregate still backs large screens (SettingsFragment,
    // MainActivity); see docs/settings-architecture.md.

    @Test
    fun `no new SurveyingApp_settingsRepo service-locator usage`() {
        val allow = setOf(
            // PERMANENT: Hilt bridges SettingsRepository through the app singleton so there is
            // exactly one SettingsLocalDataSource/DataStore (SettingsModule.provideSettingsRepository).
            "com/example/surveyingapp/di/SettingsModule.kt",
            // DEFERRED: these are Kotlin `object`s (no constructor/field injection). Converting them
            // means threading SettingsRepository through their function signatures — a future pass.
            "com/example/surveyingapp/util/DiagnosticReportExporter.kt",
            "com/example/surveyingapp/util/diagnostics/SettingsSnapshotCollector.kt",
        )
        enforce(
            rule = "Direct service-locator access to SurveyingApp.settingsRepo",
            preferred = "Inject SettingsRepository via Hilt (@Inject field / @HiltViewModel constructor)",
            violations = scan(Regex("""SurveyingApp\.settingsRepo"""), allow),
        )
    }

    // ── Rule 2: direct Room database construction ───────────────────────────────

    @Test
    fun `no direct AppDatabase construction outside DI`() {
        val allow = setOf(
            // PERMANENT: the Hilt provider that owns the single Room connection.
            "com/example/surveyingapp/di/DatabaseModule.kt",
        )
        enforce(
            rule = "Direct AppDatabase.getDatabase(...) construction",
            preferred = "Inject AppDatabase / a DAO / a repository via Hilt (e.g. SurveyingApp's UTM backfill uses the Hilt EntryPoint)",
            violations = scan(Regex("""AppDatabase\.getDatabase\("""), allow),
        )
    }

    // ── Rule 3: concrete repository construction ────────────────────────────────

    @Test
    fun `no concrete repository construction in app code`() {
        // Repositories are Hilt-bound via @Binds; nothing should invoke the impl constructors.
        enforce(
            rule = "Direct CoordinateRepositoryImpl(...) / ModelRepositoryImpl(...) construction",
            preferred = "Inject the domain CoordinateRepository / ModelRepository interface via Hilt",
            violations = scan(Regex("""(Coordinate|Model)RepositoryImpl\(""")),
        )
    }

    // ── Rule 4: GNSS provider activation must go through the coordinator ─────────

    @Test
    fun `provider activation only through GnssSourceCoordinator`() {
        val allow = setOf(
            // PERMANENT: the single place allowed to flip the active provider.
            "com/example/surveyingapp/gnss/source/GnssSourceCoordinator.kt",
            // PERMANENT: the SourceSettings.setActiveProvider definition itself.
            "com/example/surveyingapp/gnss/source/SourceSettings.kt",
        )
        enforce(
            rule = "Direct setActiveProvider(...) call bypassing GnssSourceCoordinator",
            preferred = "Use GnssSourceCoordinator.activate*Provider(...) so validation/restore rules apply",
            violations = scan(Regex("""setActiveProvider\("""), allow),
        )
    }

    // ── Rule 5: raw API-key logging/export ──────────────────────────────────────

    @Test
    fun `no raw API key logging or export`() {
        // Flag a logging/diagnostics call that emits a key; the redactApiKey(...) helper is exempt.
        val keyInLog = Regex(
            """(Log\.[dwiev]|DiagnosticsLogger\.[a-z]+|append[a-zA-Z]*)\([^\n]*""" +
                """(apiKey|GOOGLE_MAPS_API_KEY|maps_api_key|geoApiKey)"""
        )
        enforce(
            rule = "Raw API key written to logs or the diagnostic report",
            preferred = "Use MapDiagnosticCollector.redactApiKey(...) or a presence/hash check only",
            violations = scan(keyInLog, excludeLine = Regex("redact")),
        )
    }

    // ── Rule 7: persisted enums must use a stable prefKey, not the constant name ──

    @Test
    fun `settings persistence does not store enum constant names`() {
        // Targets the settings write path specifically (`local.setX(enum.name)`), not `.name`
        // everywhere. Persisted enums expose a stable prefKey + fromPrefKey instead.
        enforce(
            rule = "Enum constant name persisted in a DataStore write (brittle to renames)",
            preferred = "Persist enum.prefKey and read with EnumType.fromPrefKey(...)",
            violations = scan(Regex("""local\.set\w+\([^)]*\.name\b""")),
        )
    }

    // ── Rule 6: exact live coordinates in persistent diagnostic logs ─────────────

    @Test
    fun `no exact live coordinates in persistent diagnostic logs`() {
        // No allowlist: persistent diagnostics must never carry exact live coordinates. Saved-
        // coordinate export features write coordinates through their own (non-DiagnosticsLogger) path.
        val allow = emptySet<String>()
        val coordInDiag = Regex(
            """DiagnosticsLogger\.[a-z]+\([^\n]*\b(lat|lon|latitude|longitude|latDeg|lonDeg)\b"""
        )
        enforce(
            rule = "Exact live coordinates logged to persistent diagnostics (privacy)",
            preferred = "Log non-location fields (status/hAcc/sats/ageMs); keep exact coords in saved-coordinate exports only",
            violations = scan(coordInDiag, allow),
        )
    }

    // ── Rule 8: single SettingsRepositoryImpl owner ─────────────────────────────

    @Test
    fun `SettingsRepositoryImpl is only constructed by the approved owner`() {
        // Exactly one production SettingsRepositoryImpl may exist so there is one Preferences
        // DataStore over the app_settings file (a second instance crashes at runtime). It is built
        // by SurveyingApp.setupSettings() and bridged through Hilt. Tests live outside this scanned
        // root (app/src/main/java) and may construct their own over isolated temp DataStores.
        val allow = setOf(
            // PERMANENT: the single approved construction site.
            "com/example/surveyingapp/SurveyingApp.kt",
            // PERMANENT: the class's own declaration (`class SettingsRepositoryImpl(...)`).
            "com/example/surveyingapp/data/settings/repository/SettingsRepositoryImpl.kt",
        )
        enforce(
            rule = "SettingsRepositoryImpl(...) constructed outside SurveyingApp.setupSettings()",
            preferred = "Inject SettingsRepository (or a focused settings interface) via Hilt; it bridges to the one singleton",
            violations = scan(Regex("""SettingsRepositoryImpl\("""), allow),
        )
    }

    // ── Rule 9: single settings Preferences DataStore ───────────────────────────

    @Test
    fun `only one settings Preferences DataStore delegate exists`() {
        // Guards against a second production settings DataStore. The one `app_settings` store is
        // declared by the `Context.appDataStore` delegate in Preferences.kt; no other production
        // file should declare a `preferencesDataStore(...)` delegate for settings.
        val allow = setOf(
            // PERMANENT: the single production settings DataStore delegate.
            "com/example/surveyingapp/data/settings/datastore/Preferences.kt",
        )
        enforce(
            rule = "Additional preferencesDataStore(...) delegate in production code",
            preferred = "Reuse the single SettingsLocalDataSource/appDataStore; create isolated DataStores only in tests",
            violations = scan(Regex("""preferencesDataStore\("""), allow),
        )
    }
}
