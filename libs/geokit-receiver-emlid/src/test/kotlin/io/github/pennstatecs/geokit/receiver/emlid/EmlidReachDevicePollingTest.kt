package io.github.pennstatecs.geokit.receiver.emlid

import com.sun.net.httpserver.HttpServer
import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * [EmlidReachDevice] poll loops with a fake [EmlidReachDevice.EmlidApi] in virtual time: the battery
 * back-off (5 s → 15 s → 30 s, hidden after 3 failures, recovery line), device-info errors, corrections
 * mapping, restart and stop. The real [EmlidReachDevice.EmlidApi.Http] readers are exercised against a
 * loopback HTTP server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmlidReachDevicePollingTest {

    private class Diag : GnssLogger {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val enabled = false
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {
            lines += "${level.name} $message${throwable?.let { " [${it.message}]" } ?: ""}"
        }
        override fun isDue(key: String, intervalMs: Long) = false
        override fun trace(area: String, message: String) {}
    }

    private val diag = Diag().also { GnssLog.install(it) }
    @After fun tearDown() = GnssLog.uninstall()

    private class Link : EmlidReachDevice.EmlidApi.CorrectionsLink {
        override val info = MutableStateFlow<ReachCorrectionsInfo?>(null)
        var connects = 0
        var disconnects = 0
        var expires = 0
        override fun connect() { connects++ }
        override fun disconnect() { disconnects++ }
        override fun expireIfStale() { expires++ }
    }

    private class Api(
        val battery: ArrayDeque<() -> BatteryStatus?> = ArrayDeque(),
        val device: ArrayDeque<() -> ReachDeviceInfoDto?> = ArrayDeque(),
    ) : EmlidReachDevice.EmlidApi {
        val links = mutableListOf<Link>()
        val hosts = mutableListOf<String>()
        var batteryReads = 0
        var deviceReads = 0
        override fun batteryReader(host: String): suspend () -> BatteryStatus? {
            hosts += host
            return { batteryReads++; (battery.removeFirstOrNull() ?: { null })() }
        }
        override fun deviceReader(host: String): suspend () -> ReachDeviceInfoDto? =
            { deviceReads++; (device.removeFirstOrNull() ?: { null })() }
        override fun corrections(host: String): EmlidReachDevice.EmlidApi.CorrectionsLink = Link().also { links += it }
    }

    private fun bat(pct: Int) = BatteryStatus(pct, 8.1, 0.2, 30.0, "charging", true, otg = false,
        usbChargerCurrentA = 1.234, usbChargerVoltageV = 5.0)

    private fun dto(name: String) = ReachDeviceInfoDto(name, "Reach RS2+", "33.1", "SN", "1h", "fw2", null, 1000L, 400L, "reach.local")

    /**
     * The device's loops run on a standalone virtual-time scheduler that only these helpers advance, so
     * a failed assertion (which skips stop()) can never leave a runner advancing endless poll loops.
     */
    private val scheduler = TestCoroutineScheduler()
    private fun runCurrent() = scheduler.runCurrent()
    private fun advanceTimeBy(ms: Long) = scheduler.advanceTimeBy(ms)

    private fun device(api: Api) =
        EmlidReachDevice(api, newScope = { CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob()) })

    @Test fun capabilities_areAll_andNotRunningBeforeStart() {
        val d = EmlidReachDevice(Api())
        assertEquals(ReceiverCapabilities.ALL, d.capabilities)
        assertFalse(d.isRunning)
        d.stop()   // safe when never started
        assertNull(d.battery.value)
    }

    @Test fun battery_backsOff_hidesAfterThreeFailures_andRecovers() = runBlocking {
        val api = Api()
        api.battery.add { bat(80) }                 // t=0 ok, next read at 15 s
        api.battery.add { null }                    // t=15 fail #1 → +5 s
        api.battery.add { error("timeout") }        // t=20 fail #2 → +15 s
        api.battery.add { null }                    // t=35 fail #3 → hidden, +30 s
        api.battery.add { bat(70) }                 // t=65 recovered
        val d = device(api)
        d.start("10.0.0.5")
        runCurrent()
        assertEquals(80, d.battery.value!!.percent)
        assertEquals(mapOf("USB Charger Current" to "1.23 A", "USB Charger Voltage" to "5.00 V", "OTG Power" to "Disabled"),
            d.battery.value!!.details)
        advanceTimeBy(15_001); runCurrent()
        assertEquals("one failure keeps the last reading", 80, d.battery.value!!.percent)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, api.batteryReads)
        assertEquals(80, d.battery.value!!.percent)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(4, api.batteryReads)
        assertNull("hidden after three failures", d.battery.value)
        assertTrue(diag.lines.toString(), diag.lines.contains("WARN External receiver battery read failed: battery hidden attempts=3 [timeout]"))  // last exception of the outage
        advanceTimeBy(29_000); runCurrent()
        assertEquals("30 s back-off after the third failure", 4, api.batteryReads)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(70, d.battery.value!!.percent)
        assertTrue(diag.lines.contains("INFO External receiver battery read recovered after 3 attempts"))
        d.stop()
    }

    @Test fun battery_thirdFailureCarriesTheLastException() = runBlocking {
        val api = Api()
        repeat(2) { api.battery.add { null } }
        api.battery.add { throw java.io.IOException("unreachable") }
        val d = device(api)
        d.start("h")
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(diag.lines.toString(),
            diag.lines.contains("WARN External receiver battery read failed: battery hidden attempts=3 [unreachable]"))
        d.stop()
    }

    @Test fun deviceInfo_isPolledEvery30s_andAnErrorIsShownAsRawResponse() = runBlocking {
        val api = Api()
        api.device.add { dto("Reach A") }
        api.device.add { throw java.io.IOException("HTTP 500") }
        val d = device(api)
        d.start("192.168.42.1")
        runCurrent()
        val info = d.deviceInfo.value!!
        assertEquals("192.168.42.1", info.host)
        assertEquals("Reach A", info.name)
        assertEquals(600L, info.storage!!.usedMb)
        assertEquals("fw2", info.details[EmlidMapping.GNSS_RECEIVER_FW])
        advanceTimeBy(30_001); runCurrent()
        assertEquals(2, api.deviceReads)
        val failed = d.deviceInfo.value!!
        assertNull(failed.name)
        assertEquals("ERROR: HTTP 500", failed.details[EmlidMapping.RAW_RESPONSE])
        d.stop()
    }

    @Test fun corrections_areMapped_andTheWatchdogRuns() = runBlocking {
        val api = Api()
        val d = device(api)
        d.start("h")
        runCurrent()
        val link = api.links.single()
        assertEquals(1, link.connects)
        link.info.value = ReachCorrectionsInfo(true, "NTRIP", 18, 1.0, 2_500.0, 41.0, -76.0, 200.0, "fix")
        runCurrent()
        val c = d.corrections.value!!
        assertTrue(c.isReceiving)
        assertEquals(2.5, c.baselineKm!!, 1e-12)
        assertEquals(18, c.baseSatellites)
        assertNull(c.stationId)
        advanceTimeBy(3_001); runCurrent()
        assertEquals(3, link.expires)
        link.info.value = null; runCurrent()
        assertNull(d.corrections.value)
        d.stop()
        assertEquals(1, link.disconnects)
    }

    @Test fun startAgain_stopsThePreviousSession_andStopClearsEverything() = runBlocking {
        val api = Api()
        repeat(4) { api.battery.add { bat(50) } }
        api.device.add { dto("A") }
        val d = device(api)
        d.start("first")
        runCurrent()
        assertTrue(d.isRunning)
        d.start("second")
        runCurrent()
        assertEquals(listOf("first", "second"), api.hosts)
        assertEquals("the first link was disconnected", 1, api.links[0].disconnects)
        assertEquals(50, d.battery.value!!.percent)
        d.stop()
        assertFalse(d.isRunning)
        assertNull(d.battery.value); assertNull(d.deviceInfo.value); assertNull(d.corrections.value)
        val reads = api.batteryReads
        advanceTimeBy(120_000); runCurrent()
        assertEquals("no loop survives stop()", reads, api.batteryReads)
    }

    @Test fun httpApi_readersAndLink_talkToTheHost() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        server.createContext("/") { ex ->
            paths += ex.requestURI.path
            ex.sendResponseHeaders(404, -1); ex.close()
        }
        server.start()
        try {
            val host = "127.0.0.1:${server.address.port}"
            assertNull(EmlidReachDevice.EmlidApi.Http.batteryReader(host)())
            assertNull(EmlidReachDevice.EmlidApi.Http.deviceReader(host)())
            assertEquals(listOf("/battery", "/status", "/info", "/status"), paths.toList())
            val link = EmlidReachDevice.EmlidApi.Http.corrections(host)
            val info: StateFlow<ReachCorrectionsInfo?> = link.info
            assertNull(info.value)
            link.expireIfStale()
            link.disconnect()       // never connected: harmless
        } finally {
            server.stop(0)
        }
    }
}
