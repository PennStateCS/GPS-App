package app.surrealar.ui.settings.receiver

import app.surrealar.settings.model.ExternalReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualReceiverInputTest {

    @Test fun `hosts`() {
        listOf("192.168.42.1", " 10.0.0.5 ", "reach.local", "Reach-6.local", "base1").forEach {
            assertTrue(it, ManualReceiverInput.isValidHost(it))
        }
        listOf("", " ", "192.168.42", "192.168.42.256", "1.2.3.4.5", "http://192.168.42.1", "192.168.42.1:9001",
            "my receiver", "-bad.local", null).forEach {
            assertFalse(it.toString(), ManualReceiverInput.isValidHost(it))
        }
    }

    @Test fun `ports`() {
        assertEquals(9001, ManualReceiverInput.parsePort(" 9001 "))
        assertNull(ManualReceiverInput.parsePort("0"))
        assertNull(ManualReceiverInput.parsePort("65536"))
        assertNull(ManualReceiverInput.parsePort("abc"))
    }

    @Test fun `the four receiver types are offered`() {
        assertEquals(listOf(ExternalReceiverProfile.GENERIC_NMEA_TCP, ExternalReceiverProfile.REACH_RS2_PLUS,
            ExternalReceiverProfile.REACH_RS4, ExternalReceiverProfile.REACH_RS4_PRO), ManualReceiverInput.types)
    }
}
