package io.github.pennstatecs.geokit.receiver.emlid

import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver

/**
 * An Emlid Reach receiver found on the local network during discovery. [discoveryMethod] records how
 * it was located (e.g. "mdns", "http_sweep"); the `port*Open` flags indicate which service ports
 * responded. Optional fields ([hostname], [wifiSsid]) are null when discovery could not resolve them.
 */
public data class ReachDevice(
    val ip: String,
    val hostname: String? = null,
    val port5000Open: Boolean = false,
    val port9001Open: Boolean = false,
    val discoveryMethod: String, // e.g. "mdns", "http_sweep"
    val wifiSsid: String? = null
) {
    override fun toString(): String = buildString {
        append("ReachDevice(ip=").append(ip)
        hostname?.let { append(", host=").append(it) }
        append(", 5000=").append(port5000Open)
        append(", 9001=").append(port9001Open)
        append(", via=").append(discoveryMethod)
        wifiSsid?.let { append(", ssid=").append(it) }
        append(')')
    }

    /**
     * As a vendor-neutral [DiscoveredReceiver]: labelled by hostname (without `.local`) or IP, NMEA port
     * 9001 when it answered. [profileKey] is the host's guess at the profile; the scan can't tell an
     * RS2+ from an RS4.
     */
    public fun toDiscoveredReceiver(profileKey: String?): DiscoveredReceiver = DiscoveredReceiver(
        host = ip,
        label = hostname?.removeSuffix(".local")?.takeIf { it.isNotBlank() } ?: ip,
        profileKey = profileKey,
        nmeaPort = if (port9001Open) 9001 else null,
    )
}
