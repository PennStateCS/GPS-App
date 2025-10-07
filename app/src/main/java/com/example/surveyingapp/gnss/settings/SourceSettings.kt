package com.example.surveyingapp.gnss.settings

import kotlinx.coroutines.flow.*

/** Default NMEA/TCP port for many receivers. */
private const val DEFAULT_NMEA_PORT = 9000

data class ConnectionProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_NMEA_PORT
) {
    init { require(host.isNotBlank()) { "host must not be blank" } }
}

/** Structured connection target (avoid Pair). */
data class ConnectionInfo(val host: String, val port: Int)

/**
 * Minimal source-selection surface.
 *
 * NOTE:
 * - We keep a private MutableStateFlow for the provider so you can change it via setActiveProvider().
 * - All other inputs can be StateFlow from your SettingsRepository/DataStore.
 */
class SourceSettings(
    private val _activeProvider: MutableStateFlow<ProviderChoice>,

    @Deprecated("Use connectionProfiles/activeProfileId instead")
    @Suppress("UNUSED_PARAMETER")
    val rs2Host: StateFlow<String?>,

    val connectionProfiles: StateFlow<List<ConnectionProfile>>,
    val activeProfileId: StateFlow<String?> // selected profile id
) {
    /** Read-only view for consumers. */
    val activeProvider: StateFlow<ProviderChoice> get() = _activeProvider
    fun setActiveProvider(choice: ProviderChoice) {
        if (_activeProvider.value != choice) _activeProvider.value = choice
    }

    /** GNSS provider selection (internal device vs external receiver). */
    enum class ProviderChoice { INTERNAL, RS2_EXTERNAL }

    /** Currently selected profile as a flow (null if none). */
    val activeProfileFlow: Flow<ConnectionProfile?> =
        combine(connectionProfiles, activeProfileId) { profiles, id ->
            id?.let { profiles.firstOrNull { it.id == id } }
        }.distinctUntilChanged()

    /** Synchronous helpers. */
    fun getActiveProfile(): ConnectionProfile? {
        val id = activeProfileId.value ?: return null
        return connectionProfiles.value.firstOrNull { it.id == id }
    }

    fun getConnectionInfo(): ConnectionInfo? =
        connectionInfoFor(getActiveProfile(), rs2Host.value)

    /** Shared builder for sync + flow logic. */
    private fun connectionInfoFor(profile: ConnectionProfile?, legacy: String?): ConnectionInfo? = when {
        profile != null        -> ConnectionInfo(profile.host, profile.port.validPortOrDefault(DEFAULT_NMEA_PORT))
        !legacy.isNullOrBlank() -> ConnectionInfo(legacy, DEFAULT_NMEA_PORT)
        else                   -> null
    }

    /**
     * Emits whenever profile list, selected profile, or legacy host changes.
     * ExternalAdapter can collect this to (re)connect automatically.
     */
    val connectionInfoFlow: Flow<ConnectionInfo?> =
        combine(activeProfileFlow, rs2Host) { profile, legacy ->
            connectionInfoFor(profile, legacy)
        }.distinctUntilChanged()

    /**
     * Returns connection info from the currently active profile.
     * This is the preferred way to get connection details.
     */
    fun resolveActiveConnection(): ConnectionInfo? {
        val profile = getActiveProfile()
        return profile?.let { ConnectionInfo(it.host, it.port) }
    }
}

/** Port guard: keep within [1, 65535]; else return the provided default. */
private fun Int.validPortOrDefault(def: Int): Int =
    if (this in 1..65535) this else def
