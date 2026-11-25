package app.surrealar.ui.settings.receiver

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ReceiverIdentity
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Constellation
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SatInfo
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import java.time.Instant

/** Shared receiver-UI fixtures (states, fixes, sky). */
internal object ReceiverUiFixtures {
    val TARGET = ReceiverTarget("192.168.42.1", 9001)

    fun fix(provider: Provider = Provider.EXTERNAL_TCP, rtk: RtkStatus = RtkStatus.FIX, used: Int? = 18, visible: Int? = 24,
            diffAge: Double? = 1.0) = Fix(
        provider, Instant.parse("2026-11-25T12:00:00Z"), TimestampSource.DEVICE, 41.25, -75.88, 180.0, 214.0, -34.0,
        0.7, 1.1, 1.3, 0.012, 0.02, null, null, null, rtk, used, visible, diffAge, null, null,
    )

    fun sky(used: Int, visible: Int) = SkySnapshot(List(visible) { i ->
        SatInfo(Constellation.GPS, i + 1, 45.0, 90.0, 40.0, usedInFix = i < used)
    })

    val internal = ReceiverSelectionState()

    fun connected(profile: ExternalReceiverProfile = ExternalReceiverProfile.REACH_RS2_PLUS,
                  origin: ProfileOrigin = ProfileOrigin.USER, identity: ReceiverIdentity? = null) = ReceiverSelectionState(
        selectedSource = LocationSourceType.EXTERNAL, savedTarget = TARGET, savedName = null, profile = profile,
        profileOrigin = origin, activeProvider = ProviderChoice.EXTERNAL_TCP, link = ConnectionState.CONNECTED,
        identity = identity,
    )

    fun rs4ProIdentity(name: String? = "Reach RS4 Pro base") = ReceiverIdentity(
        host = TARGET.host, reportedModel = "Reach RS4 Pro", model = EmlidReachModel.RS4_PRO,
        recognizedProfile = ExternalReceiverProfile.REACH_RS4_PRO, name = name, serial = "SN123", firmware = "34.7",
    )
}
