package app.surrealar.gnss.source

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Plain-JVM (no Robolectric) tests for [GnssSourceCoordinator]: startup restore for every saved
 * source, read failures, the abort when the source changes mid-restore, replay start/end, and the
 * "reconnect only when the external target changed" rule (no double start of the same receiver).
 */
class GnssSourceCoordinatorJvmTest {

    private val providerFlow = MutableStateFlow(ProviderChoice.INTERNAL)
    private val sourceSettings = SourceSettings(providerFlow, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val reconnects = mutableListOf<String>()
    private val reconnect = object : ExternalReconnectable {
        override fun reconnectNow(reason: String) { reconnects += reason }
    }

    private fun repo(
        source: Flow<LocationSourceType> = flowOf(LocationSourceType.INTERNAL),
        host: Flow<String?> = flowOf(null),
        port: Flow<Int?> = flowOf(null),
    ): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.locationSource).thenReturn(source)
        Mockito.`when`(it.externalTcpHost).thenReturn(host)
        Mockito.`when`(it.externalTcpPort).thenReturn(port)
    }

    private fun coordinator(repo: SettingsRepository = repo()) = GnssSourceCoordinator(sourceSettings, repo, reconnect)

    private fun failing(): Flow<Nothing> = flow { throw java.io.IOException("datastore") }

    // ── restore ──────────────────────────────────────────────────────────────

    @Test fun restore_savedInternal_activatesInternal() = runBlocking {
        providerFlow.value = ProviderChoice.EXTERNAL_TCP
        coordinator().restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun restore_savedSimulator_activatesInternal() = runBlocking {
        coordinator(repo(source = flowOf(LocationSourceType.SIMULATOR), host = flowOf("h"), port = flowOf(9000)))
            .restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun restore_savedExternalWithTarget_activatesExternal() = runBlocking {
        coordinator(repo(source = flowOf(LocationSourceType.EXTERNAL), host = flowOf("192.168.42.1"), port = flowOf(9001)))
            .restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.EXTERNAL_TCP, providerFlow.value)
        assertTrue(reconnects.isEmpty())
    }

    @Test fun restore_savedExternalWithoutHost_staysInternal() = runBlocking {
        coordinator(repo(source = flowOf(LocationSourceType.EXTERNAL), host = flowOf(null), port = flowOf(9001)))
            .restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun restore_hostAndPortReadFailures_fallBackToInternal() = runBlocking {
        coordinator(repo(source = flowOf(LocationSourceType.EXTERNAL), host = failing(), port = failing()))
            .restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun restore_sourceReadFailure_defaultsToInternal() = runBlocking {
        providerFlow.value = ProviderChoice.EXTERNAL_TCP
        coordinator(repo(source = failing(), host = flowOf("h"), port = flowOf(9000))).restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun restore_sourceChangedBeforeActivation_aborts() = runBlocking {
        var reads = 0
        val source = flow {
            emit(if (reads++ == 0) LocationSourceType.EXTERNAL else LocationSourceType.INTERNAL)
        }
        coordinator(repo(source = source, host = flowOf("h"), port = flowOf(9000))).restoreSavedSourceOnStartup()
        assertEquals("restore must not override a switch made during startup", ProviderChoice.INTERNAL, providerFlow.value)
        assertEquals(2, reads)
    }

    @Test fun restore_recheckReadFailure_aborts() = runBlocking {
        var reads = 0
        val source = flow {
            if (reads++ > 0) throw java.io.IOException("re-check")
            emit(LocationSourceType.EXTERNAL)
        }
        coordinator(repo(source = source, host = flowOf("h"), port = flowOf(9000))).restoreSavedSourceOnStartup()
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    // ── activate ─────────────────────────────────────────────────────────────

    @Test fun activateExternal_twiceWithSameTarget_doesNotReconnect() {
        val c = coordinator()
        c.activateExternalTcpProvider("h", 9000, "settings")
        c.activateExternalTcpProvider("h", 9000, "settings again")
        assertEquals(ProviderChoice.EXTERNAL_TCP, providerFlow.value)
        assertTrue(reconnects.isEmpty())
    }

    @Test fun activateExternal_newHostOrPortWhileActive_reconnectsOncePerChange() {
        val c = coordinator()
        c.activateExternalTcpProvider("h", 9000, "a")
        c.activateExternalTcpProvider("h", 9001, "b")
        c.activateExternalTcpProvider("h", 9001, "b again")
        c.activateExternalTcpProvider("other", 9001, "c")
        assertEquals(listOf("receiver changed to h:9001", "receiver changed to other:9001"), reconnects)
    }

    @Test fun activateExternal_whenAlreadyExternalButNotActivatedThisProcess_reconnects() {
        providerFlow.value = ProviderChoice.EXTERNAL_TCP
        coordinator().activateExternalTcpProvider("h", 9000, "first")
        assertEquals(listOf("receiver changed to h:9000"), reconnects)
    }

    @Test fun activateInternal_fromExternal_switches() {
        val c = coordinator()
        c.activateExternalTcpProvider("h", 9000, "a")
        c.activateInternalProvider("user")
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
        // Back to external with the same target: a provider flip, not a reconnect.
        c.activateExternalTcpProvider("h", 9000, "a")
        assertEquals(ProviderChoice.EXTERNAL_TCP, providerFlow.value)
        assertTrue(reconnects.isEmpty())
    }

    // ── replay ───────────────────────────────────────────────────────────────

    @Test fun replay_startReturnsPrevious_andEndRestoresIt() {
        providerFlow.value = ProviderChoice.EXTERNAL_TCP
        val c = coordinator()
        val previous = c.startReplay("demo")
        assertEquals(ProviderChoice.EXTERNAL_TCP, previous)
        assertEquals(ProviderChoice.REPLAY, providerFlow.value)
        c.endReplay(previous, "done")
        assertEquals(ProviderChoice.EXTERNAL_TCP, providerFlow.value)
    }

    @Test fun replay_endWithPreviousReplay_fallsBackToInternal() {
        val c = coordinator()
        c.startReplay("one")
        val previous = c.startReplay("twice")
        assertEquals(ProviderChoice.REPLAY, previous)
        c.endReplay(previous, "done")
        assertEquals(ProviderChoice.INTERNAL, providerFlow.value)
    }

    @Test fun replay_endAfterUserSwitchedAway_leavesTheNewProvider() {
        val c = coordinator()
        val previous = c.startReplay("demo")
        c.activateExternalTcpProvider("h", 9000, "user picked external")
        c.endReplay(previous, "done")
        assertEquals(ProviderChoice.EXTERNAL_TCP, providerFlow.value)
    }
}
