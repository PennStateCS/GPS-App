package io.github.pennstatecs.geokit.gnss.receiver

import io.github.pennstatecs.geokit.gnss.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendor-neutral receiver models: defaults, value semantics, derived values and the contracts. */
class ReceiverModelsTest {

    @Test fun battery_defaultsAndCopy() {
        val b = ReceiverBattery(percent = 80, isCharging = false)
        assertNull(b.voltageV); assertNull(b.temperatureC); assertNull(b.currentA); assertNull(b.chargerStatus)
        assertTrue(b.details.isEmpty())
        val full = b.copy(isCharging = true, voltageV = 8.2, temperatureC = 30.5, currentA = -0.4,
            chargerStatus = "charging", details = mapOf("Cycles" to "12"))
        assertEquals(full, ReceiverBattery(80, true, 8.2, 30.5, -0.4, "charging", mapOf("Cycles" to "12")))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertNotEquals(b, full)
        assertTrue(full.toString().contains("percent=80"))
    }

    @Test fun deviceInfo_defaultsAreAllUnknown() {
        val d = ReceiverDeviceInfo()
        assertNull(d.host); assertNull(d.name); assertNull(d.model); assertNull(d.firmware); assertNull(d.serial)
        assertNull(d.uptimeS); assertNull(d.uptimeText); assertNull(d.hostname); assertNull(d.storage)
        assertTrue(d.details.isEmpty())
        val set = ReceiverDeviceInfo("192.168.42.1", "Reach", "RS2+", "33.1", "SN1", 60L, "1m", "reach.local",
            ReceiverStorage(1000, 250, 750), mapOf("GNSS" to "x"))
        assertEquals(set, set.copy())
        assertEquals("RS2+", set.model)
        assertEquals(25, set.storage!!.usagePercent)
    }

    @Test fun storage_usagePercent_truncates() {
        assertEquals(33, ReceiverStorage(totalMb = 3, usedMb = 1, availableMb = 2).usagePercent)
        assertEquals(0, ReceiverStorage(100, 0, 100).usagePercent)
        assertEquals(100, ReceiverStorage(100, 100, 0).usagePercent)
    }

    /**
     * Pins current behaviour for a zero capacity: 0/0 → NaN → 0 %, but used > 0 with total 0 →
     * +Infinity → Int.MAX_VALUE (a receiver reporting total=0 would show an absurd percentage).
     */
    @Test fun storage_usagePercent_zeroCapacity_currentBehaviour() {
        assertEquals(0, ReceiverStorage(0, 0, 0).usagePercent)
        assertEquals(Int.MAX_VALUE, ReceiverStorage(0, 5, 0).usagePercent)
    }

    @Test fun corrections_defaultsAndValues() {
        val c = ReceiverCorrections(isReceiving = false)
        assertNull(c.channel); assertNull(c.stationId); assertNull(c.ageS); assertNull(c.baselineKm)
        assertNull(c.baseLatDeg); assertNull(c.baseLonDeg); assertNull(c.baseHeightM); assertNull(c.baseSatellites)
        assertNull(c.solution); assertTrue(c.details.isEmpty())
        val full = ReceiverCorrections(true, "NTRIP", "0001", 1.0, 2.5, 41.0, -76.0, 200.0, 18, "fix", mapOf("k" to "v"))
        assertEquals(full, full.copy())
        assertEquals(18, full.baseSatellites)
        assertNotEquals(c, full)
    }

    @Test fun capabilities_noneAndAll() {
        val none = ReceiverCapabilities.NONE
        assertFalse(none.hasBattery || none.hasDeviceStatus || none.hasCorrectionsStatus || none.supportsDiscovery)
        val all = ReceiverCapabilities.ALL
        assertTrue(all.hasBattery && all.hasDeviceStatus && all.hasCorrectionsStatus && all.supportsDiscovery)
        assertEquals(none, ReceiverCapabilities())
        assertEquals(ReceiverCapabilities(hasBattery = true), none.copy(hasBattery = true))
    }

    @Test fun discoveredReceiver_defaults() {
        val r = DiscoveredReceiver(host = "10.0.0.2", label = "Reach")
        assertNull(r.profileKey); assertNull(r.nmeaPort)
        assertEquals(DiscoveredReceiver("10.0.0.2", "Reach", "reach_rs2_plus", 9001),
            r.copy(profileKey = "reach_rs2_plus", nmeaPort = 9001))
    }

    @Test fun accuracyClass_defaultForEveryProvider() {
        assertEquals(AccuracyClass.SURVEY_MULTIBAND, AccuracyClass.defaultFor(Provider.EXTERNAL_TCP))
        assertEquals(AccuracyClass.SURVEY_MULTIBAND, AccuracyClass.defaultFor(Provider.EXTERNAL_BT))
        for (p in listOf(Provider.INTERNAL, Provider.REPLAY, Provider.MODEL, Provider.OTHER)) {
            assertEquals(p.name, AccuracyClass.DEVICE, AccuracyClass.defaultFor(p))
        }
        assertEquals(listOf("EMLID", "GENERIC"), ReceiverVendor.entries.map { it.name })
    }

    /** A minimal implementation of the contracts, as a vendor module would provide. */
    private class FakeDevice : ReceiverDevice, ReceiverDiscovery {
        override val capabilities = ReceiverCapabilities(hasBattery = true, supportsDiscovery = true)
        override val battery = MutableStateFlow<ReceiverBattery?>(null)
        override val deviceInfo: StateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null)
        override val corrections: StateFlow<ReceiverCorrections?> = MutableStateFlow(null)
        override fun start(host: String) { battery.value = ReceiverBattery(50, false, details = mapOf("host" to host)) }
        override fun stop() { battery.value = null }
        override suspend fun discover(timeoutMs: Long) = listOf(DiscoveredReceiver("h", "Reach"))
    }

    @Test fun contracts_canBeImplemented() = runBlocking {
        val d = FakeDevice()
        d.start("h")
        assertEquals("h", d.battery.value!!.details["host"])
        d.stop(); d.stop()
        assertNull(d.battery.value)
        assertEquals(1, d.discover(100).size)
    }
}
