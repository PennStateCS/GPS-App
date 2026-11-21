package app.surrealar.receiver.emlid

import app.surrealar.gnss.external.BatteryStatus
import app.surrealar.gnss.external.ReachBatteryService
import app.surrealar.gnss.external.ReachCorrectionsService
import app.surrealar.gnss.external.ReachDeviceInfoDto
import app.surrealar.gnss.external.ReachDeviceService
import app.surrealar.gnss.external.ReachHttpClient
import app.surrealar.gnss.external.model.ReachCorrectionsInfo
import app.surrealar.gnss.log.GnssLog
import app.surrealar.gnss.receiver.ReceiverBattery
import app.surrealar.gnss.receiver.ReceiverCapabilities
import app.surrealar.gnss.receiver.ReceiverCorrections
import app.surrealar.gnss.receiver.ReceiverDevice
import app.surrealar.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Emlid Reach RS2+ / RS4 / RS4 Pro status over the receiver's HTTP API and socket.io broadcasts.
 *
 * Same behaviour as the former ReachDeviceRepository, for one host per [start]:
 * - **Battery**: the app's only battery poller. 15 s between successful reads; on failure back off
 *   5 s → 15 s → 30 s, and after 3 consecutive failures publish null (UI hides the battery) while
 *   retrying every 30 s. Short HTTP timeouts (1.5 s) because /battery is missing on some firmware.
 * - **Device info**: every 30 s.
 * - **Corrections**: pushed by the receiver's socket.io `broadcast` events.
 *
 * Not thread-safe: call [start] / [stop] from one coroutine (ReceiverDeviceManager does).
 */
class EmlidReachDevice(
    private val api: EmlidApi = EmlidApi.Http,
    private val newScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
) : ReceiverDevice {

    override val capabilities: ReceiverCapabilities = ReceiverCapabilities.ALL

    private val _battery = MutableStateFlow<ReceiverBattery?>(null)
    override val battery: StateFlow<ReceiverBattery?> = _battery.asStateFlow()

    private val _deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
    override val deviceInfo: StateFlow<ReceiverDeviceInfo?> = _deviceInfo.asStateFlow()

    private val _corrections = MutableStateFlow<ReceiverCorrections?>(null)
    override val corrections: StateFlow<ReceiverCorrections?> = _corrections.asStateFlow()

    private var scope: CoroutineScope? = null
    @Volatile private var correctionsLink: EmlidApi.CorrectionsLink? = null

    /** True while poll loops are running (tests: no loops may outlive [stop]). */
    val isRunning: Boolean get() = scope?.coroutineContext?.get(Job)?.isActive == true

    override fun start(host: String) {
        if (isRunning) stop()
        GnssLog.d(TAG, "Reach polling started")
        val s = newScope()
        scope = s
        s.launch { pollBattery(host) }
        s.launch { pollDevice(host) }
        s.launch { listenForCorrections(host) }
    }

    override fun stop() {
        val s = scope ?: return
        GnssLog.d(TAG, "Reach polling stopped")
        s.cancel()
        scope = null
        correctionsLink?.disconnect()
        correctionsLink = null
        _battery.value = null
        _deviceInfo.value = null
        _corrections.value = null
    }

    private suspend fun listenForCorrections(host: String) {
        val link = api.corrections(host)
        correctionsLink = link
        try {
            link.connect()
            link.info.collect { info -> _corrections.value = info?.let(EmlidMapping::corrections) }
        } finally {
            // stop() disconnects synchronously; this covers a stop() that raced the line above.
            if (correctionsLink === link) { correctionsLink = null; link.disconnect() }
        }
    }

    private suspend fun pollBattery(ip: String) {
        val read = api.batteryReader(ip)
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val battery = try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A blocking HTTP read can finish after stop() cancelled us — don't republish.
            if (!currentCoroutineContext().isActive) return

            if (battery != null) {
                failures = 0
                _battery.value = EmlidMapping.battery(battery)
                delay(15_000)
            } else {
                failures++
                if (failures == 3) GnssLog.w(TAG, "Battery read failed 3 times for $ip — hiding battery")
                if (failures >= 3) _battery.value = null
                delay(
                    when (failures) {
                        1    -> 5_000L
                        2    -> 15_000L
                        else -> 30_000L
                    }
                )
            }
        }
    }

    private suspend fun pollDevice(ip: String) {
        val read = api.deviceReader(ip)
        while (currentCoroutineContext().isActive) {
            val result = runCatching { read() }
            if (result.exceptionOrNull() is CancellationException) return
            if (!currentCoroutineContext().isActive) return
            _deviceInfo.value = EmlidMapping.deviceInfo(ip, result.getOrNull(), result.exceptionOrNull()?.message)
            delay(30_000) // Poll every 30 seconds
        }
    }

    /** The receiver's HTTP / socket.io endpoints, injectable for tests. */
    interface EmlidApi {
        fun batteryReader(host: String): suspend () -> BatteryStatus?
        fun deviceReader(host: String): suspend () -> ReachDeviceInfoDto?
        fun corrections(host: String): CorrectionsLink

        interface CorrectionsLink {
            val info: StateFlow<ReachCorrectionsInfo?>
            fun connect()
            fun disconnect()
        }

        /** The real receiver. */
        object Http : EmlidApi {
            override fun batteryReader(host: String): suspend () -> BatteryStatus? {
                // Short timeouts: /battery doesn't exist on all firmware, so the /status fallback
                // costs at most ~3 s instead of the default 8 s per endpoint.
                val service = ReachBatteryService(ReachHttpClient(host, connectTimeoutMs = 1500, readTimeoutMs = 1500))
                return { service.read() }
            }

            override fun deviceReader(host: String): suspend () -> ReachDeviceInfoDto? {
                val service = ReachDeviceService(ReachHttpClient(host))
                return { service.read() }
            }

            override fun corrections(host: String): CorrectionsLink {
                val svc = ReachCorrectionsService(host)
                return object : CorrectionsLink {
                    override val info: StateFlow<ReachCorrectionsInfo?> = svc.correctionsInfo
                    override fun connect() = svc.connect()
                    override fun disconnect() = svc.disconnect()
                }
            }
        }
    }

    private companion object {
        /** Same tag the former ReachDeviceRepository logged under. */
        const val TAG = "ReachDeviceRepo"
    }
}

/** Reach service results → the receiver-neutral models, keeping every field (extras in `details`). */
object EmlidMapping {

    /** Detail labels (also the Developer page's row labels). */
    const val USB_CHARGER_CURRENT = "USB Charger Current"
    const val USB_CHARGER_VOLTAGE = "USB Charger Voltage"
    const val OTG_POWER = "OTG Power"
    const val GNSS_RECEIVER_FW = "GNSS Receiver FW"
    const val RAW_RESPONSE = "Raw response"

    fun battery(b: BatteryStatus): ReceiverBattery = ReceiverBattery(
        percent = b.percent,
        isCharging = b.isCharging,
        voltageV = b.voltageV,
        temperatureC = b.temperatureC,
        currentA = b.currentA,
        chargerStatus = b.chargerStatus,
        details = buildMap {
            // Formatted exactly as the Developer page formatted these fields before.
            b.usbChargerCurrentA?.let { put(USB_CHARGER_CURRENT, "%.2f A".format(it)) }
            b.usbChargerVoltageV?.let { put(USB_CHARGER_VOLTAGE, "%.2f V".format(it)) }
            b.otg?.let { put(OTG_POWER, if (it) "Enabled" else "Disabled") }
        },
    )

    fun deviceInfo(ip: String, device: ReachDeviceInfoDto?, errorMessage: String?): ReceiverDeviceInfo {
        fun String?.blankToNull() = takeIf { !it.isNullOrBlank() }
        val storage = device?.storageTotalMb?.let { total ->
            val free = device.storageFreeMb ?: 0L
            app.surrealar.gnss.receiver.ReceiverStorage(totalMb = total, usedMb = total - free, availableMb = free)
        }
        return ReceiverDeviceInfo(
            host       = ip,
            name       = device?.name.blankToNull(),
            model      = device?.model.blankToNull(),
            firmware   = device?.firmware.blankToNull(),
            serial     = device?.serial.blankToNull(),
            uptimeS    = device?.uptimeSec,
            uptimeText = device?.uptime.blankToNull(),
            hostname   = device?.hostname.blankToNull(),
            storage    = storage,
            details    = buildMap {
                device?.gnssReceiverFirmware.blankToNull()?.let { put(GNSS_RECEIVER_FW, it) }
                (device?.rawResponse ?: errorMessage?.let { "ERROR: $it" })?.let { put(RAW_RESPONSE, it) }
            },
        )
    }

    fun corrections(c: ReachCorrectionsInfo): ReceiverCorrections = ReceiverCorrections(
        isReceiving    = c.isReceiving,
        channel        = c.channel,
        stationId      = null,          // Reach broadcasts don't carry a station ID (GGA does)
        ageS           = c.ageSeconds,
        baselineKm     = c.baselineKm,
        baseLatDeg     = c.baseLatDeg,
        baseLonDeg     = c.baseLonDeg,
        baseHeightM    = c.baseAltM,
        baseSatellites = c.satellitesInView,
        solution       = c.solution,
    )
}
