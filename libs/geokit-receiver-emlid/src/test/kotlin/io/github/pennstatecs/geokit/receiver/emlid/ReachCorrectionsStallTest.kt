package io.github.pennstatecs.geokit.receiver.emlid

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The status socket recovers from a silent stall and never leaves an old socket retrying. */
class ReachCorrectionsStallTest {

    private class FakeSocket : CorrectionsSocket {
        val listeners = mutableMapOf<String, (Array<out Any?>) -> Unit>()
        var connected = false
        var closed = false
        override fun on(event: String, listener: (Array<out Any?>) -> Unit) { listeners[event] = listener }
        override fun off(event: String) { listeners.remove(event) }
        override fun offAll() { listeners.clear() }
        override fun connect() {}                    // like socket.io: connected only after the handshake
        override fun disconnect() { connected = false; closed = true }
        override fun connected(): Boolean = connected
        fun fire(event: String, vararg args: Any?) = listeners[event]?.invoke(args)
    }

    private var now = 0L
    private val sockets = mutableListOf<FakeSocket>()
    private var options: IO.Options? = null
    private val svc = ReachCorrectionsService("192.168.2.174", nowMs = { now }, socketFactory = { _: URI, o: IO.Options ->
        options = o; FakeSocket().also { sockets += it }
    })
    private fun timeMark() = JSONObject("""{"name":"time_marks"}""")

    @Test fun `a socket silent for 30 s is replaced, once per interval`() {
        svc.connect(); sockets[0].fire(Socket.EVENT_CONNECT)
        for (t in 0L..20_000L step 5_000L) { now = t; sockets[0].fire("broadcast", timeMark()); svc.expireIfStale() }
        assertEquals("still talking: no restart", 1, sockets.size)
        now = 51_000L; svc.expireIfStale()                         // 31 s of silence
        assertEquals(2, sockets.size)
        assertTrue("old socket closed", sockets[0].closed)
        assertTrue("old socket has no listeners left", sockets[0].listeners.isEmpty())
        now = 52_000L; svc.expireIfStale()
        assertEquals("not restarted again straight away", 2, sockets.size)
    }

    @Test fun `connect errors count as activity - socket io is retrying on its own`() {
        svc.connect()
        for (t in 0L..120_000L step 15_000L) { now = t; sockets[0].fire(Socket.EVENT_CONNECT_ERROR, "server error"); svc.expireIfStale() }
        assertEquals(1, sockets.size)
    }

    @Test fun `connecting again closes the previous, still reconnecting socket`() {
        svc.connect()                                               // never connects (receiver off)
        svc.connect()
        assertEquals(2, sockets.size)
        assertTrue(sockets[0].closed); assertFalse(sockets[1].closed)
        assertTrue("own Manager per socket", options!!.forceNew)
    }

    @Test fun `disconnect closes the socket and removes all listeners`() {
        svc.connect(); svc.disconnect()
        assertTrue(sockets[0].closed); assertTrue(sockets[0].listeners.isEmpty())
        now = 100_000L; svc.expireIfStale()
        assertEquals("no restart after an intentional disconnect", 1, sockets.size)
    }
}