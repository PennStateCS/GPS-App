package io.github.pennstatecs.geokit.receiver.emlid

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IPv4 helpers for the Reach subnet sweep. Unsigned 32-bit maths in `Long`, so addresses above
 * 127.255.255.255 don't go negative.
 */
public object Ipv4Subnet {

    /**
     * A little-endian packed address (as Android's `DhcpInfo.ipAddress` / `netmask` give it) as a
     * dotted string; "0.0.0.0" if it can't be converted.
     */
    public fun littleEndianIntToString(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /** Convert big-endian host int to IPv4 string (for enumerating addresses). */
    internal fun intToInet4StringBE(value: Int): String {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Compute network range from local IP + netmask strings.
     * Returns (networkString, startHostIntBE, endHostIntBE).
     * Uses unsigned 32-bit math to avoid sign issues.
     */
    internal fun computeSubnet(localIp: String, mask: String): Triple<String, Int, Int>? {
        val ipU = ipv4ToU32(localIp) ?: return null
        val maskU = ipv4ToU32(mask) ?: return null
        if (maskU == 0L) return null

        val networkU = ipU and maskU
        val broadcastU = networkU or (maskU.inv() and 0xFFFFFFFFL)

        // If there are < 2 host addresses, just return the network as both
        val hasHosts = (broadcastU - networkU) >= 2
        val startU = if (hasHosts) networkU + 1 else networkU
        val endU = if (hasHosts) broadcastU - 1 else broadcastU

        val networkStr = u32ToIpv4(networkU)
        val startInt = u32ToInt(startU)
        val endInt = u32ToInt(endU)

        return if (startInt <= endInt) Triple(networkStr, startInt, endInt) else Triple(networkStr, endInt, startInt)
    }

    // ---- tiny IPv4 helpers (unsigned 32-bit) ----

    internal fun ipv4ToU32(ip: String): Long? = try {
        val b = InetAddress.getByName(ip).address
        if (b.size != 4) null else
            ((b[0].toLong() and 0xFF) shl 24) or
                    ((b[1].toLong() and 0xFF) shl 16) or
                    ((b[2].toLong() and 0xFF) shl 8) or
                    (b[3].toLong() and 0xFF)
    } catch (_: Exception) { null }

    internal fun u32ToIpv4(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    internal fun u32ToInt(v: Long): Int = (v and 0xFFFFFFFFL).toInt()

    /** Ensure a reasonable mask if DHCP gives 0.0.0.0 or something extremely broad (e.g. /8). */
    internal fun deriveEffectiveMask(ip: String?, rawMask: String?): String {
        if (ip == null) return "255.255.255.0"
        val mask = when {
            rawMask == null || rawMask == "0.0.0.0" -> "255.255.255.0"
            else -> rawMask
        }
        val bits = ipv4ToU32(mask)?.let { java.lang.Long.bitCount(it) } ?: 24
        return if (bits < 20) "255.255.255.0" else mask
    }

    internal fun capScanRangeIfTooLarge(subnet: Triple<String, Int, Int>): Triple<String, Int, Int> {
        val (network, start, end) = subnet
        val hostCount = (end - start + 1).coerceAtLeast(0)
        // If more than 512 hosts, cap to first /24 slice
        return if (hostCount > 512) {
            val startIp = intToInet4StringBE(start)
            val octets = startIp.split('.')
            if (octets.size == 4) {
                val base = "${octets[0]}.${octets[1]}.${octets[2]}."
                val newStart = ipv4ToU32(base + "1")?.let { u32ToInt(it) } ?: start
                val newEnd = ipv4ToU32(base + "254")?.let { u32ToInt(it) } ?: end
                Triple("${octets[0]}.${octets[1]}.${octets[2]}.0", newStart, newEnd)
            } else subnet
        } else subnet
    }
}
