package app.surrealar.gnss.external

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the pure broadcast parsing in [ReachCorrectionsService] (navigation + stream_status).
 * Protects the corrections/RTK status diagnostics from silent breakage on firmware JSON changes.
 * Robolectric supplies real org.json; no socket/network/hardware is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReachCorrectionsParseTest {

    private val svc = ReachCorrectionsService("127.0.0.1")
    private fun nav(json: String) = svc.parseNavigation(JSONObject(json))
    private fun stream(json: String) = svc.parseStreamStatus(JSONObject(json))

    // ── navigation ────────────────────────────────────────────────────────────────────────────

    @Test fun `navigation full payload parses solution, age, baseline, sats and base position`() {
        val n = nav(
            """{"solution":"fix","aod":1.5,"baseline":2.3,"satellites":{"base":12},
                "base_position":{"coordinates":{"lat":41.0,"lon":-76.0,"h":300.0}}}"""
        )
        assertEquals("fix", n.solution)
        assertEquals(1.5, n.aod!!, 1e-9)
        assertEquals(2.3, n.baseline!!, 1e-9)
        assertEquals(12, n.satsBase)
        assertEquals(41.0, n.baseLat!!, 1e-9)
        assertEquals(-76.0, n.baseLon!!, 1e-9)
        assertEquals(300.0, n.baseH!!, 1e-9)
    }

    @Test fun `navigation passes through fixed float single none solution strings`() {
        listOf("fix", "float", "single", "none", "weird_unexpected").forEach {
            assertEquals(it, nav("""{"solution":"$it"}""").solution)
        }
    }

    @Test fun `navigation with no fields yields all nulls`() {
        val n = nav("{}")
        assertNull(n.solution); assertNull(n.aod); assertNull(n.baseline)
        assertNull(n.satsBase); assertNull(n.baseLat)
    }

    @Test fun `navigation tolerates null and non-finite numeric fields`() {
        val n = nav("""{"aod":null,"baseline":null,"satellites":{}}""")
        assertNull(n.aod)        // optDoubleOrNull treats NaN/null as absent
        assertNull(n.baseline)
        assertNull(n.satsBase)
    }

    // ── stream_status ─────────────────────────────────────────────────────────────────────────

    @Test fun `stream_status active with type reports receiving and channel`() {
        val s = stream("""{"correction_input":[{"state":"active","type":"NTRIP"}]}""")!!
        assertTrue(s.isReceiving)
        assertEquals("NTRIP", s.channel)
        assertEquals("active", s.state)
    }

    @Test fun `stream_status active without type defaults channel to RTCM`() {
        val s = stream("""{"correction_input":[{"state":"active"}]}""")!!
        assertTrue(s.isReceiving)
        assertEquals("RTCM", s.channel)
    }

    @Test fun `stream_status inactive reports not receiving and null channel`() {
        val s = stream("""{"correction_input":[{"state":"idle"}]}""")!!
        assertFalse(s.isReceiving)
        assertNull(s.channel)
    }

    @Test fun `stream_status legacy boolean connected is honored`() {
        val s = stream("""{"correction_input":[{"connected":true,"name":"Serial"}]}""")!!
        assertTrue(s.isReceiving)
        assertEquals("Serial", s.channel)
    }

    @Test fun `stream_status accepts a single object instead of an array`() {
        val s = stream("""{"correction_input":{"state":"active","type":"TCP"}}""")!!
        assertTrue(s.isReceiving)
        assertEquals("TCP", s.channel)
    }

    @Test fun `stream_status missing or empty correction_input returns null`() {
        assertNull(stream("{}"))
        assertNull(stream("""{"correction_input":[]}"""))
    }
}
