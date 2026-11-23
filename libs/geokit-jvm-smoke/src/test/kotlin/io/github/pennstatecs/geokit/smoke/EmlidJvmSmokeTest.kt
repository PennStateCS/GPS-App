package io.github.pennstatecs.geokit.smoke

import com.sun.net.httpserver.HttpServer
import io.github.pennstatecs.geokit.receiver.emlid.BatteryStatus
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * geokit-receiver-emlid from a plain JVM with only its declared runtime dependencies (this suite
 * depends on nothing else). The Reach JSON parsers are internal, so the public HTTP reader is driven
 * against a local JDK HTTP server; without `runtimeOnly(org.json:json)` it fails with NoClassDefFoundError.
 */
class EmlidJvmSmokeTest {

    @Test fun `org json on the runtime classpath is org json json, not android-json`() {
        val source = Class.forName("org.json.JSONObject").protectionDomain.codeSource.location.toString()
        assertTrue("org.json comes from $source", source.contains("json-20240303"))
        assertFalse(source.contains("android-json"))
    }

    /** Runs a suspend function to completion with the stdlib only (no coroutines test library here). */
    private fun <T> await(block: suspend () -> T): T {
        val latch = CountDownLatch(1)
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it; latch.countDown() })
        assertTrue("timed out", latch.await(20, TimeUnit.SECONDS))
        return outcome!!.getOrThrow()
    }

    @Test fun `Reach battery JSON parses through the public HTTP reader`() {
        val body = """{"charger_status":"Charging","current":0.5,"otg":false,"state_of_charge":87,"temperature":31.5,"voltage":7.9}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/battery") { ex ->
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val read = EmlidReachDevice.EmlidApi.Http.batteryReader("127.0.0.1:${server.address.port}")
            val battery: BatteryStatus? = await(read)
            assertNotNull(battery)
            assertEquals(87, battery!!.percent)
            assertEquals(7.9, battery.voltageV!!, 1e-9)
            assertEquals("Charging", battery.chargerStatus)
        } finally {
            server.stop(0)
        }
    }
}
