package app.surrealar.util

data class ReachDevice(
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
}
