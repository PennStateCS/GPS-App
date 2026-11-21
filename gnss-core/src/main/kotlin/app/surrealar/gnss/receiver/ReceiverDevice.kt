package app.surrealar.gnss.receiver

import kotlinx.coroutines.flow.StateFlow

/**
 * A receiver's status channel beyond its NMEA stream: battery, device information and correction
 * link, for whichever of those the receiver reports ([capabilities]). The position stream itself
 * always arrives through NMEA and is not part of this interface.
 *
 * Lifecycle: [start] begins polling/listening for the receiver at [host]; [stop] ends every loop and
 * socket and resets the flows to null. Calling [start] again (e.g. with a new host) after [stop] is
 * allowed. Implementations must make [stop] idempotent and leave no running coroutine behind.
 *
 * Flows hold null while a value is unknown: not started, not reachable, or not supported.
 */
public interface ReceiverDevice {
    /** What this receiver reports; a flow for an unsupported capability stays null. */
    public val capabilities: ReceiverCapabilities

    /** Starts polling the receiver at [host] (IP address or hostname). */
    public fun start(host: String)

    /** Stops all polling and clears the flows. Safe to call when not started. */
    public fun stop()

    /** Latest battery reading, or null. */
    public val battery: StateFlow<ReceiverBattery?>

    /** Latest device information, or null. */
    public val deviceInfo: StateFlow<ReceiverDeviceInfo?>

    /** Latest correction-link status, or null. */
    public val corrections: StateFlow<ReceiverCorrections?>
}

/**
 * Battery state.
 *
 * @property percent state of charge, 0–100 %.
 * @property isCharging true only when the receiver clearly reports charging (never guessed from text).
 * @property voltageV battery voltage, volts.
 * @property temperatureC battery temperature, °C.
 * @property currentA battery current, amperes (sign as reported by the receiver).
 * @property chargerStatus the receiver's own charger status text, for display only.
 * @property details vendor-specific extras as display label → formatted value.
 */
public data class ReceiverBattery(
    val percent: Int,
    val isCharging: Boolean,
    val voltageV: Double? = null,
    val temperatureC: Double? = null,
    val currentA: Double? = null,
    val chargerStatus: String? = null,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Device information.
 *
 * @property host address the app talks to (IP or hostname).
 * @property name user-assigned device name.
 * @property model hardware model.
 * @property firmware main firmware version.
 * @property serial serial number.
 * @property uptimeS time since boot, seconds.
 * @property uptimeText uptime as the receiver formats it, when it reports text rather than seconds.
 * @property hostname network (mDNS) hostname, e.g. "Reach6.local".
 * @property storage on-board storage.
 * @property details vendor-specific extras as display label → value (e.g. GNSS chip firmware).
 */
public data class ReceiverDeviceInfo(
    val host: String? = null,
    val name: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val uptimeS: Long? = null,
    val uptimeText: String? = null,
    val hostname: String? = null,
    val storage: ReceiverStorage? = null,
    val details: Map<String, String> = emptyMap(),
)

/**
 * On-board storage, megabytes.
 *
 * @property totalMb capacity. @property usedMb in use. @property availableMb free.
 */
public data class ReceiverStorage(val totalMb: Long, val usedMb: Long, val availableMb: Long) {
    /** Used share of [totalMb], whole percent. */
    public val usagePercent: Int get() = ((usedMb.toDouble() / totalMb) * 100).toInt()
}

/**
 * Correction (RTK/DGNSS) link status as the receiver reports it.
 *
 * @property isReceiving corrections are arriving.
 * @property channel link name/type as reported (e.g. "NTRIP", "LoRa").
 * @property stationId base/reference station ID, if reported.
 * @property ageS age of the latest corrections, seconds.
 * @property baselineKm distance to the base, kilometres.
 * @property baseLatDeg base latitude, degrees. @property baseLonDeg base longitude, degrees.
 * @property baseHeightM base height, metres, as reported.
 * @property baseSatellites satellites seen at the base.
 * @property solution the receiver's solution status text (e.g. "fix", "float").
 * @property details vendor-specific extras as display label → value.
 */
public data class ReceiverCorrections(
    val isReceiving: Boolean,
    val channel: String? = null,
    val stationId: String? = null,
    val ageS: Double? = null,
    val baselineKm: Double? = null,
    val baseLatDeg: Double? = null,
    val baseLonDeg: Double? = null,
    val baseHeightM: Double? = null,
    val baseSatellites: Int? = null,
    val solution: String? = null,
    val details: Map<String, String> = emptyMap(),
)

/** Finds receivers on the local network. */
public interface ReceiverDiscovery {
    /** Scans for up to [timeoutMs] milliseconds and returns what was found (possibly empty). */
    public suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver>
}

/**
 * A receiver found by [ReceiverDiscovery].
 *
 * @property host IP address or hostname to connect to.
 * @property label name to show (device name or hostname).
 * @property profileKey best guess at the matching receiver profile's stable key, or null if unknown.
 * @property nmeaPort TCP port for the NMEA stream if the scan found one, else null.
 */
public data class DiscoveredReceiver(
    val host: String,
    val label: String,
    val profileKey: String? = null,
    val nmeaPort: Int? = null,
)
