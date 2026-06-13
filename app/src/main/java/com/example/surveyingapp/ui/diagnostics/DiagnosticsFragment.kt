package com.example.surveyingapp.ui.diagnostics

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.SkySnapshot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class DiagnosticsFragment : Fragment() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    // ── Root views ────────────────────────────────────────────────────────────
    private lateinit var rootView: ScrollView

    // Section: Source & Status
    private lateinit var providerText: TextView
    private lateinit var rtkStatusText: TextView
    private lateinit var timestampText: TextView
    private lateinit var timestampSourceText: TextView

    // Section: Position
    private lateinit var latLonText: TextView
    private lateinit var altMslText: TextView
    private lateinit var altEllipsoidalText: TextView
    private lateinit var geoidSepText: TextView
    private lateinit var hAccText: TextView
    private lateinit var vAccText: TextView

    // Section: Motion
    private lateinit var speedText: TextView
    private lateinit var courseText: TextView

    // Section: Signal Quality
    private lateinit var hdopText: TextView
    private lateinit var vdopText: TextView
    private lateinit var pdopText: TextView
    private lateinit var satsUsedText: TextView
    private lateinit var satsVisibleText: TextView
    private lateinit var corrAgeText: TextView
    private lateinit var corrStationText: TextView
    private lateinit var diffAgeText: TextView

    // Section: Sky / Constellations
    private lateinit var skyTotalText: TextView
    private lateinit var skyConstellationsText: TextView

    // Section: NMEA replay metrics
    private lateinit var linesPerSecText: TextView
    private lateinit var parseErrText: TextView
    private lateinit var sentenceHistoryText: TextView

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        buildUI()
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeData()
    }

    // ── UI construction (programmatic – no XML dependency) ───────────────────

    private fun buildUI() {
        val ctx = requireContext()
        rootView = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(titleView("GNSS Live Data"))

        // ── Source & Status ────────────────────────────────────────────────
        container.addView(sectionHeader("Source & Status"))
        val sourceBox = cardBox(0xFFE8F5E9.toInt()).also { container.addView(it) }
        providerText       = rowView("Provider: —").also { sourceBox.addView(it) }
        rtkStatusText      = rowView("RTK Status: —").also { sourceBox.addView(it) }
        timestampText      = rowView("UTC Time: —").also { sourceBox.addView(it) }
        timestampSourceText = rowView("Time Source: —").also { sourceBox.addView(it) }

        // ── Position ──────────────────────────────────────────────────────
        container.addView(sectionHeader("Position"))
        val posBox = cardBox(0xFFF0F8FF.toInt()).also { container.addView(it) }
        latLonText         = rowView("Lat / Lon: —").also { posBox.addView(it) }
        altMslText         = rowView("Alt MSL: —").also { posBox.addView(it) }
        altEllipsoidalText = rowView("Alt Ellipsoidal: —").also { posBox.addView(it) }
        geoidSepText       = rowView("Geoid Separation: —").also { posBox.addView(it) }
        hAccText           = rowView("H. Accuracy (1σ): —").also { posBox.addView(it) }
        vAccText           = rowView("V. Accuracy (1σ): —").also { posBox.addView(it) }

        // ── Motion ────────────────────────────────────────────────────────
        container.addView(sectionHeader("Motion"))
        val motBox = cardBox(0xFFFFF8E1.toInt()).also { container.addView(it) }
        speedText  = rowView("Speed: —").also { motBox.addView(it) }
        courseText = rowView("Course: —").also { motBox.addView(it) }

        // ── Signal Quality ────────────────────────────────────────────────
        container.addView(sectionHeader("Signal Quality"))
        val qualBox = cardBox(0xFFF3E5F5.toInt()).also { container.addView(it) }
        hdopText        = rowView("HDOP: —").also { qualBox.addView(it) }
        vdopText        = rowView("VDOP: —").also { qualBox.addView(it) }
        pdopText        = rowView("PDOP: —").also { qualBox.addView(it) }
        satsUsedText    = rowView("Satellites Used: —").also { qualBox.addView(it) }
        satsVisibleText = rowView("Satellites Visible: —").also { qualBox.addView(it) }
        corrAgeText     = rowView("Correction Age: —").also { qualBox.addView(it) }
        corrStationText = rowView("Correction Station: —").also { qualBox.addView(it) }
        diffAgeText     = rowView("Diff. Age: —").also { qualBox.addView(it) }

        // ── Sky / Constellations ──────────────────────────────────────────
        container.addView(sectionHeader("Satellite Sky"))
        val skyBox = cardBox(0xFFE3F2FD.toInt()).also { container.addView(it) }
        skyTotalText          = rowView("Total Used / Visible: —").also { skyBox.addView(it) }
        skyConstellationsText = rowView("Constellations: —").also { skyBox.addView(it) }

        // ── NMEA Replay Metrics (populated only during NMEA file replay) ──
        container.addView(sectionHeader("NMEA Replay Metrics"))
        val replayBox = cardBox(0xFFFFF3E0.toInt()).also { container.addView(it) }
        linesPerSecText = rowView("Lines/sec: —").also { replayBox.addView(it) }
        parseErrText    = rowView("Parse error rate: —").also { replayBox.addView(it) }

        container.addView(sectionHeader("Last 20 NMEA Sentences  (replay only)"))
        val histBox = cardBox(0xFFFFF8F0.toInt()).also { container.addView(it) }
        sentenceHistoryText = TextView(ctx).apply {
            text = "No NMEA replay active…"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }.also { histBox.addView(it) }

        rootView.addView(container)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch { viewModel.activeProviderLabel.collect { updateProvider(it) } }
                launch { viewModel.latestFix.collect { updateFix(it) } }
                launch { viewModel.skySnapshot.collect { updateSky(it) } }
                launch { viewModel.diagnosticData.collect { updateReplayMetrics(it) } }
            }
        }
    }

    // ── Update functions ──────────────────────────────────────────────────────

    private fun updateProvider(label: String) {
        providerText.text = "Provider: $label"
    }

    private fun updateFix(fix: Fix?) {
        if (fix == null) {
            rtkStatusText.text = "RTK Status: waiting…"
            rtkStatusText.setTextColor(Color.GRAY)
            timestampText.text = "UTC Time: —"
            timestampSourceText.text = "Time Source: —"
            latLonText.text = "Lat / Lon: —"
            altMslText.text = "Alt MSL: —"
            altEllipsoidalText.text = "Alt Ellipsoidal: —"
            geoidSepText.text = "Geoid Separation: —"
            hAccText.text = "H. Accuracy (1σ): —"
            vAccText.text = "V. Accuracy (1σ): —"
            speedText.text = "Speed: —"
            courseText.text = "Course: —"
            hdopText.text = "HDOP: —"
            vdopText.text = "VDOP: —"
            pdopText.text = "PDOP: —"
            satsUsedText.text = "Satellites Used: —"
            satsVisibleText.text = "Satellites Visible: —"
            corrAgeText.text = "Correction Age: —"
            corrStationText.text = "Correction Station: —"
            diffAgeText.text = "Diff. Age: —"
            return
        }

        // RTK status with colour coding
        val (rtkLabel, rtkColor) = when (fix.rtkStatus) {
            RtkStatus.FIX    -> "Fixed" to Color.parseColor("#388E3C")
            RtkStatus.FLOAT  -> "Float" to Color.parseColor("#1976D2")
            RtkStatus.DGPS   -> "DGPS"  to Color.parseColor("#F57C00")
            RtkStatus.SINGLE -> "Single" to Color.parseColor("#D32F2F")
            RtkStatus.DEAD_RECKONING -> "Dead Reckoning" to Color.parseColor("#7B1FA2")
            RtkStatus.INVALID -> "Invalid" to Color.RED
            RtkStatus.NONE   -> "None"  to Color.GRAY
        }
        rtkStatusText.text = "RTK Status: $rtkLabel"
        rtkStatusText.setTextColor(rtkColor)

        // Timestamp
        val utcFmt = SimpleDateFormat("HH:mm:ss.SSS 'UTC'", Locale.US).also {
            it.timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        timestampText.text = "UTC Time: ${utcFmt.format(Date(fix.timeUtc.toEpochMilli()))}"
        timestampSourceText.text = "Time Source: ${fix.timestampSource}"

        // Position
        latLonText.text = "Lat / Lon: ${f6(fix.latDeg)},  ${f6(fix.lonDeg)}"
        altMslText.text = "Alt MSL: ${fix.altMslM?.let { "${f2(it)} m" } ?: "—"}"
        altEllipsoidalText.text = "Alt Ellipsoidal: ${fix.altEllipsoidalM?.let { "${f2(it)} m" } ?: "—"}"
        geoidSepText.text = "Geoid Separation: ${fix.geoidSeparationM?.let { "${f3(it)} m" } ?: "—"}"
        hAccText.text = "H. Accuracy (1σ): ${fix.hAccM?.let { "${f3(it)} m" } ?: "—  (no GST)"}"
        vAccText.text = "V. Accuracy (1σ): ${fix.vAccM?.let { "${f3(it)} m" } ?: "—  (no GST)"}"

        // Motion
        val speedKnots = fix.speedMps?.let { it * 1.94384 }
        speedText.text = "Speed: ${fix.speedMps?.let { "${f2(it)} m/s  (${f1(speedKnots!!)} kn)" } ?: "—"}"
        courseText.text = "Course: ${fix.courseDeg?.let { "${f1(it)}°" } ?: "—"}"

        // Quality
        hdopText.text = "HDOP: ${fix.hDop?.let { f2(it) } ?: "—"}"
        vdopText.text = "VDOP: ${fix.vDop?.let { f2(it) } ?: "—"}"
        pdopText.text = "PDOP: ${fix.pDop?.let { f2(it) } ?: "—"}"
        satsUsedText.text = "Satellites Used: ${fix.satsUsed}"
        satsVisibleText.text = "Satellites Visible: ${fix.satsVisible ?: "—"}"
        corrAgeText.text = "Correction Age: ${fix.diffAgeS?.let { "${f1(it)} s" } ?: "—"}"
        corrStationText.text = "Correction Station: ${fix.correctionStationId ?: "—"}"
        diffAgeText.text = "Diff. Age: ${fix.diffAgeS?.let { "${f1(it)} s" } ?: "—"}"
    }

    private fun updateSky(sky: SkySnapshot) {
        skyTotalText.text = "Total Used / Visible: ${sky.totalUsed} / ${sky.totalVisible}"

        if (sky.satellites.isEmpty()) {
            skyConstellationsText.text = "Constellations: — (no GSV data)"
            return
        }

        val sb = StringBuilder()
        sky.visibleByConstellation
            .entries.sortedByDescending { it.value }
            .forEach { (constellation, visible) ->
                val used = sky.usedByConstellation[constellation] ?: 0
                sb.appendLine("  ${constellation.name.padEnd(10)}  used=$used  vis=$visible")
            }
        skyConstellationsText.text = "Constellations:\n${sb.toString().trimEnd()}"
    }

    private fun updateReplayMetrics(data: com.example.surveyingapp.gnss.diagnostics.DiagnosticData) {
        linesPerSecText.text = "Lines/sec: ${f2(data.linesPerSecond)}"
        parseErrText.text = "Parse error rate: ${f2(data.parseErrorRate)}%"

        sentenceHistoryText.text = if (data.lastTwentySentences.isEmpty()) {
            "No NMEA replay active…"
        } else {
            data.lastTwentySentences.reversed().joinToString("\n")
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun titleView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 22f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 12.dp)
    }

    private fun sectionHeader(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#37474F"))
        setPadding(0, 16.dp, 0, 4.dp)
    }

    private fun cardBox(bgColor: Int): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgColor)
        setPadding(12.dp, 8.dp, 12.dp, 12.dp)
    }

    private fun rowView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 3.dp, 0, 3.dp)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── Format helpers ────────────────────────────────────────────────────────

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
    private fun f6(v: Double) = String.format(Locale.US, "%.6f", v)
}
