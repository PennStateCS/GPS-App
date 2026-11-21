package app.surrealar.receiver

import app.surrealar.gnss.external.BatteryStatus
import app.surrealar.gnss.external.ReachDeviceInfoDto
import app.surrealar.gnss.external.model.ReachCorrectionsInfo
import app.surrealar.gnss.receiver.ReceiverBattery
import app.surrealar.gnss.receiver.ReceiverCapabilities
import app.surrealar.gnss.receiver.ReceiverCorrections
import app.surrealar.gnss.receiver.ReceiverDevice
import app.surrealar.gnss.receiver.ReceiverDeviceInfo
import app.surrealar.gnss.receiver.ReceiverVendor
import app.surrealar.receiver.emlid.EmlidReachDevice
import app.surrealar.settings.model.ExternalReceiverProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDeviceManagerTest {

    /** Records its lifecycle; "running" between start and stop. */
    private class FakeDevice(override val capabilities: ReceiverCapabilities) : ReceiverDevice {
        val events = mutableListOf<String>()
        var running = false
        override val battery = MutableStateFlow<ReceiverBattery?>(null)
        override val deviceInfo: StateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null)
        override val corrections: StateFlow<ReceiverCorrections?> = MutableStateFlow(null)
        override fun start(host: String) { events += "start:$host"; running = true; battery.value = ReceiverBattery(80, false) }
        override fun stop() { if (running) events += "stop"; running = false; battery.value = null }
    }

    private val external = MutableStateFlow(true)
    private val profile = MutableStateFlow(ExternalReceiverProfile.REACH_RS2_PLUS)
    private val host = MutableStateFlow<String?>("192.168.42.1")
    private val created = mutableListOf<FakeDevice>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun manager(deviceFor: (ReceiverVendor) -> ReceiverDevice = { v ->
        if (v == ReceiverVendor.EMLID) FakeDevice(ReceiverCapabilities.ALL).also { created += it } else GenericNmeaDevice
    }) = ReceiverDeviceManager(external, profile, host, deviceFor, { null }, scope)

    private fun running() = created.count { it.running }

    @Test fun `Emlid profile on an external source starts the Emlid device on the host`() {
        val m = manager()
        assertEquals(1, created.size)
        assertEquals(listOf("start:192.168.42.1"), created[0].events)
        assertSame(created[0], m.activeDevice.value)
        assertEquals(ReceiverCapabilities.ALL, m.capabilities.value)
        assertEquals(80, m.battery.value?.percent)
    }

    @Test fun `switching to Generic NMEA stops the Emlid device and hides everything`() {
        val m = manager()
        profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        assertEquals(listOf("start:192.168.42.1", "stop"), created[0].events)
        assertSame(GenericNmeaDevice, m.activeDevice.value)
        assertEquals(ReceiverCapabilities.NONE, m.capabilities.value)
        assertNull(m.battery.value)
        assertEquals(0, running())
    }

    @Test fun `back to Emlid creates a fresh device and never more than one runs`() {
        manager()
        profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        profile.value = ExternalReceiverProfile.REACH_RS4
        assertEquals(2, created.size)
        assertFalse(created[0].running)
        assertTrue(created[1].running)
        assertEquals(1, running())
    }

    @Test fun `profiles of the same vendor keep the device running`() {
        manager()
        profile.value = ExternalReceiverProfile.REACH_RS4_PRO
        assertEquals(1, created.size)
        assertEquals(listOf("start:192.168.42.1"), created[0].events)
    }

    @Test fun `a host change restarts on the new host and a blank host stops`() {
        manager()
        host.value = "10.0.0.7"
        assertEquals(listOf("start:192.168.42.1", "stop", "start:10.0.0.7"), created[0].events)
        host.value = "  "
        assertFalse(created[0].running)
    }

    @Test fun `leaving the external source stops the device (same timing as before)`() {
        val m = manager()
        external.value = false
        assertFalse(created[0].running)
        assertSame(GenericNmeaDevice, m.activeDevice.value)
        assertNull(m.battery.value)
    }

    @Test fun `nothing starts until a host is known`() {
        host.value = null
        manager()
        assertEquals(0, running())
        host.value = "192.168.42.1"
        assertEquals(1, running())
    }

    @Test fun `generic NMEA device has no capabilities and null flows`() {
        assertEquals(ReceiverCapabilities.NONE, GenericNmeaDevice.capabilities)
        GenericNmeaDevice.start("10.0.0.1")
        assertNull(GenericNmeaDevice.battery.value)
        assertNull(GenericNmeaDevice.deviceInfo.value)
        assertNull(GenericNmeaDevice.corrections.value)
    }

    @Test fun `profile capabilities follow the selected profile even when the source is internal`() {
        val m = manager()
        external.value = false
        assertEquals(ReceiverCapabilities.ALL, m.profileCapabilities.value)
        profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        assertEquals(ReceiverCapabilities.NONE, m.profileCapabilities.value)
    }

    // ── Real EmlidReachDevice with a fake HTTP API: no poll loop survives a profile change ──

    private class FakeApi : EmlidReachDevice.EmlidApi {
        val links = mutableListOf<FakeLink>()
        override fun batteryReader(host: String): suspend () -> BatteryStatus? = { BatteryStatus(64, 7.2, 0.1, 25.0, "Charging", true) }
        override fun deviceReader(host: String): suspend () -> ReachDeviceInfoDto? = {
            ReachDeviceInfoDto("Reach6", "RS2+", "33", "S", null, null, null, null, null, null)
        }
        override fun corrections(host: String) = FakeLink().also { links += it }
        class FakeLink : EmlidReachDevice.EmlidApi.CorrectionsLink {
            override val info = MutableStateFlow<ReachCorrectionsInfo?>(null)
            var connected = false
            override fun connect() { connected = true }
            override fun disconnect() { connected = false }
        }
    }

    @Test fun `real Emlid device polls, then stops every loop and socket on switch to Generic`() = runBlocking {
        val api = FakeApi()
        val devices = mutableListOf<EmlidReachDevice>()
        val m = manager { v -> if (v == ReceiverVendor.EMLID) EmlidReachDevice(api).also { devices += it } else GenericNmeaDevice }
        withTimeout(5_000) { m.battery.first { it != null } }
        assertEquals(64, m.battery.value?.percent)
        assertTrue(m.battery.value!!.isCharging)
        withTimeout(5_000) { m.deviceInfo.first { it != null } }
        assertTrue(devices.single().isRunning)

        profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        assertFalse("no poll loops after stop", devices.single().isRunning)
        assertTrue(api.links.none { it.connected })
        assertNull(m.battery.value)
        assertNull(m.deviceInfo.value)
    }
}
