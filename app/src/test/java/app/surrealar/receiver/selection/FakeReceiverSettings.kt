package app.surrealar.receiver.selection

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.RecentReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito

/**
 * The receiver-selection slice of [SettingsRepository] as live flows, with every write logged in order ([writes]);
 * everything else is delegated to a Mockito mock (unused here).
 */
internal class FakeReceiverSettings(
    private val base: SettingsRepository = Mockito.mock(SettingsRepository::class.java),
) : SettingsRepository by base {
    val source = MutableStateFlow(LocationSourceType.INTERNAL)
    val connType = MutableStateFlow(ExternalConnectionType.TCP)
    val host = MutableStateFlow<String?>(null)
    val port = MutableStateFlow<Int?>(null)
    val name = MutableStateFlow<String?>(null)
    val profile = MutableStateFlow(ExternalReceiverProfile.DEFAULT)
    val origin = MutableStateFlow(ProfileOrigin.DEFAULT)
    val recent = MutableStateFlow<List<RecentReceiver>>(emptyList())
    val mock = MutableStateFlow(false)

    /** Every write, in order, e.g. "source=EXTERNAL" (recent-list writes are kept out: see [recentWrites]). */
    val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
    var recentWrites = 0
    /** Called before each write is applied (e.g. to look at the active provider at that moment). */
    var onWrite: (String) -> Unit = {}

    private fun write(w: String) { onWrite(w); writes += w }

    override val locationSource: Flow<LocationSourceType> get() = source
    override suspend fun setLocationSource(v: LocationSourceType) { write("source=$v"); source.value = v }
    override val externalConnType: Flow<ExternalConnectionType> get() = connType
    override suspend fun setExternalConnType(v: ExternalConnectionType) { write("connType=$v"); connType.value = v }
    override val externalTcpHost: Flow<String?> get() = host
    override val externalTcpPort: Flow<Int?> get() = port
    override val externalTcpName: Flow<String?> get() = name
    override suspend fun setExternalTcp(host: String, port: Int, name: String) {
        write("tcp=$host:$port"); this.host.value = host; this.port.value = port
        if (name.isNotBlank()) this.name.value = name
    }
    /** [saveExternalTcpReceiver] transactions applied. */
    var receiverCommits = 0
    /** Makes the next [saveExternalTcpReceiver] fail (like a DataStore IOException): nothing is applied. */
    var failNextCommit = false

    /**
     * One transaction, like SettingsRepositoryImpl: logs the same entries as the separate writes (connType, tcp,
     * profile, source) and applies them all, or nothing when [failNextCommit].
     */
    override suspend fun saveExternalTcpReceiver(
        host: String, port: Int, name: String, profile: ExternalReceiverProfile?, origin: ProfileOrigin,
        source: LocationSourceType,
    ) {
        commitGate?.let { commitGate = null; it.await() }
        if (failNextCommit) {
            failNextCommit = false
            throw java.io.IOException("simulated DataStore write failure")
        }
        listOfNotNull("connType=TCP", "tcp=$host:$port", profile?.let { "profile=${it.prefKey}/${origin.prefKey}" }, "source=$source")
            .forEach(::write)
        val sameEndpoint = this.host.value?.trim().equals(host.trim(), ignoreCase = true) && this.port.value == port
        connType.value = ExternalConnectionType.TCP
        this.host.value = host
        this.port.value = port
        if (name.isNotBlank()) this.name.value = name else if (!sameEndpoint) this.name.value = ""
        if (profile != null) { this.profile.value = profile; this.origin.value = origin }
        this.source.value = source
        receiverCommits++
    }

    /**
     * When set, the next [saveExternalTcpReceiver] suspends on it before applying anything (a slow DataStore write):
     * lets a test interleave other calls at that exact point.
     */
    var commitGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** As [commitGate], for the next [saveInternalSource]. */
    var internalGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** Makes the next N [saveInternalSource] calls fail (nothing applied). */
    var failInternalSaves = 0
    /** [saveInternalSource] transactions applied. */
    var internalCommits = 0

    /** One transaction, like SettingsRepositoryImpl: logs mock=false, source=INTERNAL [, clearTcp], or nothing on failure. */
    override suspend fun saveInternalSource(clearReceiver: Boolean) {
        internalGate?.let { internalGate = null; it.await() }
        if (failInternalSaves > 0) {
            failInternalSaves--
            throw java.io.IOException("simulated DataStore write failure")
        }
        listOfNotNull("mock=false", "source=INTERNAL", "clearTcp".takeIf { clearReceiver }).forEach(::write)
        mock.value = false
        source.value = LocationSourceType.INTERNAL
        if (clearReceiver) { host.value = null; port.value = null; name.value = null }
        internalCommits++
    }

    override suspend fun clearExternalTcp() { write("clearTcp"); host.value = null; port.value = null; name.value = null }
    override val externalReceiverProfile: Flow<ExternalReceiverProfile> get() = profile
    override suspend fun setExternalReceiverProfile(profile: ExternalReceiverProfile) =
        setExternalReceiverProfile(profile, ProfileOrigin.USER)
    override val externalReceiverProfileOrigin: Flow<ProfileOrigin> get() = origin
    override suspend fun setExternalReceiverProfile(profile: ExternalReceiverProfile, origin: ProfileOrigin) {
        write("profile=${profile.prefKey}/${origin.prefKey}"); this.profile.value = profile; this.origin.value = origin
    }
    override val externalRecentReceivers: Flow<List<RecentReceiver>> get() = recent
    override suspend fun setExternalRecentReceivers(list: List<RecentReceiver>) { recentWrites++; recent.value = list }
    override val mockLocationEnabled: Flow<Boolean> get() = mock
    override suspend fun setMockLocationEnabled(enabled: Boolean) { write("mock=$enabled"); mock.value = enabled }
}
