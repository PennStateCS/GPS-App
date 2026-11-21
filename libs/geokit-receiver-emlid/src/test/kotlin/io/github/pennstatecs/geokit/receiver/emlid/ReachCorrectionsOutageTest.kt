package io.github.pennstatecs.geokit.receiver.emlid

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** Corrections status after an outage: cleared while the link is down, restored when it returns. */
class ReachCorrectionsOutageTest {

    /** Records listeners; the test fires socket.io events by hand. */
    private class FakeSocket : CorrectionsSocket {
        val listeners = mutableMapOf<String, (Array<out Any?>) -> Unit>()
        var connected = false
        var disconnects = 0
        override fun on(event: String, listener: (Array<out Any?>) -> Unit) { listeners[event] = listener }
        override fun off(event: String) { listeners.remove(event) }
        override fun connect() { connected = true }
        override fun disconnect() { connected = false; disconnects++ }
        override fun connected(): Boolean = connected
        fun fire(event: String, vararg args: Any?) = listeners[event]?.invoke(args)
    }

    private var now = 0L
    private val socket = FakeSocket()
    private var options: IO.Options? = null
    private val svc = ReachCorrectionsService("192.168.42.1", nowMs = { now }, socketFactory = { _: URI, o: IO.Options ->
        options = o; socket
    })

    private fun streamActive() = JSONObject(
        """{"name":"stream_status","payload":{"correction_input":[{"state":"active","type":"NTRIP"}]}}"""
    )

    @Test fun `reconnects indefinitely with backoff capped at 15 s`() {
        svc.connect()
        assertEquals(Int.MAX_VALUE, options!!.reconnectionAttempts)
        assertTrue(options!!.reconnection)
        assertEquals(15_000L, options!!.reconnectionDelayMax)
    }

    @Test fun `a disconnect clears the status`() {
        svc.connect()
        socket.fire("broadcast", streamActive())
        assertEquals("NTRIP", svc.correctionsInfo.value?.channel)
        socket.fire(Socket.EVENT_DISCONNECT, "transport close")
        assertNull(svc.correctionsInfo.value)
    }

    @Test fun `status comes back after more than 5 failed reconnects`() {
        svc.connect()
        socket.fire("broadcast", streamActive())
        socket.fire(Socket.EVENT_DISCONNECT, "transport error")
        repeat(8) { socket.fire(Socket.EVENT_CONNECT_ERROR, "timeout") }
        assertNull(svc.correctionsInfo.value)
        socket.fire(Socket.EVENT_CONNECT)
        socket.fire("broadcast", streamActive())
        assertEquals(true, svc.correctionsInfo.value?.isReceiving)
    }

    @Test fun `reconnect_failed also clears the status`() {
        svc.connect()
        socket.fire("broadcast", streamActive())
        socket.fire("reconnect_failed")
        assertNull(svc.correctionsInfo.value)
    }

    @Test fun `no broadcast for 10 s ages the status out, and an identical broadcast restores it`() {
        svc.connect()
        socket.fire("broadcast", streamActive())
        now += 9_000; svc.expireIfStale()
        assertNotNull("9 s: still fresh", svc.correctionsInfo.value)
        now += 2_000; svc.expireIfStale()
        assertNull("11 s without a broadcast: unknown", svc.correctionsInfo.value)
        socket.fire("broadcast", streamActive())      // same payload as before
        assertEquals("NTRIP", svc.correctionsInfo.value?.channel)
    }

    @Test fun `regular broadcasts keep the status`() {
        svc.connect()
        repeat(30) { now += 1_000; socket.fire("broadcast", streamActive()); svc.expireIfStale() }
        assertNotNull(svc.correctionsInfo.value)
    }

    // ── EmlidReachDevice: watchdog runs while started, and stop() leaves nothing behind ──

    private class CountingLink(private val inner: ReachCorrectionsService) : EmlidReachDevice.EmlidApi.CorrectionsLink {
        @Volatile var expireCalls = 0
        override val info: StateFlow<ReachCorrectionsInfo?> = inner.correctionsInfo
        override fun connect() = inner.connect()
        override fun disconnect() = inner.disconnect()
        override fun expireIfStale() { expireCalls++; inner.expireIfStale() }
    }

    @Test fun `device ages the status out and stop() leaves no loop or socket running`() = runBlocking {
        val link = CountingLink(svc)
        val api = object : EmlidReachDevice.EmlidApi {
            override fun batteryReader(host: String): suspend () -> BatteryStatus? = { null }
            override fun deviceReader(host: String): suspend () -> ReachDeviceInfoDto? = { null }
            override fun corrections(host: String) = link
        }
        val device = EmlidReachDevice(api, newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) })
        device.start("192.168.42.1")
        withTimeout(5_000) { while (!socket.connected) delay(10) }
        socket.fire("broadcast", streamActive())
        withTimeout(5_000) { device.corrections.first { it != null } }

        now += 11_000                                   // receiver goes quiet
        withTimeout(5_000) { device.corrections.first { it == null } }
        assertTrue(link.expireCalls > 0)

        device.stop()
        assertFalse(device.isRunning)
        assertFalse("socket closed", socket.connected)
        val calls = link.expireCalls
        delay(2_500)
        assertEquals("watchdog stopped with the device", calls, link.expireCalls)
        assertNull(device.corrections.value)
    }
}
