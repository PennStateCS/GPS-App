package com.example.surveyingapp.gnss.source

import com.example.surveyingapp.util.DiagnosticsLogger
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
 * Manages GNSS provider selection and TCP connection profiles.
 *
 * Provider switching is reactive: update [_activeProvider] via [setActiveProvider] and
 * `FixSwitchboard` will automatically route to the corresponding `SourceAdapter`.
 *
 * **Profile Support (Future)**: Connection profiles allow multiple named TCP endpoints to be
 * stored and selected without restarting the app. The active profile is resolved lazily via
 * [getConnectionInfo] / [connectionInfoFlow].
 *
 * **Current Status**: Profiles are initialized empty and not yet populated from UI. TCP
 * connection parameters are read from `SettingsRepository` by `TcpNmeaSource`. Once profile
 * UI is implemented, `TcpNmeaSource` can switch to using [getConnectionInfo] instead.
 */
class SourceSettings(
    private val _activeProvider: MutableStateFlow<ProviderChoice>,
    val connectionProfiles: StateFlow<List<ConnectionProfile>>,
    val activeProfileId: StateFlow<String?>
) {
    /** Read-only view for consumers. */
    val activeProvider: StateFlow<ProviderChoice> get() = _activeProvider

    fun setActiveProvider(choice: ProviderChoice) {
        val prev = _activeProvider.value
        android.util.Log.d("SourceSettings", "setActiveProvider: $prev → $choice")
        if (prev != choice) DiagnosticsLogger.i("GNSS", "Source changed $prev → $choice")
        _activeProvider.value = choice
    }

    /**
     * GNSS provider selection.
     *
     * - [INTERNAL]     — Android device's built-in GNSS chip (via NMEA listener)
     * - [EXTERNAL_TCP] — External GNSS receiver connected over TCP/IP (e.g. RS2+, u-blox, Reach)
     *
     * The enum is transport-level, not device-specific, so adding a new provider only requires
     * a new `SourceAdapter` implementation and a new entry here.
     */
    enum class ProviderChoice { INTERNAL, EXTERNAL_TCP }

    /** Currently selected profile as a flow (null if none selected or list is empty). */
    val activeProfileFlow: Flow<ConnectionProfile?> =
        combine(connectionProfiles, activeProfileId) { profiles, id ->
            id?.let { profiles.firstOrNull { p -> p.id == id } }
        }.distinctUntilChanged()

    /** Synchronous helper — returns null when no profile is selected. */
    fun getActiveProfile(): ConnectionProfile? {
        val id = activeProfileId.value ?: return null
        return connectionProfiles.value.firstOrNull { it.id == id }
    }

    /** Returns [ConnectionInfo] for the currently active profile, or null if none is selected. */
    fun getConnectionInfo(): ConnectionInfo? = getActiveProfile()?.toConnectionInfo()

    /**
     * Emits whenever the active profile changes.
     * `ExternalAdapter` / `TcpNmeaSource` can collect this to (re)connect automatically.
     */
    val connectionInfoFlow: Flow<ConnectionInfo?> =
        activeProfileFlow
            .map { it?.toConnectionInfo() }
            .distinctUntilChanged()

    private fun ConnectionProfile.toConnectionInfo() =
        ConnectionInfo(host, port.validPortOrDefault(DEFAULT_NMEA_PORT))
}

/** Port guard: keep within [1, 65535]; else return the provided default. */
private fun Int.validPortOrDefault(def: Int): Int =
    if (this in 1..65535) this else def
