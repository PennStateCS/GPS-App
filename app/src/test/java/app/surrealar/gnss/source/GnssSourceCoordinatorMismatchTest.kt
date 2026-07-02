package app.surrealar.gnss.source

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.capture.GnssCaptureSettings
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.AppearanceSettings
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.DeveloperSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coordinator-layer tests for selected-vs-active source behavior and config-change fallback, using a
 * MUTABLE settings repository so config can change between startup restores. Complements the pure
 * [SourceRoutingDecisionsTest] and the existing [GnssSourceCoordinatorTest]. Robolectric is only for
 * `android.util.Log`; the routing logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GnssSourceCoordinatorMismatchTest {

    /** Settings repository whose source/host/port can be changed between restores. */
    private class MutableSettingsRepo(
        source: LocationSourceType, host: String?, port: Int?
    ) : SettingsRepository {
        val sourceFlow = MutableStateFlow(source)
        val hostFlow = MutableStateFlow(host)
        val portFlow = MutableStateFlow(port)
        override val locationSource: Flow<LocationSourceType> = sourceFlow
        override val externalTcpHost: Flow<String?> = hostFlow
        override val externalTcpPort: Flow<Int?> = portFlow

        override val externalConnType: Flow<ExternalConnectionType> = emptyFlow()
        override val externalBtAddress: Flow<String?> = emptyFlow()
        override val externalTcpName: Flow<String?> = emptyFlow()
        override val externalReceiverProfile: Flow<app.surrealar.settings.model.ExternalReceiverProfile> = emptyFlow()
        override val externalReceiverSettings: Flow<app.surrealar.settings.model.ExternalReceiverSettings> = emptyFlow()
        override val gnssCaptureSettings: Flow<GnssCaptureSettings> = emptyFlow()
        override val arDisplaySettings: Flow<ArDisplaySettings> = emptyFlow()
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = emptyFlow()
        override val mockLocationEnabled: Flow<Boolean> = emptyFlow()
        override val arVisibilityMode: Flow<String> = emptyFlow()
        override val arVisibilityCustomized: Flow<Boolean> = emptyFlow()
        override val arVisibleIds: Flow<Set<String>> = emptyFlow()
        override val gnssReceiverSettings: Flow<GnssReceiverSettings> = emptyFlow()
        override val developerSettings: Flow<DeveloperSettings> = emptyFlow()
        override val appearanceSettings: Flow<AppearanceSettings> = emptyFlow()
        override val stakeoutSettings: Flow<app.surrealar.settings.model.StakeoutSettings> = emptyFlow()
        override val mapSettings: Flow<app.surrealar.ui.rendermap.MapSettings> = emptyFlow()

        override suspend fun setExternalReceiverProfile(profile: app.surrealar.settings.model.ExternalReceiverProfile) {}
        override suspend fun setExternalReceiverSettings(settings: app.surrealar.settings.model.ExternalReceiverSettings) {}
        override suspend fun setLocationSource(v: LocationSourceType) {}
        override suspend fun setExternalTcp(host: String, port: Int, name: String) {}
        override suspend fun setMockLocationEnabled(enabled: Boolean) {}
        override suspend fun setArVisibilityMode(mode: String) {}
        override suspend fun setArVisibilityCustomized(customized: Boolean) {}
        override suspend fun setArVisibleIds(ids: Set<String>) {}
        override suspend fun setExternalConnType(v: ExternalConnectionType) {}
        override suspend fun clearExternalTcp() {}
        override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {}
        override suspend fun setArDisplaySettings(settings: ArDisplaySettings) {}
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) {}
        override suspend fun setGnssReceiverSettings(settings: GnssReceiverSettings) {}
        override suspend fun setDeveloperSettings(settings: DeveloperSettings) {}
        override suspend fun setAppearanceSettings(settings: AppearanceSettings) {}
        override suspend fun setStakeoutSettings(settings: app.surrealar.settings.model.StakeoutSettings) {}
        override suspend fun setMapSettings(settings: app.surrealar.ui.rendermap.MapSettings) {}
    }

    private fun setup(
        source: LocationSourceType, host: String? = null, port: Int? = null,
        initialProvider: ProviderChoice = ProviderChoice.INTERNAL
    ): Pair<GnssSourceCoordinator, Pair<SourceSettings, MutableSettingsRepo>> {
        val ss = SourceSettings(MutableStateFlow(initialProvider), MutableStateFlow(emptyList()), MutableStateFlow(null))
        val repo = MutableSettingsRepo(source, host, port)
        return GnssSourceCoordinator(ss, repo) to (ss to repo)
    }

    // ── Selected vs active reported distinctly + no masquerade ──────────────────────────────────

    @Test fun `external selected but unconfigured stays internal and reports distinct selected vs active`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.EXTERNAL, host = null, port = null)
        val (ss, repo) = deps
        c.restoreSavedSourceOnStartup()

        // selected and active are reported distinctly — the classic mismatch state.
        assertEquals(LocationSourceType.EXTERNAL, repo.sourceFlow.value)        // selected source
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)          // active provider
        // The app must NOT pretend external is active when it cannot be.
        assertEquals(
            SourceRoutingDecisions.MismatchKind.EXTERNAL_SELECTED_INTERNAL_ACTIVE,
            SourceRoutingDecisions.classifySelectedActiveMismatch(
                repo.sourceFlow.value, ss.activeProvider.value, externalConfigured = false
            ).kind
        )
    }

    // ── Config changes between restores ──────────────────────────────────────────────────────────

    @Test fun `adding valid external config makes external available on next restore`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.INTERNAL)
        val (ss, repo) = deps
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)

        // User selects external and configures host/port, then restore runs again.
        repo.sourceFlow.value = LocationSourceType.EXTERNAL
        repo.hostFlow.value = "192.168.2.174"
        repo.portFlow.value = 9001
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, ss.activeProvider.value)
    }

    @Test fun `clearing external config falls back to internal on next restore`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.EXTERNAL, host = "10.0.0.5", port = 9000)
        val (ss, repo) = deps
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, ss.activeProvider.value)

        // Config cleared while still selected External → fall back to internal, no masquerade.
        repo.hostFlow.value = null
        repo.portFlow.value = null
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)
    }

    @Test fun `changing host and port keeps external active (re-activated)`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.EXTERNAL, host = "10.0.0.5", port = 9000)
        val (ss, repo) = deps
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, ss.activeProvider.value)

        repo.hostFlow.value = "10.0.0.9"
        repo.portFlow.value = 2101
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, ss.activeProvider.value)
    }

    // ── Switching decisions ──────────────────────────────────────────────────────────────────────

    @Test fun `re-selecting the current source is idempotent`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.INTERNAL)
        val (ss, _) = deps
        c.restoreSavedSourceOnStartup()
        c.restoreSavedSourceOnStartup()   // same selection again
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)
    }

    @Test fun `external selected without config never activates external`() = runBlocking {
        val (c, deps) = setup(LocationSourceType.EXTERNAL, host = "192.168.2.174", port = null)
        val (ss, _) = deps
        c.restoreSavedSourceOnStartup()
        // Partial config (host but no port) → must NOT activate external.
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)
    }
}
