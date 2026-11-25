package app.surrealar.settings.model

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `external_recent_receivers` JSON (Robolectric: Android's org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentReceiversCodecTest {

    private fun r(host: String, port: Int = 9001, serial: String? = null, model: String? = null, ms: Long = 1L) =
        RecentReceiver(host, port, "reach_rs4", "Base", model, serial, "34.10".takeIf { serial != null }, ms)

    @Test fun `round trip keeps every field and the order`() {
        val list = listOf(r("192.168.42.1", serial = "SN1", model = "Reach RS4", ms = 20), RecentReceiver("10.0.0.5", 9000, "generic_nmea_tcp"))
        assertEquals(list, RecentReceiversCodec.decode(RecentReceiversCodec.encode(list)))
    }

    @Test fun `dedupe by serial, newest first`() {
        var list = emptyList<RecentReceiver>()
        list = RecentReceiversCodec.upsert(list, r("192.168.42.1", serial = "SN1", ms = 1))
        list = RecentReceiversCodec.upsert(list, r("192.168.42.2", serial = "SN2", ms = 2))
        list = RecentReceiversCodec.upsert(list, r("192.168.42.9", serial = "SN1", ms = 3))   // SN1 moved to .9
        assertEquals(listOf("192.168.42.9", "192.168.42.2"), list.map { it.host })
        assertEquals(3L, list.first().lastConnectedMs)
    }

    @Test fun `without a serial, dedupe by host and port`() {
        var list = emptyList<RecentReceiver>()
        list = RecentReceiversCodec.upsert(list, r("192.168.42.1", 9001, ms = 1))
        list = RecentReceiversCodec.upsert(list, r("192.168.42.1", 9002, ms = 2))
        list = RecentReceiversCodec.upsert(list, r("192.168.42.1", 9001, ms = 3))
        assertEquals(listOf(9001, 9002), list.map { it.port })
        assertEquals(2, list.size)
    }

    @Test fun `a reported serial replaces the serial-less entry of that host and port`() {
        var list = RecentReceiversCodec.upsert(emptyList(), r("192.168.42.1", ms = 1))
        list = RecentReceiversCodec.upsert(list, r("192.168.42.1", serial = "SN1", model = "Reach RS4", ms = 1))
        assertEquals(1, list.size)
        assertEquals("SN1", list.single().serial)
        assertEquals("Reach RS4", list.single().model)
        // A reconnect before the receiver reports again keeps what it reported.
        list = RecentReceiversCodec.upsert(list, RecentReceiver("192.168.42.1", 9001, "reach_rs4", null, lastConnectedMs = 5))
        assertEquals("Reach RS4", list.single().model)
        assertEquals("SN1", list.single().serial)
        assertEquals("Base", list.single().name)
        assertEquals(5L, list.single().lastConnectedMs)
    }

    @Test fun `at most eight entries, the oldest dropped`() {
        var list = emptyList<RecentReceiver>()
        for (i in 1..12) list = RecentReceiversCodec.upsert(list, r("10.0.0.$i", ms = i.toLong()))
        assertEquals(RecentReceiversCodec.MAX, list.size)
        assertEquals("10.0.0.12", list.first().host)
        assertEquals("10.0.0.5", list.last().host)
        // Decoding a longer stored list is capped too.
        val long = "[" + (1..10).joinToString(",") { """{"host":"10.1.0.$it","port":9001}""" } + "]"
        assertEquals(8, RecentReceiversCodec.decode(long).size)
    }

    @Test fun `corrupt JSON reads as empty, never throws`() {
        for (bad in listOf("", "   ", "{", "not json", "{\"host\":\"x\"}", "[1,2", "null")) {
            assertEquals(bad, emptyList<RecentReceiver>(), RecentReceiversCodec.decode(bad))
        }
        assertEquals(emptyList<RecentReceiver>(), RecentReceiversCodec.decode(null))
    }

    @Test fun `unknown fields are ignored and invalid entries dropped`() {
        val json = """[
            {"host":"192.168.42.1","port":9001,"model":"Reach RS4","futureField":{"a":1},"flags":[1,2]},
            {"host":"","port":9001},
            {"host":"10.0.0.1","port":70000},
            {"port":9001},
            "garbage",
            {"host":"10.0.0.2","port":9000,"serial":null}
        ]"""
        val list = RecentReceiversCodec.decode(json)
        assertEquals(listOf("192.168.42.1", "10.0.0.2"), list.map { it.host })
        assertEquals("Reach RS4", list.first().model)
        assertTrue(list.last().serial == null)
    }
}
