package app.surrealar.gnss.external.model

/**
 * App-facing models for the external Reach/RS2+ receiver subsystem.
 *
 * These are the normalized, UI/repository-facing types exposed by
 * [app.surrealar.gnss.external.repository.ReachDeviceRepository]. They are deliberately
 * distinct from the service-layer JSON DTOs (e.g. `ReachDeviceInfoDto`, `BatteryStatus`) which the
 * services parse and then map into these models. Names are `Reach`-prefixed to avoid collisions
 * with generic types when sharing the `gnss.external` package tree.
 */

data class ReachBatteryInfo(
    val percent: Int,
    val voltageV: Double?,
    val currentA: Double?,
    val temperatureC: Double?,
    val chargerStatus: String?,
    val otg: Boolean? = null,
    val usbChargerCurrentA: Double? = null,
    val usbChargerVoltageV: Double? = null
)

data class ReachDeviceInfo(
    val ip: String?,
    val name: String?,
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val uptimeSec: Long?,
    val uptime: String? = null,               // Formatted uptime string
    val gnssReceiverFirmware: String? = null, // GNSS receiver chip firmware (e.g. "HPG_1.50")
    val hostname: String? = null,             // mDNS hostname e.g. "Reach6.local"
    val rawResponse: String? = null,          // First 300 chars of HTTP response (diagnostics)
    val storage: ReachStorageInfo? = null     // Storage information
)

data class ReachStorageInfo(
    val totalMB: Long,
    val usedMB: Long,
    val availableMB: Long
) {
    val usagePercent: Int get() = ((usedMB.toDouble() / totalMB) * 100).toInt()
}

/**
 * Live correction status pushed from the RS2+ via socket.io broadcast events
 * (parsed by [app.surrealar.gnss.external.ReachCorrectionsService]).
 */
data class ReachCorrectionsInfo(
    val isReceiving: Boolean,
    val channel: String?,
    val satellitesInView: Int?,
    val ageSeconds: Double?,
    val baselineKm: Double?,
    val baseLatDeg: Double?,
    val baseLonDeg: Double?,
    val baseAltM: Double?,
    val solution: String?
)

/** Connection state to the Reach device's HTTP/socket API (distinct from the GNSS fix's RTK status). */
enum class ReachConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    TIMEOUT
}

/**
 * Control commands sent to the Reach device. Several are destructive or disruptive — `SHUTDOWN`,
 * `FACTORY_RESET`, `CLEAR_LOGS`, and `RESTART_GNSS`/`REBOOT` interrupt surveying — so callers should
 * confirm with the user before issuing them.
 */
enum class ReachDeviceCommand {
    REBOOT,
    SHUTDOWN,
    RESTART_GNSS,
    CLEAR_LOGS,
    FACTORY_RESET
}
