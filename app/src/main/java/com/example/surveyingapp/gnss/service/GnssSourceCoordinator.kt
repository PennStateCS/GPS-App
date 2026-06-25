package com.example.surveyingapp.gnss.service

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice
import com.example.surveyingapp.util.DiagnosticsLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single, non-UI entry point for changing the live GNSS provider. Both app startup and Settings
 * can call this so the activation rules live in one place instead of being copied into fragments.
 *
 * Design note on "validation": activating [ProviderChoice.EXTERNAL_TCP] makes [FixSwitchboard] bind
 * the external adapter, which opens the TCP NMEA connection, waits for first data, and retries with
 * backoff. That adapter IS the connection validator used by the live data pipeline, so this
 * coordinator does not duplicate SettingsFragment's probe logic — it just flips the provider and
 * lets the adapter connect. No stale or wrong-provider data can surface while it connects: the
 * internal adapter is not bound while External is active, and currentFix stays null until the
 * receiver actually streams, so the toolbar shows a clean waiting state.
 */
@Singleton
class GnssSourceCoordinator @Inject constructor(
    private val sourceSettings: SourceSettings,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Called once per process at app launch. Restores the live provider from the persisted
     * selected source WITHOUT requiring the user to open Settings or rotate the device.
     */
    suspend fun restoreSavedSourceOnStartup() {
        val selected = runCatching { settingsRepository.locationSource.first() }
            .getOrDefault(LocationSourceType.INTERNAL)
        val active = sourceSettings.activeProvider.value
        DiagnosticsLogger.i("GNSS", "Startup selectedSource=$selected activeProvider=$active")

        if (selected != LocationSourceType.EXTERNAL) {
            // Internal (or simulator): keep the safe default Internal provider.
            switchToInternal()
            return
        }

        val host = runCatching { settingsRepository.externalTcpHost.first() }.getOrNull()
        val port = runCatching { settingsRepository.externalTcpPort.first() }.getOrNull()
        if (host.isNullOrBlank() || port == null) {
            DiagnosticsLogger.w("GNSS",
                "Startup External restore: no saved host/port — staying Internal until configured")
            switchToInternal()
            return
        }

        DiagnosticsLogger.i("GNSS",
            "Startup selectedSource=EXTERNAL activeProvider=$active; attempting saved external reconnect")

        // Re-check the selected source immediately before flipping the live provider. If the user
        // switched to Internal during the (sub-second) startup window, abort so this restore can't
        // override their choice and activate External afterwards.
        val stillExternal = runCatching { settingsRepository.locationSource.first() }
            .getOrDefault(LocationSourceType.INTERNAL) == LocationSourceType.EXTERNAL
        if (!stillExternal) {
            DiagnosticsLogger.i("GNSS", "Startup external restore aborted — source changed before activation")
            return
        }
        connectExternalTcp(host, port, reason = "startup-restore")
    }

    /** Makes Internal the live provider. */
    fun switchToInternal() {
        sourceSettings.setActiveProvider(ProviderChoice.INTERNAL)
    }

    /**
     * Activates the external provider using the saved receiver settings. Connection validation and
     * retry are handled by the external adapter the switchboard binds (see class note). The toolbar
     * shows a waiting state until external fixes arrive; if the receiver is unreachable it stays in
     * the waiting/no-data state rather than silently falling back to Internal.
     */
    fun connectExternalTcp(host: String, port: Int, reason: String) {
        DiagnosticsLogger.i("Receiver", "Startup reconnect to $host:$port ($reason)")
        sourceSettings.setActiveProvider(ProviderChoice.EXTERNAL_TCP)
        DiagnosticsLogger.i("GNSS", "External provider activated from $reason; EXTERNAL_TCP set (validation delegated to external adapter)")
    }

    /** Reverts the live provider to Internal (e.g. user disconnects the receiver). */
    fun disconnectExternal() {
        sourceSettings.setActiveProvider(ProviderChoice.INTERNAL)
    }
}
