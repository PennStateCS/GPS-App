package com.example.surveyingapp.gnss.source

import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.settings.GnssReceiverSettings
import com.example.surveyingapp.settings.model.AppearanceSettings
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.DeveloperSettings
import com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice
import com.example.surveyingapp.gnss.source.ConnectionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GnssSourceCoordinator]. These guard the saved-External cold-start path (the bug
 * where the toolbar showed RS2+ but live data didn't restore until rotation) and confirm the
 * `activate…Provider` methods are provider-activation only — they do NOT persist settings.
 *
 * Robolectric is used only so `android.util.Log` (hit by the coordinator's diagnostic logs) works;
 * the coordinator logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GnssSourceCoordinatorTest {

    /** Fake that records writes so tests can assert the coordinator does NOT persist anything. */
    private class FakeSettingsRepository(
        source: LocationSourceType,
        host: String?,
        port: Int?
    ) : SettingsRepository {
        var setLocationSourceCalls = 0
        var setExternalTcpCalls = 0
        var setMockLocationCalls = 0

        override val locationSource: Flow<LocationSourceType> = flowOf(source)
        override val externalTcpHost: Flow<String?> = flowOf(host)
        override val externalTcpPort: Flow<Int?> = flowOf(port)

        // Unused by the coordinator — empty flows / no-op setters.
        override val externalConnType: Flow<ExternalConnectionType> = emptyFlow()
        override val externalBtAddress: Flow<String?> = emptyFlow()
        override val externalTcpName: Flow<String?> = emptyFlow()
        override val gnssCaptureSettings: Flow<GnssCaptureSettings> = emptyFlow()
        override val arDisplaySettings: Flow<ArDisplaySettings> = emptyFlow()
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = emptyFlow()
        override val mockLocationEnabled: Flow<Boolean> = emptyFlow()
        override val gnssReceiverSettings: Flow<GnssReceiverSettings> = emptyFlow()
        override val developerSettings: Flow<DeveloperSettings> = emptyFlow()
        override val appearanceSettings: Flow<AppearanceSettings> = emptyFlow()

        override suspend fun setLocationSource(v: LocationSourceType) { setLocationSourceCalls++ }
        override suspend fun setExternalTcp(host: String, port: Int, name: String) { setExternalTcpCalls++ }
        override suspend fun setMockLocationEnabled(enabled: Boolean) { setMockLocationCalls++ }
        override suspend fun setExternalConnType(v: ExternalConnectionType) {}
        override suspend fun clearExternalTcp() {}
        override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {}
        override suspend fun setArDisplaySettings(settings: ArDisplaySettings) {}
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) {}
        override suspend fun setGnssReceiverSettings(settings: GnssReceiverSettings) {}
        override suspend fun setDeveloperSettings(settings: DeveloperSettings) {}
        override suspend fun setAppearanceSettings(settings: AppearanceSettings) {}
    }

    private fun sourceSettings(initial: ProviderChoice) = SourceSettings(
        _activeProvider = MutableStateFlow(initial),
        connectionProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList()),
        activeProfileId = MutableStateFlow<String?>(null)
    )

    private fun coordinator(
        source: LocationSourceType = LocationSourceType.INTERNAL,
        host: String? = null,
        port: Int? = null,
        initialProvider: ProviderChoice = ProviderChoice.INTERNAL
    ): Pair<GnssSourceCoordinator, Pair<SourceSettings, FakeSettingsRepository>> {
        val ss = sourceSettings(initialProvider)
        val repo = FakeSettingsRepository(source, host, port)
        return GnssSourceCoordinator(ss, repo) to (ss to repo)
    }

    // ── Task 3: provider activation methods ──────────────────────────────────────

    @Test fun `activateInternalProvider sets INTERNAL and persists nothing`() {
        val (c, deps) = coordinator(initialProvider = ProviderChoice.EXTERNAL_TCP)
        val (ss, repo) = deps
        c.activateInternalProvider("test")
        assertEquals(ProviderChoice.INTERNAL, ss.activeProvider.value)
        assertEquals(0, repo.setLocationSourceCalls)
        assertEquals(0, repo.setExternalTcpCalls)
        assertEquals(0, repo.setMockLocationCalls)
    }

    @Test fun `activateExternalTcpProvider sets EXTERNAL_TCP and persists nothing`() {
        val (c, deps) = coordinator(initialProvider = ProviderChoice.INTERNAL)
        val (ss, repo) = deps
        c.activateExternalTcpProvider("192.168.2.174", 9001, "test")
        assertEquals(ProviderChoice.EXTERNAL_TCP, ss.activeProvider.value)
        assertEquals("does not persist host/port", 0, repo.setExternalTcpCalls)
        assertEquals("does not persist selected source", 0, repo.setLocationSourceCalls)
    }

    // ── Task 4: startup restore ──────────────────────────────────────────────────

    @Test fun `restore - saved Internal activates Internal`() = runBlocking {
        val (c, deps) = coordinator(source = LocationSourceType.INTERNAL, initialProvider = ProviderChoice.EXTERNAL_TCP)
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, deps.first.activeProvider.value)
    }

    @Test fun `restore - saved External with host activates External`() = runBlocking {
        val (c, deps) = coordinator(
            source = LocationSourceType.EXTERNAL, host = "192.168.2.174", port = 9001,
            initialProvider = ProviderChoice.INTERNAL
        )
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, deps.first.activeProvider.value)
        // restore must not persist — it only activates the live provider
        assertEquals(0, deps.second.setLocationSourceCalls)
        assertEquals(0, deps.second.setExternalTcpCalls)
    }

    @Test fun `restore - saved External with no host stays Internal`() = runBlocking {
        val (c, deps) = coordinator(
            source = LocationSourceType.EXTERNAL, host = null, port = null,
            initialProvider = ProviderChoice.INTERNAL
        )
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, deps.first.activeProvider.value)
    }

    @Test fun `restore - saved External with blank host stays Internal`() = runBlocking {
        val (c, deps) = coordinator(
            source = LocationSourceType.EXTERNAL, host = "   ", port = 9001,
            initialProvider = ProviderChoice.INTERNAL
        )
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, deps.first.activeProvider.value)
    }

    @Test fun `restore - saved External with null port stays Internal`() = runBlocking {
        val (c, deps) = coordinator(
            source = LocationSourceType.EXTERNAL, host = "192.168.2.174", port = null,
            initialProvider = ProviderChoice.INTERNAL
        )
        c.restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, deps.first.activeProvider.value)
    }
}
