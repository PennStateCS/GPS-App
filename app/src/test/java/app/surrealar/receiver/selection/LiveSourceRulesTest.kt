package app.surrealar.receiver.selection

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/** Stabilization item 4: the live positioning source vs the selected receiver (pure rules). */
class LiveSourceRulesTest {

    companion object {
        val A = ReceiverTarget("192.168.42.1", 9001)
        val B = ReceiverTarget("192.168.42.7", 9001)

        fun fix(provider: Provider, t: Long = 0L, rtk: RtkStatus = RtkStatus.FIX) = Fix(
            provider = provider, timeUtc = Instant.ofEpochSecond(1_700_000_000L + t), timestampSource = TimestampSource.DEVICE,
            latDeg = 41.0, lonDeg = -75.0, altEllipsoidalM = 200.0, altMslM = 230.0, geoidSeparationM = -30.0,
            hDop = 0.8, vDop = 1.2, pDop = 1.4, hAccM = 0.014, vAccM = 0.02, stdDevEastM = null, stdDevNorthM = null,
            stdDevUpM = null, rtkStatus = rtk, satsUsed = 20, satsVisible = 30, diffAgeS = 1.0, speedMps = null,
            courseDeg = null,
        )

        fun identity(host: String = A.host) = ReceiverIdentity(host, "Reach RS4", null, null, "reach-a", "SN1", "34.1")
    }

    private fun input(
        provider: ProviderChoice = ProviderChoice.EXTERNAL_TCP,
        fix: Fix? = fix(Provider.EXTERNAL_TCP),
        fenced: Fix? = null,
        link: ConnectionState? = ConnectionState.CONNECTED,
        endpoint: ReceiverTarget? = A,
        active: ReceiverTarget? = A,
        selected: LocationSourceType = LocationSourceType.EXTERNAL,
        saved: ReceiverTarget? = A,
        name: String? = "Base",
        attempt: ConnectionAttempt = ConnectionAttempt.Idle,
        identity: ReceiverIdentity? = identity(),
    ) = LiveSourceRules.Input(provider, fix, fenced, link, endpoint, true, active, selected, saved, name, attempt, identity)

    @Test fun `an external fix from the connected saved receiver carries its identity`() {
        val i = input()
        val s = LiveSourceRules.derive(i)
        assertEquals(LiveSourceKind.EXTERNAL, s.kind)
        assertSame(i.fix, s.fix)
        assertEquals(LiveExternalSource(A, "Base", identity()), s.external)
        assertNull(s.transition)
    }

    @Test fun `startup with External saved but device GNSS live shows device GNSS and the pending receiver`() {
        val s = LiveSourceRules.derive(input(provider = ProviderChoice.INTERNAL, fix = fix(Provider.INTERNAL),
            link = ConnectionState.DISCONNECTED, endpoint = null, active = null))
        assertEquals(LiveSourceKind.DEVICE, s.kind)
        assertNull("no external identity on a device fix", s.external)
        assertEquals(SourceTransition.ExternalNotActive(A), s.transition)
        // No fix at all yet.
        val none = LiveSourceRules.derive(input(provider = ProviderChoice.INTERNAL, fix = null, link = null, endpoint = null, active = null))
        assertEquals(LiveSourceKind.NONE, none.kind)
        assertEquals(SourceTransition.ExternalNotActive(A), none.transition)
    }

    @Test fun `External active but not connected yet is a connecting state, never the saved identity`() {
        for (link in listOf(null, ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
            val s = LiveSourceRules.derive(input(link = link, fix = null))
            assertEquals(LiveSourceKind.NONE, s.kind)
            assertNull(s.external)
            assertEquals(SourceTransition.Connecting(A), s.transition)
        }
        for (link in listOf(ConnectionState.RECONNECTING, ConnectionState.ERROR)) {
            val s = LiveSourceRules.derive(input(link = link))
            assertEquals("a held fix during a drop is not shown as live", LiveSourceKind.NONE, s.kind)
            assertEquals(SourceTransition.Reconnecting(A), s.transition)
        }
    }

    @Test fun `a fix held from the previous receiver is never attributed to the new one`() {
        val old = fix(Provider.EXTERNAL_TCP, t = 1)
        // Switched A -> B: the adapter has not asked for B's endpoint yet.
        val s1 = LiveSourceRules.derive(input(fix = old, fenced = old, endpoint = A, active = B, saved = B, identity = null))
        assertEquals(LiveSourceKind.NONE, s1.kind)
        assertEquals(SourceTransition.Connecting(B), s1.transition)
        // Endpoint now B and the link up, but still the old fix: waiting for B's position.
        val s2 = LiveSourceRules.derive(input(fix = old, fenced = old, endpoint = B, active = B, saved = B, identity = null))
        assertEquals(SourceTransition.WaitingForPosition(B), s2.transition)
        assertNull(s2.fix)
        // B's first fix.
        val s3 = LiveSourceRules.derive(input(fix = fix(Provider.EXTERNAL_TCP, t = 2), fenced = old, endpoint = B, active = B, saved = B, identity = null))
        assertEquals(LiveSourceKind.EXTERNAL, s3.kind)
        assertEquals(B, s3.external!!.target)
    }

    @Test fun `identity for another host is not attached`() {
        val s = LiveSourceRules.derive(input(identity = identity(host = "192.168.42.99")))
        assertEquals(LiveSourceKind.EXTERNAL, s.kind)
        assertNull(s.external!!.identity)
    }

    @Test fun `a saved target that differs from the connected endpoint is not the live source`() {
        val s = LiveSourceRules.derive(input(saved = B, active = A, endpoint = A))
        assertEquals(LiveSourceKind.NONE, s.kind)
        assertEquals(SourceTransition.Connecting(A), s.transition)
    }

    @Test fun `testing another receiver keeps the current source live with a transition`() {
        val s = LiveSourceRules.derive(input(attempt = ConnectionAttempt.Testing(1, B, null)))
        assertEquals(LiveSourceKind.EXTERNAL, s.kind)
        assertEquals(A, s.external!!.target)
        assertEquals(SourceTransition.Testing(B), s.transition)
        assertEquals(SourceTransition.Switching(B),
            LiveSourceRules.derive(input(attempt = ConnectionAttempt.Switching(1, B))).transition)
    }

    @Test fun `a failed connection is a transition only when the receiver is not the live source`() {
        val failed = ConnectionAttempt.Failed(2, B, ConnectError.Unreachable(9001))
        assertNull("receiver A still live", LiveSourceRules.derive(input(attempt = failed)).transition)
        val onDevice = LiveSourceRules.derive(input(provider = ProviderChoice.INTERNAL, fix = fix(Provider.INTERNAL),
            selected = LocationSourceType.INTERNAL, attempt = failed))
        assertEquals(LiveSourceKind.DEVICE, onDevice.kind)
        assertEquals(SourceTransition.ConnectFailed(B, ConnectError.Unreachable(9001)), onDevice.transition)
    }

    @Test fun `device GNSS selected and live has no transition and ignores external fixes`() {
        val s = LiveSourceRules.derive(input(provider = ProviderChoice.INTERNAL, fix = fix(Provider.INTERNAL),
            selected = LocationSourceType.INTERNAL))
        assertEquals(LiveSourceKind.DEVICE, s.kind)
        assertNull(s.transition)
        val stray = LiveSourceRules.derive(input(provider = ProviderChoice.INTERNAL, fix = fix(Provider.EXTERNAL_TCP),
            selected = LocationSourceType.INTERNAL))
        assertEquals("an external fix while device GNSS is active is not attributed", LiveSourceKind.NONE, stray.kind)
    }

    @Test fun `replay is its own kind and shows the saved External selection as not active`() {
        val s = LiveSourceRules.derive(input(provider = ProviderChoice.REPLAY, fix = fix(Provider.REPLAY)))
        assertEquals(LiveSourceKind.REPLAY, s.kind)
        assertNull(s.external)
        assertEquals(SourceTransition.ExternalNotActive(A), s.transition)
    }
}
