package app.surrealar.settings.model

/** Developer-tools preference: when `developerToolsEnabled` is set, debug-only UI/diagnostics are exposed. */
data class DeveloperSettings(
    val developerToolsEnabled: Boolean = false
)
