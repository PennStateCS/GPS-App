package app.surrealar.ui.settings.receiver

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.MismatchReason
import app.surrealar.receiver.selection.ProfileMismatch
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.TARGET
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.connected
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.fix
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.rs4ProIdentity
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.sky
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCardPresenterTest {

    private fun present(s: app.surrealar.receiver.selection.ReceiverSelectionState, f: io.github.pennstatecs.geokit.gnss.model.Fix? = null,
                        battery: ReceiverBattery? = null, caps: ReceiverCapabilities = ReceiverCapabilities.ALL) =
        ReceiverCardPresenter.present(s, f, sky(15, 20), battery, caps)

    @Test fun `tablet GNSS shows the device source, its fix and no receiver model`() {
        val m = present(ReceiverUiFixtures.internal, fix(Provider.INTERNAL, RtkStatus.SINGLE))
        assertFalse(m.external)
        assertEquals(CardConnection.DeviceGnss, m.connection)
        assertNull(m.modelLabel)
        assertNull(m.receiverName)
        assertEquals(CardFix.SINGLE, m.fix)
        assertEquals(15, m.satsUsed)
        assertEquals(20, m.satsVisible)
        assertNull("no battery for tablet GNSS", m.batteryPercent)
    }

    @Test fun `an external fix never shows as the tablet's and vice versa`() {
        assertNull(present(ReceiverUiFixtures.internal, fix(Provider.EXTERNAL_TCP)).fix)
        assertNull(present(connected(), fix(Provider.INTERNAL)).fix)
    }

    @Test fun `testing and switching show Testing and Connecting`() {
        val s = connected().copy(attempt = ConnectionAttempt.Testing(1, ReceiverTarget("10.0.0.2", 9001), null))
        assertEquals(CardConnection.Testing, present(s).connection)
        assertTrue(present(s).busy)
        val sw = connected().copy(attempt = ConnectionAttempt.Switching(1, TARGET))
        assertEquals(CardConnection.Connecting, present(sw).connection)
    }

    @Test fun `connected with a verified model shows it as reported by the receiver`() {
        val m = present(connected(ExternalReceiverProfile.REACH_RS4_PRO, ProfileOrigin.RECEIVER, rs4ProIdentity()),
            fix(rtk = RtkStatus.FIX), ReceiverBattery(76, true))
        assertEquals(CardConnection.Connected, m.connection)
        assertEquals("Emlid Reach RS4 Pro", m.modelLabel)
        assertEquals(ValueOrigin.REPORTED, m.modelOrigin)
        assertEquals("Reach RS4 Pro base", m.receiverName)
        assertEquals(CardFix.FIX, m.fix)
        assertEquals(76, m.batteryPercent)
        assertTrue(m.batteryCharging)
    }

    @Test fun `an unverified profile is labelled set by you, default or earlier report`() {
        assertEquals(ValueOrigin.SET_BY_USER, present(connected(origin = ProfileOrigin.USER)).modelOrigin)
        assertEquals(ValueOrigin.DEFAULT, present(connected(origin = ProfileOrigin.DEFAULT)).modelOrigin)
        assertEquals(ValueOrigin.EARLIER_REPORT, present(connected(origin = ProfileOrigin.RECEIVER)).modelOrigin)
        val m = present(connected(origin = ProfileOrigin.USER))
        assertEquals("Emlid Reach RS2+", m.modelLabel)
        assertEquals("host is the name when nothing else is known", TARGET.host, m.receiverName)
    }

    @Test fun `battery shows only when the capabilities report one`() {
        assertNull(present(connected(), battery = ReceiverBattery(50, false), caps = ReceiverCapabilities.NONE).batteryPercent)
        assertNull(present(connected(), battery = null).batteryPercent)
    }

    @Test fun `a failed test of the saved receiver is the chip state and offers Retry`() {
        val s = connected().copy(activeProvider = ProviderChoice.INTERNAL, link = ConnectionState.DISCONNECTED,
            attempt = ConnectionAttempt.Failed(3, TARGET, ConnectError.NoData(9001)))
        val m = present(s)
        assertEquals(CardConnection.Failed(ConnectError.NoData(9001)), m.connection)
        assertTrue(m.canRetry)
        assertNull(m.failedSwitch)
    }

    @Test fun `a failed switch to another receiver keeps the current one and shows a note`() {
        val s = connected().copy(attempt = ConnectionAttempt.Failed(4, ReceiverTarget("10.0.0.9", 9001), ConnectError.Unreachable(9001)))
        val m = present(s, fix())
        assertEquals(CardConnection.Connected, m.connection)
        assertEquals(ReceiverTarget("10.0.0.9", 9001), m.failedSwitch?.target)
        assertFalse(m.canRetry)
    }

    @Test fun `reconnecting and unavailable link states`() {
        assertEquals(CardConnection.Reconnecting, present(connected().copy(link = ConnectionState.RECONNECTING)).connection)
        val m = present(connected().copy(link = ConnectionState.ERROR))
        assertEquals(CardConnection.Unavailable, m.connection)
        assertTrue(m.canRetry)
        assertNull("no live fix while unavailable", present(connected().copy(link = ConnectionState.ERROR), fix()).fix)
    }

    @Test fun `the mismatch is passed to the banner for an external receiver only`() {
        val mm = ProfileMismatch(TARGET.host, "Reach RS4 Pro", ExternalReceiverProfile.REACH_RS4_PRO,
            ExternalReceiverProfile.REACH_RS2_PLUS, MismatchReason.PROFILE_SET_BY_USER)
        assertEquals(mm, present(connected().copy(mismatch = mm)).mismatch)
        assertNull(present(ReceiverUiFixtures.internal.copy(mismatch = mm, selectedSource = LocationSourceType.INTERNAL)).mismatch)
    }
}
