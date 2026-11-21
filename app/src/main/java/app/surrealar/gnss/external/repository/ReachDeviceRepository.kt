package app.surrealar.gnss.external.repository

import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import app.surrealar.gnss.external.ReachBatteryService
import app.surrealar.gnss.external.ReachCorrectionsService
import app.surrealar.gnss.external.ReachDeviceService
import app.surrealar.gnss.external.ReachHttpClient
import app.surrealar.gnss.external.model.ReachDeviceCommand
import app.surrealar.gnss.external.model.ReachBatteryInfo
import app.surrealar.gnss.external.model.ReachConnectionStatus
import app.surrealar.gnss.external.model.ReachCorrectionsInfo
import app.surrealar.gnss.external.model.ReachDeviceInfo
import app.surrealar.gnss.external.model.ReachStorageInfo
import android.util.Log
import app.surrealar.gnss.source.SourceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton owner of Emlid Reach device state (info, storage, connection, battery) over its HTTP API,
 * keyed off the configured connection profile in [SourceSettings]/`ExternalReceiverSettingsRepository`.
 * Polls/exposes device data on an IO scope; it does not handle the NMEA fix stream (that is the
 * external NMEA source). Network failures surface as state rather than throwing.
 */
private const val TAG = "ReachDeviceRepo"

@Singleton
class ReachDeviceRepository @Inject constructor(
    private val sourceSettings: SourceSettings,
    private val settingsRepository: ExternalReceiverSettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _batteryInfo = MutableStateFlow<ReachBatteryInfo?>(null)
    val batteryInfo: StateFlow<ReachBatteryInfo?> = _batteryInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<ReachDeviceInfo?>(null)
    val deviceInfo: StateFlow<ReachDeviceInfo?> = _deviceInfo.asStateFlow()

    private val _correctionsInfo = MutableStateFlow<ReachCorrectionsInfo?>(null)
    val correctionsInfo: StateFlow<ReachCorrectionsInfo?> = _correctionsInfo.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ReachConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ReachConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Poll loops are held as Jobs so stopPolling() actually ends them (a loop sleeping in delay()
    // would otherwise outlive a quick EXTERNAL → INTERNAL → EXTERNAL switch and run twice).
    private var pollBatteryJob: Job? = null
    private var pollDeviceJob: Job? = null
    private val isPolling: Boolean get() = pollBatteryJob?.isActive == true
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private var correctionsService: ReachCorrectionsService? = null

    init {
        scope.launch {
            sourceSettings.activeProvider
                .collectLatest { provider ->
                    val shouldPoll = provider == SourceSettings.ProviderChoice.EXTERNAL_TCP
                    if (shouldPoll && !isPolling) {
                        startPolling()
                    } else if (!shouldPoll) {
                        stopPolling()
                    }
                }
        }
    }

    private fun startPolling() {
        if (isPolling) return                      // already running — never start a second loop
        Log.d(TAG, "Reach polling started")
        pollBatteryJob = scope.launch { pollBattery() }
        if (pollDeviceJob?.isActive != true) pollDeviceJob = scope.launch { pollDevice() }
        startCorrectionsSocket()
    }

    private fun stopPolling() {
        if (pollBatteryJob?.isActive == true || pollDeviceJob?.isActive == true) {
            Log.d(TAG, "Reach polling stopped")
        }
        pollBatteryJob?.cancel(); pollBatteryJob = null
        pollDeviceJob?.cancel(); pollDeviceJob = null
        stopCorrectionsSocket()
        _batteryInfo.value = null
        _deviceInfo.value = null
        _correctionsInfo.value = null
    }

    private fun startCorrectionsSocket() {
        scope.launch {
            val ip = getReachIp() ?: return@launch

            stopCorrectionsSocket()

            val svc = ReachCorrectionsService(ip)
            correctionsService = svc
            svc.connect()

            // Forward the service's StateFlow into the repository's own StateFlow so
            // existing observers of correctionsInfo continue to work unchanged.
            svc.correctionsInfo.collect { info ->
                _correctionsInfo.value = info
            }
        }
    }

    private fun stopCorrectionsSocket() {
        correctionsService?.disconnect()
        correctionsService = null
    }

    /**
     * The app's only battery poller (toolbar, Settings and the Developer page all collect
     * [batteryInfo]). 15 s between successful reads; on failure back off 5 s → 15 s → 30 s, and
     * after 3 consecutive failures publish null (UI hides the battery) while retrying every 30 s.
     */
    private suspend fun pollBattery() {
        var lastIp: String? = null
        var service: ReachBatteryService? = null
        var failures = 0
        // setActiveProvider(EXTERNAL_TCP) can fire before the host's DataStore write lands, so the
        // first reads may see no IP: retry every ~1 s for 5 misses, then every 60 s.
        var blankIpCount = 0

        while (currentCoroutineContext().isActive) {
            val ip = getReachIp()
            if (ip == null) {
                _batteryInfo.value = null
                delay(if (blankIpCount++ < 5) 1_000L else 60_000L)
                continue
            }
            blankIpCount = 0

            if (ip != lastIp || service == null) {
                // Short timeouts: /battery doesn't exist on all firmware, so the /status fallback
                // costs at most ~3 s instead of the default 8 s per endpoint.
                service = ReachBatteryService(
                    ReachHttpClient(ip, connectTimeoutMs = 1500, readTimeoutMs = 1500)
                )
                if (lastIp != null) Log.d(TAG, "Battery IP changed to $ip")
                lastIp = ip
                failures = 0
            }

            val battery = try {
                service.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A blocking HTTP read can finish after stopPolling() cancelled us — don't republish.
            if (!currentCoroutineContext().isActive) return

            if (battery != null) {
                failures = 0
                _batteryInfo.value = ReachBatteryInfo(
                    percent            = battery.percent,
                    voltageV           = battery.voltageV,
                    currentA           = battery.currentA,
                    temperatureC       = battery.temperatureC,
                    chargerStatus      = battery.chargerStatus,
                    otg                = battery.otg,
                    usbChargerCurrentA = battery.usbChargerCurrentA,
                    usbChargerVoltageV = battery.usbChargerVoltageV,
                    isCharging         = battery.isCharging
                )
                delay(15_000)
            } else {
                failures++
                if (failures == 3) Log.w(TAG, "Battery read failed 3 times for $ip — hiding battery")
                if (failures >= 3) _batteryInfo.value = null
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

    private suspend fun pollDevice() {
        while (currentCoroutineContext().isActive) {
            val ip = getReachIp()
            if (ip == null) {
                _deviceInfo.value = null
            } else {
                val client = ReachHttpClient(ip)
                val service = ReachDeviceService(client)
                val result = runCatching { service.read() }
                val device = result.getOrNull()
                val errorMsg = result.exceptionOrNull()?.message

                fun String?.blankToNull() = takeIf { !it.isNullOrBlank() }

                val storageInfo: ReachStorageInfo? = if (device?.storageTotalMb != null) {
                    val total = device.storageTotalMb
                    val free  = device.storageFreeMb ?: 0L
                    ReachStorageInfo(totalMB = total, usedMB = total - free, availableMB = free)
                } else null

                _deviceInfo.value = ReachDeviceInfo(
                    ip                   = ip,
                    name                 = device?.name.blankToNull(),
                    model                = device?.model.blankToNull(),
                    firmware             = device?.firmware.blankToNull(),
                    serial               = device?.serial.blankToNull(),
                    uptimeSec            = device?.uptimeSec,
                    uptime               = device?.uptime.blankToNull(),
                    gnssReceiverFirmware = device?.gnssReceiverFirmware.blankToNull(),
                    hostname             = device?.hostname.blankToNull(),
                    storage              = storageInfo,
                    rawResponse          = device?.rawResponse
                        ?: errorMsg?.let { "ERROR: $it" }
                )
            }
            delay(30_000) // Poll every 30 seconds
        }
    }

    private suspend fun getReachIp(): String? {
        return runCatching {
            settingsRepository.externalTcpHost.first()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // Device discovery and connection management
    suspend fun discoverDevices(): List<ReachDeviceInfo> {
        _isDiscovering.value = true
        return try {
            // Implementation would scan network for Reach devices
            // This is a placeholder for the actual discovery logic
            emptyList()
        } catch (e: Exception) {
            _lastError.value = "Discovery failed: ${e.message}"
            emptyList()
        } finally {
            _isDiscovering.value = false
        }
    }

    suspend fun connectToDevice(ip: String): Boolean {
        _connectionStatus.value = ReachConnectionStatus.CONNECTING
        return try {
            val client = ReachHttpClient(ip)
            val service = ReachDeviceService(client)
            val device = service.read()

            _connectionStatus.value = ReachConnectionStatus.CONNECTED
            _lastError.value = null
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            _connectionStatus.value = ReachConnectionStatus.ERROR
            _lastError.value = "Connection failed: ${e.message}"
            false
        }
    }

    suspend fun disconnectDevice() {
        stopPolling()
        _connectionStatus.value = ReachConnectionStatus.DISCONNECTED
        _batteryInfo.value = null
        _deviceInfo.value = null
    }

    // Device control commands
    suspend fun sendCommand(command: ReachDeviceCommand): Boolean {
        val ip = getReachIp() ?: return false
        return try {
            val client = ReachHttpClient(ip)
            when (command) {
                ReachDeviceCommand.REBOOT -> {
                    // Implementation for device reboot
                    true
                }
                ReachDeviceCommand.SHUTDOWN -> {
                    // Implementation for device shutdown
                    true
                }
                ReachDeviceCommand.RESTART_GNSS -> {
                    // Implementation for GNSS restart
                    true
                }
                ReachDeviceCommand.CLEAR_LOGS -> {
                    // Implementation for clearing device logs
                    true
                }
                ReachDeviceCommand.FACTORY_RESET -> {
                    // Implementation for factory reset
                    true
                }
            }
        } catch (e: Exception) {
            _lastError.value = "Command failed: ${e.message}"
            false
        }
    }

    // Device health monitoring
    fun isDeviceHealthy(): Boolean {
        val battery = _batteryInfo.value
        val device = _deviceInfo.value
        return when {
            battery == null || device == null -> false
            battery.percent < 10 -> false
            battery.temperatureC?.let { it > 60 } == true -> false
            device.storage?.usagePercent?.let { it > 90 } == true -> false
            else -> true
        }
    }

    fun getBatteryStatus(): String {
        val battery = _batteryInfo.value ?: return "Unknown"
        return when {
            battery.percent >= 80 -> "Excellent"
            battery.percent >= 50 -> "Good"
            battery.percent >= 20 -> "Low"
            else -> "Critical"
        }
    }


    // Auto-reconnection logic
    private suspend fun attemptReconnection() {
        if (reconnectAttempts >= maxReconnectAttempts) return

        reconnectAttempts++
        _connectionStatus.value = ReachConnectionStatus.CONNECTING

        val ip = getReachIp()
        if (ip != null && connectToDevice(ip)) {
            reconnectAttempts = 0
        } else {
            delay(5000) // Wait 5 seconds before next attempt
            if (reconnectAttempts < maxReconnectAttempts) {
                attemptReconnection()
            } else {
                _connectionStatus.value = ReachConnectionStatus.ERROR
                _lastError.value = "Failed to reconnect after $maxReconnectAttempts attempts"
            }
        }
    }

    // Utility methods
    fun formatUptime(uptimeSec: Long?): String {
        if (uptimeSec == null) return "Unknown"

        val days = uptimeSec / 86400
        val hours = (uptimeSec % 86400) / 3600
        val minutes = (uptimeSec % 3600) / 60

        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
