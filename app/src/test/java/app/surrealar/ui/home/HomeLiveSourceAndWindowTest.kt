package app.surrealar.ui.home

import app.surrealar.ui.home.HomeLiveSource.Link
import app.surrealar.ui.home.HomeLiveSource.Live
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLiveSourceAndWindowTest {

    private fun resolve(
        externalSelected: Boolean = true,
        provider: Provider? = Provider.EXTERNAL_TCP,
        valid: Boolean = true,
        active: Boolean = true,
        link: ConnectionState? = ConnectionState.CONNECTED,
        attempt: Boolean = false,
        identityHost: String? = "192.168.42.1",
        savedHost: String? = "192.168.42.1",
    ) = HomeLiveSource.resolve(externalSelected, provider, valid, active, link, attempt, identityHost, savedHost)

    @Test fun `external selected, external fix, link up, identity for the saved host - confirmed`() {
        assertEquals(HomeLiveSource.Resolved(Live.EXTERNAL, Link.CONNECTED, true), resolve())
    }

    @Test fun `startup - external selected, no fix, adapter not active yet - unavailable, nothing confirmed`() {
        assertEquals(HomeLiveSource.Resolved(Live.NONE, Link.UNAVAILABLE, false),
            resolve(provider = null, active = false, link = null))
    }

    @Test fun `startup - external selected, tablet fix still live, saved receiver being tested - connecting`() {
        assertEquals(HomeLiveSource.Resolved(Live.NONE, Link.CONNECTING, false),
            resolve(provider = Provider.INTERNAL, active = false, link = null, attempt = true))
    }

    @Test fun `reconnecting - link retrying`() {
        val r = resolve(provider = null, link = ConnectionState.RECONNECTING)
        assertEquals(Live.NONE, r.live)
        assertEquals(Link.RECONNECTING, r.link)
    }

    @Test fun `failed connection - adapter error is unavailable`() {
        assertEquals(Link.UNAVAILABLE, resolve(provider = null, link = ConnectionState.ERROR).link)
    }

    @Test fun `switching to tablet - external fix while tablet is selected is not shown`() {
        val r = resolve(externalSelected = false)
        assertEquals(Live.NONE, r.live)
        assertEquals(Link.NOT_APPLICABLE, r.link)
        assertFalse(r.identityConfirmed)
    }

    @Test fun `identity for another host (old receiver after a switch) is not confirmed`() {
        assertFalse(resolve(identityHost = "192.168.42.7").identityConfirmed)
        assertTrue(resolve(identityHost = " 192.168.42.1 ".uppercase()).identityConfirmed)
    }

    @Test fun `invalid coordinates and model fixes are no live fix`() {
        assertEquals(Live.NONE, resolve(valid = false).live)
        assertEquals(Live.NONE, resolve(provider = Provider.MODEL).live)
        assertEquals(Live.REPLAY, resolve(provider = Provider.REPLAY).live)
    }

    // ── RecentCountWindow ──────────────────────────────────────────────────────────────────────

    @Test fun `errors before the first sample do not count`() {
        val w = RecentCountWindow(60_000)
        assertEquals(0L, w.update(0, 120))
    }

    @Test fun `new errors count for the window, then drop out`() {
        val w = RecentCountWindow(60_000)
        w.update(0, 5)
        assertEquals(3L, w.update(10_000, 8))
        assertEquals(4L, w.update(20_000, 9))
        assertEquals(4L, w.update(65_000, 9))    // baseline is still the 0 s sample (newest at or before 5 s)
        assertEquals(1L, w.update(75_000, 9))    // the errors seen at 10 s left the window
        assertEquals(0L, w.update(85_000, 9))
    }

    @Test fun `a counter reset restarts the window`() {
        val w = RecentCountWindow(60_000)
        w.update(0, 50)
        assertEquals(0L, w.update(1_000, 0))
        assertEquals(2L, w.update(2_000, 2))
    }
}
