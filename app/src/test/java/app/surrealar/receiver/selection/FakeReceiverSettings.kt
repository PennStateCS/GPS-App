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
