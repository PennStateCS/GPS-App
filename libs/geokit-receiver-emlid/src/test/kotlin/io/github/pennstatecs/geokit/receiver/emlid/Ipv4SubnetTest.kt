package io.github.pennstatecs.geokit.receiver.emlid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4SubnetTest {

    private fun ip(s: String) = Ipv4Subnet.u32ToInt(Ipv4Subnet.ipv4ToU32(s)!!)

    @Test fun `DhcpInfo little-endian ints convert to dotted strings`() {
        // 192.168.1.20 packed little-endian (DhcpInfo.ipAddress): 0x1401A8C0
        assertEquals("192.168.1.20", Ipv4Subnet.littleEndianIntToString(0x1401A8C0))
        assertEquals("255.255.255.0", Ipv4Subnet.littleEndianIntToString(0x00FFFFFF))
        assertEquals("0.0.0.0", Ipv4Subnet.littleEndianIntToString(0))
    }

    @Test fun `a slash 24 sweeps hosts 1 to 254`() {
        val (net, start, end) = Ipv4Subnet.computeSubnet("192.168.1.20", "255.255.255.0")!!
        assertEquals("192.168.1.0", net)
        assertEquals(ip("192.168.1.1"), start)
        assertEquals(ip("192.168.1.254"), end)
        assertEquals("192.168.1.254", Ipv4Subnet.intToInet4StringBE(end))
    }

    @Test fun `addresses above 127 do not go negative`() {
        val (net, start, end) = Ipv4Subnet.computeSubnet("200.1.2.3", "255.255.255.0")!!
        assertEquals("200.1.2.0", net)
        assertEquals(253, end - start)
    }

    @Test fun `a zero or unparsable mask gives no subnet`() {
        assertNull(Ipv4Subnet.computeSubnet("192.168.1.20", "0.0.0.0"))
        assertNull(Ipv4Subnet.computeSubnet("not an ip", "255.255.255.0"))
    }

    @Test fun `missing or too-broad masks fall back to slash 24`() {
        assertEquals("255.255.255.0", Ipv4Subnet.deriveEffectiveMask(null, "255.255.0.0"))
        assertEquals("255.255.255.0", Ipv4Subnet.deriveEffectiveMask("10.0.0.5", null))
        assertEquals("255.255.255.0", Ipv4Subnet.deriveEffectiveMask("10.0.0.5", "0.0.0.0"))
        assertEquals("255.255.255.0", Ipv4Subnet.deriveEffectiveMask("10.0.0.5", "255.0.0.0"))   // /8
        assertEquals("255.255.240.0", Ipv4Subnet.deriveEffectiveMask("10.0.0.5", "255.255.240.0")) // /20 kept
    }

    @Test fun `ranges over 512 hosts are cut to the first slash 24`() {
        val s = Ipv4Subnet.computeSubnet("10.0.5.9", "255.255.240.0")!!   // /20: 4094 hosts
        val (net, start, end) = Ipv4Subnet.capScanRangeIfTooLarge(s)
        assertEquals("10.0.0.0", net)
        assertEquals(ip("10.0.0.1"), start)
        assertEquals(ip("10.0.0.254"), end)
        val small = Ipv4Subnet.computeSubnet("10.0.5.9", "255.255.254.0")!!  // /23: 510 hosts, kept
        assertEquals(small, Ipv4Subnet.capScanRangeIfTooLarge(small))
    }
}
