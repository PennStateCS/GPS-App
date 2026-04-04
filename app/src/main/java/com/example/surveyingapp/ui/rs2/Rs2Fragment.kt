package com.example.surveyingapp.ui.rs2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.gnss.model.Constellation
import com.example.surveyingapp.ui.common.SatelliteSignalChartView
import com.example.surveyingapp.ui.common.SkyplotView
import com.example.surveyingapp.util.ReachDiscoveryHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import java.util.Locale
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class Rs2Fragment : Fragment() {

    // Charts
    private var chartGps: SatelliteSignalChartView? = null
    private var chartGlonass: SatelliteSignalChartView? = null
    private var chartGalileo: SatelliteSignalChartView? = null
    private var chartBeidou: SatelliteSignalChartView? = null
    private var chartQzss: SatelliteSignalChartView? = null
    private var chartSbas: SatelliteSignalChartView? = null
    private var skyplotView: SkyplotView? = null

    // Jobs
    private var rs2InfoJob: Job? = null
    private var skyCountJob: Job? = null

    // Short-lived IP cache for discovery
    private var lastIp: String? = null
    private var lastIpStampMs: Long = 0L
    private val ipCacheMs = 60_000L

    // Sats label cache
    private var latestSatsUsed: Int? = null
    private var latestSatsVisible: Int? = null

    // Device/battery VM (Android provides Application context)
    private val devVm: Rs2DeviceViewModel by viewModels()
    // RS2 data ViewModel (Hilt provided)
    private val rs2Vm: Rs2ViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.content_settings_rs2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Views ---
        chartGps = view.findViewById(R.id.satellite_chart_gps)
        chartGlonass = view.findViewById(R.id.satellite_chart_glonass)
        chartGalileo = view.findViewById(R.id.satellite_chart_galileo)
        chartBeidou = view.findViewById(R.id.satellite_chart_beidou)
        chartQzss = view.findViewById(R.id.satellite_chart_qzss)
        chartSbas = view.findViewById(R.id.satellite_chart_sbas)
        skyplotView = view.findViewById(R.id.skyplot_view)

        // Info labels
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

        // Battery / device labels
        val tvBattSoc = view.findViewById<TextView>(R.id.value_batt_soc)
        val tvBattVolt = view.findViewById<TextView>(R.id.value_batt_voltage)
        val tvBattStat = view.findViewById<TextView>(R.id.value_batt_status)
        val tvBattTemp = view.findViewById<TextView>(R.id.value_batt_temp)
        val tvBattCurr = view.findViewById<TextView>(R.id.value_batt_current)
        val tvBattOtg = view.findViewById<TextView>(R.id.value_batt_otg)

        val tvDevName = view.findViewById<TextView>(R.id.value_dev_name)
        val tvDevModel = view.findViewById<TextView>(R.id.value_dev_model)
        val tvDevFw = view.findViewById<TextView>(R.id.value_dev_firmware)
        val tvDevSerial = view.findViewById<TextView>(R.id.value_dev_serial)
        val tvDevUptime = view.findViewById<TextView>(R.id.value_dev_uptime)
        val tvDevIp = view.findViewById<TextView>(R.id.value_dev_ip)

        // Apply constellation filters to charts
        mapOf(
            chartGps to Constellation.GPS,
            chartGlonass to Constellation.GLONASS,
            chartGalileo to Constellation.GALILEO,
            chartBeidou to Constellation.BEIDOU,
            chartQzss to Constellation.QZSS,
            chartSbas to Constellation.SBAS,
        ).forEach { (v, c) -> v?.setConstellationFilter(c) }

        // Satellite tap dialog (no collectors here)
        skyplotView?.onSatelliteClick = { sat, isUsed ->
            val constName = when (sat.constellation) {
                Constellation.GPS -> "GPS"
                Constellation.GLONASS -> "GLONASS"
                Constellation.GALILEO -> "Galileo"
                Constellation.BEIDOU -> "BeiDou"
                Constellation.QZSS -> "QZSS"
                Constellation.SBAS -> "SBAS"
                Constellation.IRNSS -> "IRNSS"
                else -> "Unknown"
            }
            val title = "Satellite ${constPrefix(sat.constellation)}${sat.svid}"
            val msg = buildString {
                appendLine("Constellation: $constName")
                appendLine("Azimuth: ${sat.azDeg ?: "--"}°")
                appendLine("Elevation: ${sat.elDeg ?: "--"}°")
                append("SNR: ${sat.snrDbHz ?: "--"} dB-Hz\nUsed in fix: ${if (isUsed) "Yes" else "No"}")
            }
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // Formatting helpers
        fun fmt(v: Double?, decimals: Int = 3): String =
            if (v == null) "--" else String.format(Locale.US, "% .${decimals}f", v).trim()
        fun fmtHdop(v: Double?): String = fmt(v, 1)
        fun fmtLatLon(v: Double?): String =
            if (v == null) "--" else String.format(Locale.US, "%.6f", v)
        fun fmtAgeS(seconds: Double?): String =
            if (seconds == null) "--" else String.format(Locale.US, "%.1f", seconds)

        fun fmtSats(used: Int?, visible: Int?): String {
            val u = used ?: 0
            val v = visible ?: 0
            val u2 = if (u > v && v > 0) v else u
            val v2 = if (v < u2) u2 else v
            return if (u2 > 0 || v2 > 0) "$u2/$v2" else "--"
        }
        fun updateSatsLabel() { tvSats.text = fmtSats(latestSatsUsed, latestSatsVisible) }

        val vm = rs2Vm

        // Sky geometry and totals
        skyCountJob?.cancel()
        skyCountJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sky.collect { sky ->
                    // Convert SatInfo to SkyGeometry for the charts
                    val geoms = sky.satellites.mapNotNull { satInfo ->
                        // Only include satellites with position data
                        if (satInfo.azimuthDeg != null && satInfo.elevationDeg != null) {
                            com.example.surveyingapp.gnss.model.SkyGeometry(
                                svid = satInfo.svid,
                                constellation = satInfo.constellation,
                                azDeg = satInfo.azimuthDeg,
                                elDeg = satInfo.elevationDeg,
                                snrDbHz = satInfo.cn0DbHz,
                                usedInFix = satInfo.usedInFix ?: false
                            )
                        } else null
                    }

                    // Update all charts and skyplot with geometry
                    chartGps?.setGeometry(geoms)
                    chartGlonass?.setGeometry(geoms)
                    chartGalileo?.setGeometry(geoms)
                    chartBeidou?.setGeometry(geoms)
                    chartQzss?.setGeometry(geoms)
                    chartSbas?.setGeometry(geoms)
                    skyplotView?.setGeometry(geoms)

                    // Update sats label from constellation totals
                    val used = sky.usedByConstellation.values.sum()
                    val vis = sky.visibleByConstellation.values.sum()
                    if (vis > 0) {
                        latestSatsUsed = used
                        latestSatsVisible = vis
                        updateSatsLabel()
                    }
                }
            }
        }

        // Fix info
        rs2InfoJob?.cancel()
        rs2InfoJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.fixes.collect { fix ->
                    tvLat.text = fmtLatLon(fix.latDeg)
                    tvLon.text = fmtLatLon(fix.lonDeg)
                    tvAlt.text = fmt(fix.altEllipsoidalM, 3)
                    tvRtk.text = fix.rtkStatus.name

                    latestSatsUsed = fix.satsUsed
                    updateSatsLabel()

                    tvHdop.text = fmtHdop(fix.hDop)
                    tvPdop.text = fmtHdop(fix.pDop)
                    tvHacc.text = fmt(fix.hAccM, 2)
                    tvVacc.text = fmt(fix.vAccM, 2)
                    tvCorrSrc.text = "--"
                    tvCorrAge.text = fmtAgeS(fix.diffAgeS)
                    tvAltMsl.text = fmt(fix.altMslM, 3)
                    tvGeoid.text = fmt(fix.geoidSeparationM, 3)
                }
            }
        }

        // Battery & device collectors
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                devVm.battery.collect { b ->
                    tvBattSoc.text  = b?.socPct?.let { "$it%" } ?: "--"
                    tvBattVolt.text = b?.voltageV?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    tvBattStat.text = b?.chargerStatus ?: "--"
                    tvBattTemp.text = b?.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
                    tvBattCurr.text = b?.currentA?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    tvBattOtg.text  = b?.otg?.let { if (it) "Yes" else "No" } ?: "--"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                devVm.device.collect { d ->
                    tvDevIp.text     = d?.ip ?: "--"
                    tvDevName.text   = d?.name ?: "--"
                    tvDevModel.text  = d?.model ?: "--"
                    tvDevFw.text     = d?.firmware ?: "--"
                    tvDevSerial.text = d?.serial ?: "--"
                    tvDevUptime.text = d?.uptime ?: "--"
                }
            }
        }
    }

    // Keep this only if you still need it elsewhere; it’s not used by the VM collectors.
    private suspend fun resolveReachIp(): String? {
        val now = System.currentTimeMillis()
        val configured = try { SurveyingApp.settingsRepo.externalTcpHost.firstOrNull() } catch (_: Exception) { null }
        if (!configured.isNullOrBlank()) { lastIp = configured; lastIpStampMs = now; return configured }
        if (!lastIp.isNullOrBlank() && (now - lastIpStampMs) <= ipCacheMs) return lastIp
        val discovered = withTimeoutOrNull(4000) {
            ReachDiscoveryHelper.discoverReachDevices(requireContext()).first().ip
        }
        if (!discovered.isNullOrBlank()) { lastIp = discovered; lastIpStampMs = now }
        return lastIp
    }

    private fun constPrefix(c: Constellation): String = when (c) {
        Constellation.GPS -> "G"
        Constellation.GLONASS -> "R"
        Constellation.GALILEO -> "E"
        Constellation.BEIDOU -> "B"
        Constellation.QZSS -> "Q"
        Constellation.SBAS -> "S"
        Constellation.IRNSS -> "I"
        Constellation.UNKNOWN -> "?"
    }

    override fun onDestroyView() {
        rs2InfoJob?.cancel()
        skyCountJob?.cancel()
        chartGps = null; chartGlonass = null; chartGalileo = null
        chartBeidou = null; chartQzss = null; chartSbas = null
        skyplotView = null
        super.onDestroyView()
    }
}
