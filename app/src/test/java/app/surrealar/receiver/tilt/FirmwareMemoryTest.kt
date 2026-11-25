package app.surrealar.receiver.tilt

import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareMemoryTest {

    private val rs4 = ExternalReceiverProfile.REACH_RS4_PRO

    @Test fun failedPoll_keepsTheLastKnownFirmware() {
        val m = FirmwareMemory()
        assertEquals("34.10", m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = "34.10"), rs4))
        assertEquals("poll failed (firmware null)", "34.10", m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = null), rs4))
        assertEquals("blank firmware", "34.10", m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = " "), rs4))
        assertEquals("device restarting (no info)", "34.10", m.update(null, rs4))
        assertEquals("a new value replaces it", "34.11", m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = "34.11"), rs4))
    }

    @Test fun anotherHost_forgetsIt() {
        val m = FirmwareMemory()
        m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = "34.10"), rs4)
        assertNull(m.update(ReceiverDeviceInfo(host = "192.168.42.7", firmware = null), rs4))
        assertEquals("33.2", m.update(ReceiverDeviceInfo(host = "192.168.42.7", firmware = "33.2"), rs4))
    }

    @Test fun profileChange_forgetsIt() {
        val m = FirmwareMemory()
        m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = "34.10"), rs4)
        assertNull(m.update(ReceiverDeviceInfo(host = "192.168.42.1", firmware = null), ExternalReceiverProfile.REACH_RS4))
        assertNull(m.update(null, ExternalReceiverProfile.REACH_RS4))
    }
}
