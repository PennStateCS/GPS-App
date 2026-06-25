package com.example.surveyingapp.gnss.source

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice
import com.example.surveyingapp.util.DiagnosticsLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes **live provider activation** for the GNSS subsystem.
 *
 * ### Selected source vs active provider
 * - **Selected source** = the user's persisted choice (`SettingsRepository.locationSource`,
 *   `LocationSourceType` Internal/External/Simulator). Owned by [SettingsRepository] / DataStore.
 * - **Active provider** = what is actually streaming fixes right now
 *   ([SourceSettings.activeProvider], `ProviderChoice` INTERNAL / EXTERNAL_TCP). The only mutator is
 *   [SourceSettings.setActiveProvider]; this coordinator is the single place that calls it.
 *
 * ### What this coordinator owns
 * Flipping the **active provider** (and emitting the diagnostic transition logs). When it activates
 * EXTERNAL_TCP, `FixSwitchboard` binds the external adapter, which opens the TCP NMEA connection,
 * waits for first data, and retries with backoff — that adapter is the connection validator, so the
 * coordinator does not duplicate any probe. No stale/wrong-provider data can surface while it
 * connects: the internal adapter is not bound while External is active and `currentFix` stays null
 * until the receiver streams, so the toolbar shows a clean waiting state.
 *
 * ### What this coordinator does NOT do (yet)
 * It does **not** persist the selected source, disable mock-location publishing, validate the
 * receiver, or touch any UI state. Those remain in `SettingsFragment` (and DataStore). The
 * `activate…Provider` method names reflect this: they ONLY change the active provider. Do not rename
 * them back to full-switch verbs (`switchTo…`, `connect…`) unless they actually perform the full
 * sequence below.
 *
 * ### External TCP activation order (must be preserved by callers)
 * 1. save connection type (`setExternalConnType`)
 * 2. save host/port (`setExternalTcp`)
 * 3. save selected source = External (`setLocationSource`)
 * 4. validate the receiver if required (probe / let the adapter connect)
 * 5. **activate** the External TCP provider ([activateExternalTcpProvider]) — only after 1–4
 *
 * [restoreSavedSourceOnStartup] is the one method that orchestrates a small read-decide-activate
 * flow; the `activate…Provider` methods are pure one-line provider flips.
 */
@Singleton
class GnssSourceCoordinator @Inject constructor(
    private val sourceSettings: SourceSettings,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Called once per process at app launch. Restores the live provider from the persisted selected
     * source WITHOUT requiring the user to open Settings or rotate the device. For saved External it
     * re-activates EXTERNAL_TCP (the adapter reconnects); if there is no saved host/port, or the user
     * switched away during the startup window, it stays/returns to Internal.
     */
    suspend fun restoreSavedSourceOnStartup() {
        val selected = runCatching { settingsRepository.locationSource.first() }
            .getOrDefault(LocationSourceType.INTERNAL)
        val active = sourceSettings.activeProvider.value
        DiagnosticsLogger.i("GNSS", "Startup selectedSource=$selected activeProvider=$active")

        if (selected != LocationSourceType.EXTERNAL) {
            // Internal (or simulator): keep the safe default Internal provider.
            activateInternalProvider(reason = "startup-restore-internal")
            return
        }

        val host = runCatching { settingsRepository.externalTcpHost.first() }.getOrNull()
        val port = runCatching { settingsRepository.externalTcpPort.first() }.getOrNull()
        if (host.isNullOrBlank() || port == null) {
            DiagnosticsLogger.w("GNSS",
                "Startup External restore: no saved host/port — staying Internal until configured")
            activateInternalProvider(reason = "startup-restore-no-host")
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
        activateExternalTcpProvider(host, port, reason = "startup-restore")
    }

    /**
     * Makes Internal the **active provider** (`setActiveProvider(INTERNAL)`) and logs the reason.
     * Does NOT persist the selected source or disable mock publishing — callers own that.
     */
    fun activateInternalProvider(reason: String) {
        DiagnosticsLogger.i("GNSS", "activateInternalProvider ($reason)")
        sourceSettings.setActiveProvider(ProviderChoice.INTERNAL)
    }

    /**
     * Makes External TCP the **active provider** (`setActiveProvider(EXTERNAL_TCP)`) and logs the
     * reason. [host]/[port] are for diagnostics only — the actual connection params are read by
     * `TcpNmeaSource` from [SettingsRepository], which the caller must have already persisted (see
     * the External TCP activation order in the class KDoc). Connection validation/retry is delegated
     * to the external adapter the switchboard binds.
     */
    fun activateExternalTcpProvider(host: String, port: Int, reason: String) {
        DiagnosticsLogger.i("Receiver", "Activate external provider for $host:$port ($reason)")
        sourceSettings.setActiveProvider(ProviderChoice.EXTERNAL_TCP)
        DiagnosticsLogger.i("GNSS", "External provider activated from $reason; EXTERNAL_TCP set (validation delegated to external adapter)")
    }
}
