package com.example.surveyingapp.ui.rs2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.service.EmlidBatteryService
import com.example.surveyingapp.service.EmlidDeviceService
import com.example.surveyingapp.util.ReachDiscoveryHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.collectLatest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.first

data class BatteryUi(
    val socPct: Int?, val voltageV: Double?, val tempC: Double?,
    val currentA: Double?, val chargerStatus: String?, val otg: Boolean?
)
data class DeviceUi(
    val ip: String?, val name: String?, val model: String?,
    val firmware: String?, val serial: String?, val uptime: String?
)

class Rs2DeviceViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val batteryService = EmlidBatteryService()
    private val deviceService = EmlidDeviceService()

    private val _battery = MutableStateFlow<BatteryUi?>(null)
    val battery: StateFlow<BatteryUi?> = _battery.asStateFlow()

    private val _device = MutableStateFlow<DeviceUi?>(null)
    val device: StateFlow<DeviceUi?> = _device.asStateFlow()

    private var lastIp: String? = null
    private var lastIpStampMs: Long = 0L
    private val ipCacheMs = 60_000L

    init {
        viewModelScope.launch {
            combine(
                SurveyingApp.settingsRepo.locationSource,
                SurveyingApp.locationManager.statusFlow
            ) { src, st -> src == LocationSourceType.EXTERNAL && st is LocationStatus.Streaming }
                .distinctUntilChanged()
                .collectLatest { shouldPoll ->
                    if (!shouldPoll) {
                        _battery.value = null
                        _device.value = null
                    } else {
                        launch { batteryLoop() }
                        launch { deviceLoop() }
                    }
                }
        }
    }

    private suspend fun batteryLoop() {
        while (true) {
            val ip = resolveIp()
            if (ip == null) _battery.value = null
            else runCatching { batteryService.getBattery(ip) }.getOrNull()?.let { b ->
                _battery.value = BatteryUi(b.stateOfCharge, b.voltageV, b.temperatureC, b.currentA, b.chargerStatus, b.otg)
            }
            delay(15_000)
        }
    }

    private suspend fun deviceLoop() {
        while (true) {
            val ip = resolveIp()
            val info = if (ip == null) null else runCatching { deviceService.getDeviceInfo(ip, 80) }.getOrNull()
            _device.value = DeviceUi(
                ip = ip,
                name = info?.deviceName,
                model = info?.model,
                firmware = info?.firmwareVersion,
                serial = info?.serialNumber,
                uptime = info?.uptime
            )
            delay(30_000)
        }
    }

    private suspend fun resolveIp(): String? {
        val now = System.currentTimeMillis()

        val configured = runCatching { SurveyingApp.settingsRepo.externalTcpHost.firstOrNull() }.getOrNull()
        if (!configured.isNullOrBlank()) { lastIp = configured; lastIpStampMs = now; return configured }

        if (!lastIp.isNullOrBlank() && (now - lastIpStampMs) <= ipCacheMs) return lastIp

        // use the Application as a Context here
        val ctx = getApplication<Application>()
        val discovered = withTimeoutOrNull(4000) {
            ReachDiscoveryHelper.discoverReachDevices(ctx).first().ip
        }
        if (!discovered.isNullOrBlank()) { lastIp = discovered; lastIpStampMs = now }
        return lastIp
    }
}
