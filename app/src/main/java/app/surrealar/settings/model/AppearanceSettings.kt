package app.surrealar.settings.model

/**
 * App theme selection, persisted in DataStore by its stable [prefKey] (not the constant name).
 * [fromPrefKey] also accepts the legacy uppercase names older installs persisted.
 */
enum class AppThemeMode(val prefKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        val DEFAULT = SYSTEM

        fun fromPrefKey(value: String?): AppThemeMode =
            entries.firstOrNull { it.prefKey.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: DEFAULT
    }
}

/**
 * Appearance/display preferences: theme, the live GNSS status bar, and screen power behavior
 * (keep-awake, max brightness). Pure configuration persisted in DataStore; none of these affect
 * captured survey data.
 */
data class AppearanceSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val showLiveGnssStatusBar: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val maxBrightnessWhileOpen: Boolean = false
)
