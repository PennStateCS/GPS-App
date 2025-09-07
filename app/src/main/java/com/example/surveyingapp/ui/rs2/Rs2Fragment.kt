package com.example.surveyingapp.ui.rs2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.service.EmlidBatteryService
import com.example.surveyingapp.service.EmlidDeviceService
import com.example.surveyingapp.ui.common.SatelliteSignalChartView
import com.example.surveyingapp.ui.common.SkyplotView
import com.example.surveyingapp.util.ReachDiscoveryHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class Rs2Fragment : Fragment() {

    // Charts
    private var chartGps: SatelliteSignalChartView? = null
    private var chartGlonass: SatelliteSignalChartView? = null
    private var chartGalileo: SatelliteSignalChartView? = null
    private var chartBeidou: SatelliteSignalChartView? = null
    private var chartQzss: SatelliteSignalChartView? = null
    private var chartSbas: SatelliteSignalChartView? = null
    private var skyplotView: SkyplotView? = null

    private var rs2ChartJob: Job? = null
    private var rs2InfoJob: Job? = null
    private var batteryJob: Job? = null
    private var deviceDetailsJob: Job? = null

    // short-lived cache to avoid rediscovery every cycle
    private var lastIp: String? = null
    private var lastIpStampMs: Long = 0L
    private val ipCacheMs = 60_000L

    // Track latest used/visible counts from different flows
    private var latestSatsUsed: Int? = null
    private var latestSatsVisible: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.content_settings_rs2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chartGps = view.findViewById(R.id.satellite_chart_gps)
        chartGlonass = view.findViewById(R.id.satellite_chart_glonass)
        chartGalileo = view.findViewById(R.id.satellite_chart_galileo)
        chartBeidou = view.findViewById(R.id.satellite_chart_beidou)
        chartQzss = view.findViewById(R.id.satellite_chart_qzss)
        chartSbas = view.findViewById(R.id.satellite_chart_sbas)
        skyplotView = view.findViewById(R.id.skyplot_view)

        // Apply constellation filters with a small map
        mapOf(
            chartGps to NmeaParser.Constellation.GPS,
            chartGlonass to NmeaParser.Constellation.GLONASS,
            chartGalileo to NmeaParser.Constellation.GALILEO,
            chartBeidou to NmeaParser.Constellation.BEIDOU,
            chartQzss to NmeaParser.Constellation.QZSS,
            chartSbas to NmeaParser.Constellation.SBAS,
        ).forEach { (v, c) -> v?.setConstellationFilter(c) }

        skyplotView?.onSatelliteClick = { sat, isUsed ->
            val constName = when (sat.constellation) {
                NmeaParser.Constellation.GPS -> "GPS"
                NmeaParser.Constellation.GLONASS -> "GLONASS"
                NmeaParser.Constellation.GALILEO -> "Galileo"
                NmeaParser.Constellation.BEIDOU -> "BeiDou"
                NmeaParser.Constellation.QZSS -> "QZSS"
                NmeaParser.Constellation.SBAS -> "SBAS"
                NmeaParser.Constellation.IRNSS -> "IRNSS"
                else -> "Unknown"
            }
            val az = sat.azimuthDeg?.toString() ?: "--"
            val el = sat.elevationDeg?.toString() ?: "--"
            val snr = sat.snrDb?.toString() ?: "--"
            val usedStr = if (isUsed) "Yes" else "No"
            val title = "Satellite ${constPrefix(sat.constellation)}${sat.prn}"
            val msg = "Constellation: $constName\nAzimuth: $az°\nElevation: $el°\nSNR: $snr dB-Hz\nUsed in fix: $usedStr"
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // Label/value views
        val tvLat = view.findViewById<TextView>(R.id.value_latitude)
        val tvLon = view.findViewById<TextView>(R.id.value_longitude)
        val tvAlt = view.findViewById<TextView>(R.id.value_altitude)
        val tvRtk = view.findViewById<TextView>(R.id.value_rtk_status)
        val tvSats = view.findViewById<TextView>(R.id.value_sats_used)
        val tvHdop = view.findViewById<TextView>(R.id.value_hdop)
        val tvHacc = view.findViewById<TextView>(R.id.value_hacc)
        val tvVacc = view.findViewById<TextView>(R.id.value_vacc)
        val tvCorrSrc = view.findViewById<TextView>(R.id.value_corr_source)
        val tvCorrAge = view.findViewById<TextView>(R.id.value_corr_age)
        val tvAltMsl = view.findViewById<TextView>(R.id.value_alt_msl)
        val tvGeoid = view.findViewById<TextView>(R.id.value_geoid_sep)
        val tvPdop = view.findViewById<TextView>(R.id.value_pdop)
        // Battery
        val tvBattSoc = view.findViewById<TextView>(R.id.value_batt_soc)
        val tvBattVolt = view.findViewById<TextView>(R.id.value_batt_voltage)
        val tvBattStat = view.findViewById<TextView>(R.id.value_batt_status)
        val tvBattTemp = view.findViewById<TextView>(R.id.value_batt_temp)
        val tvBattCurr = view.findViewById<TextView>(R.id.value_batt_current)
        val tvBattOtg = view.findViewById<TextView>(R.id.value_batt_otg)
        // Device details
        val tvDevName = view.findViewById<TextView>(R.id.value_dev_name)
        val tvDevModel = view.findViewById<TextView>(R.id.value_dev_model)
        val tvDevFw = view.findViewById<TextView>(R.id.value_dev_firmware)
        val tvDevSerial = view.findViewById<TextView>(R.id.value_dev_serial)
        val tvDevUptime = view.findViewById<TextView>(R.id.value_dev_uptime)
        val tvDevIp = view.findViewById<TextView>(R.id.value_dev_ip)

        fun fmt(v: Double?, decimals: Int = 3): String =
            if (v == null) "--" else String.format(Locale.US, "% .${decimals}f", v).trim()
        fun fmtHdop(v: Double?): String = fmt(v, 1)
        fun fmtLatLon(v: Double?): String =
            if (v == null) "--" else String.format(Locale.US, "%.6f", v)
        fun fmtAgeS(d: kotlin.time.Duration?): String =
            if (d == null) "--" else String.format(Locale.US, "%.1f", d.inWholeMilliseconds / 1000.0)
        fun fmtRtk(s: RtkStatus?): String = s?.name ?: "--"
        fun fmtCorr(c: com.example.surveyingapp.domain.model.CorrectionSource?): String = c?.name ?: "--"

        fun fmtSats(used: Int?, visible: Int?): String {
            val u = used ?: 0
            val v = visible ?: 0
            val u2 = if (u > v && v > 0) v else u
            val v2 = if (v < u2) u2 else v
            return if (u2 > 0 || v2 > 0) "$u2/$v2" else "--"
        }

        fun updateSatsLabel() {
            tvSats?.text = fmtSats(latestSatsUsed, latestSatsVisible)
        }

        // Sky/Charts (debounced so we don’t redraw on every GSV line)
        rs2ChartJob?.cancel()
        rs2ChartJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SurveyingApp.locationManager.skyFlow
                    .debounce(200)
                    .collect { sky ->
                        val sats = sky.satellites
                        val used = sky.usedPrns
                        chartGps?.setSatelliteData(sats, used)
                        chartGlonass?.setSatelliteData(sats, used)
                        chartGalileo?.setSatelliteData(sats, used)
                        chartBeidou?.setSatelliteData(sats, used)
                        chartQzss?.setSatelliteData(sats, used)
                        chartSbas?.setSatelliteData(sats, used)
                        skyplotView?.setSatelliteData(sats, used)

                        // Visible = all satellites from skyFlow (more up-to-date than GGA)
                        latestSatsVisible = sats.size
                        updateSatsLabel()
                    }
            }
        }

        // Fix info panel
        rs2InfoJob?.cancel()
        rs2InfoJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SurveyingApp.locationManager.fixFlow.collect { fix ->
                    tvLat?.text = fmtLatLon(fix.lat)
                    tvLon?.text = fmtLatLon(fix.lon)
                    tvAlt?.text = fmt(fix.altEllipsoidalM, 3)
                    tvRtk?.text = fmtRtk(fix.rtkStatus)
                    // Used from GGA
                    latestSatsUsed = fix.satsUsed
                    // Fallback visible from fix if sky hasn’t reported yet
                    if (latestSatsVisible == null && fix.satsVisible != null) {
                        latestSatsVisible = fix.satsVisible
                    }
                    updateSatsLabel()

                    tvHdop?.text = fmtHdop(fix.hdop)
                    tvPdop?.text = fmtHdop(fix.pdop)
                    tvHacc?.text = fmt(fix.hAccM, 2)
                    tvVacc?.text = fmt(fix.vAccM, 2)
                    tvCorrSrc?.text = fmtCorr(fix.correctionSource)
                    tvCorrAge?.text = fmtAgeS(fix.diffAge)
                    tvAltMsl?.text = fmt(fix.altMslM, 3)
                    tvGeoid?.text = fmt(fix.geoidSeparationM, 3)
                }
            }
        }

        // Gate battery & device polling off flows so we don’t call .first() every loop
        val shouldPollFlow = combine(
            SurveyingApp.settingsRepo.locationSource,
            SurveyingApp.locationManager.statusFlow
        ) { src, st -> src == LocationSourceType.EXTERNAL && st is LocationStatus.Streaming }
            .distinctUntilChanged()

        // Battery polling
        batteryJob?.cancel()
        batteryJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val batteryService = EmlidBatteryService()
                shouldPollFlow.collectLatest { shouldPoll ->
                    if (!shouldPoll) {
                        // clear
                        tvBattSoc?.text = "--"
                        tvBattVolt?.text = "--"
                        tvBattStat?.text = "--"
                        tvBattTemp?.text = "--"
                        tvBattCurr?.text = "--"
                        tvBattOtg?.text = "--"
                        return@collectLatest
                    }
                    // active polling loop while shouldPoll is true
                    while (isActive) {
                        try {
                            val ip = resolveReachIp() // uses cache + settings + discovery
                            if (ip == null) {
                                tvBattSoc?.text = "--"
                                tvBattVolt?.text = "--"
                                tvBattStat?.text = "--"
                                tvBattTemp?.text = "--"
                                tvBattCurr?.text = "--"
                                tvBattOtg?.text = "--"
                            } else {
                                val batt = batteryService.getBattery(ip)
                                if (batt != null) {
                                    tvBattSoc?.text = batt.stateOfCharge?.let { "$it%" } ?: "--"
                                    tvBattVolt?.text = batt.voltageV?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                                    tvBattStat?.text = batt.chargerStatus ?: "--"
                                    tvBattTemp?.text = batt.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
                                    tvBattCurr?.text = batt.currentA?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                                    tvBattOtg?.text = batt.otg?.let { if (it) "Yes" else "No" } ?: "--"
                                } else {
                                    tvBattStat?.text = "--"
                                }
                            }
                        } catch (_: Exception) { /* ignore */ }
                        delay(15_000)
                    }
                }
            }
        }

        // Device details polling
        deviceDetailsJob?.cancel()
        deviceDetailsJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val devService = EmlidDeviceService()
                shouldPollFlow.collectLatest { shouldPoll ->
                    if (!shouldPoll) {
                        tvDevIp?.text = "--"
                        tvDevName?.text = "--"
                        tvDevModel?.text = "--"
                        tvDevFw?.text = "--"
                        tvDevSerial?.text = "--"
                        tvDevUptime?.text = "--"
                        return@collectLatest
                    }
                    while (isActive) {
                        try {
                            val ip = resolveReachIp()
                            if (ip == null) {
                                tvDevIp?.text = "--"
                                tvDevName?.text = "--"
                                tvDevModel?.text = "--"
                                tvDevFw?.text = "--"
                                tvDevSerial?.text = "--"
                                tvDevUptime?.text = "--"
                            } else {
                                tvDevIp?.text = ip
                                val info = devService.getDeviceInfo(ip, 80)
                                if (info != null) {
                                    tvDevName?.text = info.deviceName.ifBlank { "--" }
                                    tvDevModel?.text = info.model ?: "--"
                                    tvDevFw?.text = info.firmwareVersion ?: "--"
                                    tvDevSerial?.text = info.serialNumber ?: "--"
                                    tvDevUptime?.text = info.uptime ?: "--"
                                }
                            }
                        } catch (_: Exception) { /* ignore */ }
                        delay(30_000)
                    }
                }
            }
        }
    }

    // prefer cached IP, then settings, then discovery (with timeout)
    private suspend fun resolveReachIp(): String? {
        val now = System.currentTimeMillis()
        // settings first
        val configured = try { SurveyingApp.settingsRepo.externalTcpHost.first() } catch (_: Exception) { null }
        if (!configured.isNullOrBlank()) {
            lastIp = configured
            lastIpStampMs = now
            return configured
        }
        // recent cache
        if (!lastIp.isNullOrBlank() && (now - lastIpStampMs) <= ipCacheMs) return lastIp
        // discovery fallback
        val discovered = withTimeoutOrNull(4000) {
            ReachDiscoveryHelper.discoverReachDevices(requireContext()).first().ip
        }
        if (!discovered.isNullOrBlank()) {
            lastIp = discovered
            lastIpStampMs = now
        }
        return lastIp
    }

    private fun constPrefix(c: NmeaParser.Constellation): String = when (c) {
        NmeaParser.Constellation.GPS -> "G"
        NmeaParser.Constellation.GLONASS -> "R"
        NmeaParser.Constellation.GALILEO -> "E"
        NmeaParser.Constellation.BEIDOU -> "B"
        NmeaParser.Constellation.QZSS -> "Q"
        NmeaParser.Constellation.SBAS -> "S"
        NmeaParser.Constellation.IRNSS -> "I"
        else -> "?"
    }

    override fun onDestroyView() {
        rs2ChartJob?.cancel()
        rs2InfoJob?.cancel()
        batteryJob?.cancel()
        deviceDetailsJob?.cancel()
        chartGps = null
        chartGlonass = null
        chartGalileo = null
        chartBeidou = null
        chartQzss = null
        chartSbas = null
        skyplotView = null
        super.onDestroyView()
    }
}
