package com.example.surveyingapp.domain.repository

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.gnss.reach.ReachBatteryService
import com.example.surveyingapp.gnss.reach.ReachCorrectionsInfo
import com.example.surveyingapp.gnss.reach.ReachCorrectionsService
import com.example.surveyingapp.gnss.reach.ReachDeviceService
import com.example.surveyingapp.gnss.reach.ReachHttpClient
import com.example.surveyingapp.gnss.settings.SourceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
    val storage: StorageInfo? = null          // Storage information
)

data class StorageInfo(
    val totalMB: Long,
    val usedMB: Long,
    val availableMB: Long
) {
    val usagePercent: Int get() = ((usedMB.toDouble() / totalMB) * 100).toInt()
}


enum class DeviceConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    TIMEOUT
}

enum class DeviceCommand {
    REBOOT,
    SHUTDOWN,
    RESTART_GNSS,
    CLEAR_LOGS,
    FACTORY_RESET
}

@Singleton
class ReachDeviceRepository @Inject constructor(
    private val sourceSettings: SourceSettings
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _batteryInfo = MutableStateFlow<ReachBatteryInfo?>(null)
    val batteryInfo: StateFlow<ReachBatteryInfo?> = _batteryInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<ReachDeviceInfo?>(null)
    val deviceInfo: StateFlow<ReachDeviceInfo?> = _deviceInfo.asStateFlow()

    private val _correctionsInfo = MutableStateFlow<ReachCorrectionsInfo?>(null)
    val correctionsInfo: StateFlow<ReachCorrectionsInfo?> = _correctionsInfo.asStateFlow()

    private val _connectionStatus = MutableStateFlow(DeviceConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<DeviceConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var isPolling = false
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

    private suspend fun startPolling() {
        isPolling = true
        scope.launch { pollBattery() }
        scope.launch { pollDevice() }
        startCorrectionsSocket()
    }

    private fun stopPolling() {
        isPolling = false
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

    private suspend fun pollBattery() {
        while (isPolling) {
            val ip = getReachIp()
            if (ip == null) {
                _batteryInfo.value = null
            } else {
                val client = ReachHttpClient(ip)
                val service = ReachBatteryService(client)
                val battery = runCatching { service.read() }.getOrNull()
                _batteryInfo.value = battery?.let {
                    ReachBatteryInfo(
                        percent           = it.percent,
                        voltageV          = it.voltageV,
                        currentA          = it.currentA,
                        temperatureC      = it.temperatureC,
                        chargerStatus     = it.chargerStatus,
                        otg               = it.otg,
                        usbChargerCurrentA = it.usbChargerCurrentA,
                        usbChargerVoltageV = it.usbChargerVoltageV
                    )
                }
            }
            delay(15_000) // Poll every 15 seconds
        }
    }

    private suspend fun pollDevice() {
        while (isPolling) {
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

                val storageInfo: StorageInfo? = if (device?.storageTotalMb != null) {
                    val total = device.storageTotalMb
                    val free  = device.storageFreeMb ?: 0L
                    StorageInfo(totalMB = total, usedMB = total - free, availableMB = free)
                } else null

                _deviceInfo.value = ReachDeviceInfo(
                    ip                   = ip,
                    name                 = device?.name.blankToNull(),
                    model                = device?.model.blankToNull(),
                    firmware             = device?.firmware.blankToNull(),
                    serial               = device?.serial.blankToNull(),
                    uptimeSec            = parseUptimeToSeconds(device?.uptime),
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

    /**
     * Parses RS2+ uptime string "2 days, 1:08:08" into seconds.
     * Returns null if parsing fails.
     */
    private fun parseUptimeToSeconds(uptimeStr: String?): Long? {
        if (uptimeStr == null) return null
        return try {
            var totalSeconds = 0L

            // Handle days (e.g., "2 days, ")
            val daysMatch = Regex("""(\d+)\s+days?""").find(uptimeStr)
            if (daysMatch != null) {
                totalSeconds += daysMatch.groupValues[1].toLong() * 86400
            }

            // Handle time portion (e.g., "1:08:08")
            val timeMatch = Regex("""(\d+):(\d+):(\d+)""").find(uptimeStr)
            if (timeMatch != null) {
                val hours = timeMatch.groupValues[1].toLong()
                val minutes = timeMatch.groupValues[2].toLong()
                val seconds = timeMatch.groupValues[3].toLong()
                totalSeconds += hours * 3600 + minutes * 60 + seconds
            }

            totalSeconds
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getReachIp(): String? {
        return runCatching {
            SurveyingApp.settingsRepo.externalTcpHost.first()
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
        _connectionStatus.value = DeviceConnectionStatus.CONNECTING
        return try {
            val client = ReachHttpClient(ip)
            val service = ReachDeviceService(client)
            val device = service.read()

            _connectionStatus.value = DeviceConnectionStatus.CONNECTED
            _lastError.value = null
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            _connectionStatus.value = DeviceConnectionStatus.ERROR
            _lastError.value = "Connection failed: ${e.message}"
            false
        }
    }

    suspend fun disconnectDevice() {
        stopPolling()
        _connectionStatus.value = DeviceConnectionStatus.DISCONNECTED
        _batteryInfo.value = null
        _deviceInfo.value = null
    }

    // Device control commands
    suspend fun sendCommand(command: DeviceCommand): Boolean {
        val ip = getReachIp() ?: return false
        return try {
            val client = ReachHttpClient(ip)
            when (command) {
                DeviceCommand.REBOOT -> {
                    // Implementation for device reboot
                    true
                }
                DeviceCommand.SHUTDOWN -> {
                    // Implementation for device shutdown
                    true
                }
                DeviceCommand.RESTART_GNSS -> {
                    // Implementation for GNSS restart
                    true
                }
                DeviceCommand.CLEAR_LOGS -> {
                    // Implementation for clearing device logs
                    true
                }
                DeviceCommand.FACTORY_RESET -> {
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
        _connectionStatus.value = DeviceConnectionStatus.CONNECTING

        val ip = getReachIp()
        if (ip != null && connectToDevice(ip)) {
            reconnectAttempts = 0
        } else {
            delay(5000) // Wait 5 seconds before next attempt
            if (reconnectAttempts < maxReconnectAttempts) {
                attemptReconnection()
            } else {
                _connectionStatus.value = DeviceConnectionStatus.ERROR
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
