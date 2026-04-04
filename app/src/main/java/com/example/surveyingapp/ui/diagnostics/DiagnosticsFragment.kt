package com.example.surveyingapp.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class DiagnosticsFragment : Fragment() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    private lateinit var rootView: ScrollView
    private lateinit var metricsContainer: LinearLayout
    private lateinit var fixDataContainer: LinearLayout
    private lateinit var sentenceHistoryContainer: LinearLayout

    // Metric display views
    private lateinit var linesPerSecondText: TextView
    private lateinit var parseErrorRateText: TextView
    private lateinit var totalLinesText: TextView
    private lateinit var totalErrorsText: TextView

    // Fix data display views
    private lateinit var timestampText: TextView
    private lateinit var latLonText: TextView
    private lateinit var altMslText: TextView
    private lateinit var altEllipsoidalText: TextView
    private lateinit var speedText: TextView
    private lateinit var courseText: TextView
    private lateinit var satsUsedText: TextView
    private lateinit var hdopText: TextView
    private lateinit var satellitesInViewText: TextView

    // Sentence history
    private lateinit var sentenceHistoryText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        createDiagnosticsUI()
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeData()
    }

    private fun createDiagnosticsUI() {
        val context = requireContext()

        // Create main scroll view
        rootView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }

        // Main container
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Add title
        val titleText = TextView(context).apply {
            text = "GNSS Diagnostics"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16.dpToPx())
        }
        mainContainer.addView(titleText)

        // Create metrics section
        createMetricsSection(context, mainContainer)

        // Create fix data section
        createFixDataSection(context, mainContainer)

        // Create sentence history section
        createSentenceHistorySection(context, mainContainer)

        rootView.addView(mainContainer)
    }

    private fun createMetricsSection(context: android.content.Context, parent: LinearLayout) {
        // Metrics section header
        val metricsHeader = TextView(context).apply {
            text = "Processing Metrics"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        parent.addView(metricsHeader)

        // Metrics container
        metricsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Create metric text views
        linesPerSecondText = createMetricTextView(context, "Lines/sec: --")
        parseErrorRateText = createMetricTextView(context, "Parse error rate: --%")
        totalLinesText = createMetricTextView(context, "Total lines: --")
        totalErrorsText = createMetricTextView(context, "Total errors: --")

        metricsContainer.addView(linesPerSecondText)
        metricsContainer.addView(parseErrorRateText)
        metricsContainer.addView(totalLinesText)
        metricsContainer.addView(totalErrorsText)

        parent.addView(metricsContainer)
    }

    private fun createFixDataSection(context: android.content.Context, parent: LinearLayout) {
        // Fix data section header
        val fixDataHeader = TextView(context).apply {
            text = "Current Fix Data"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        parent.addView(fixDataHeader)

        // Fix data container
        fixDataContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            setBackgroundColor(0xFFF0F8FF.toInt())
        }

        // Create fix data text views
        timestampText = createFixDataTextView(context, "Timestamp: --")
        latLonText = createFixDataTextView(context, "Lat/Lon: --")
        altMslText = createFixDataTextView(context, "Altitude MSL: --")
        altEllipsoidalText = createFixDataTextView(context, "Altitude Ellipsoidal: --")
        speedText = createFixDataTextView(context, "Speed: --")
        courseText = createFixDataTextView(context, "Course: --")
        satsUsedText = createFixDataTextView(context, "Satellites Used: --")
        hdopText = createFixDataTextView(context, "HDOP: --")
        satellitesInViewText = createFixDataTextView(context, "Satellites in View: --")

        fixDataContainer.addView(timestampText)
        fixDataContainer.addView(latLonText)
        fixDataContainer.addView(altMslText)
        fixDataContainer.addView(altEllipsoidalText)
        fixDataContainer.addView(speedText)
        fixDataContainer.addView(courseText)
        fixDataContainer.addView(satsUsedText)
        fixDataContainer.addView(hdopText)
        fixDataContainer.addView(satellitesInViewText)

        parent.addView(fixDataContainer)
    }

    private fun createSentenceHistorySection(context: android.content.Context, parent: LinearLayout) {
        // Sentence history section header
        val historyHeader = TextView(context).apply {
            text = "Last 20 NMEA Sentences"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        parent.addView(historyHeader)

        // Sentence history container
        sentenceHistoryContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            setBackgroundColor(0xFFFFF8F0.toInt())
        }

        sentenceHistoryText = TextView(context).apply {
            text = "No NMEA data received yet..."
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }

        sentenceHistoryContainer.addView(sentenceHistoryText)
        parent.addView(sentenceHistoryContainer)
    }

    private fun createMetricTextView(context: android.content.Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
        }
    }

    private fun createFixDataTextView(context: android.content.Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            // Observe diagnostic metrics
            viewModel.diagnosticData.collect { data ->
                updateMetricsUI(data)
            }
        }

        lifecycleScope.launch {
            // Observe fix snapshot data
            viewModel.fixSnapshot.collect { snapshot ->
                updateFixDataUI(snapshot)
            }
        }
    }

    private fun updateMetricsUI(data: com.example.surveyingapp.gnss.diagnostics.DiagnosticData) {
        linesPerSecondText.text = "Lines/sec: ${String.format(Locale.US, "%.2f", data.linesPerSecond)}"
        parseErrorRateText.text = "Parse error rate: ${String.format(Locale.US, "%.2f", data.parseErrorRate)}%"
        totalLinesText.text = "Total lines: ${data.totalLinesProcessed}"
        totalErrorsText.text = "Total errors: ${data.totalParseErrors}"

        // Update sentence history
        val historyText = if (data.lastTwentySentences.isEmpty()) {
            "No NMEA data received yet..."
        } else {
            data.lastTwentySentences.reversed().joinToString("\n") { sentence ->
                if (sentence.startsWith("❌")) {
                    sentence // Already marked as error
                } else {
                    sentence
                }
            }
        }
        sentenceHistoryText.text = historyText
    }

    private fun updateFixDataUI(snapshot: com.example.surveyingapp.gnss.accumulator.FixSnapshot) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            .format(java.util.Date(snapshot.timestampMillis))
        timestampText.text = "Timestamp: $timestamp (${snapshot.timestampSource})"

        // Lat/Lon
        val latLon = if (snapshot.lat != null && snapshot.lon != null) {
            "${String.format(Locale.US, "%.6f", snapshot.lat)}, ${String.format(Locale.US, "%.6f", snapshot.lon)}"
        } else {
            "--"
        }
        latLonText.text = "Lat/Lon: $latLon"

        // Altitude MSL
        val altMslStr = snapshot.altMsl?.let {
            "${String.format(Locale.US, "%.2f", it)} m"
        } ?: "--"
        altMslText.text = "Altitude MSL: $altMslStr"

        // Altitude Ellipsoidal with derivation indicator
        val altEllipsoidalStr = snapshot.altEllipsoidal?.let { ellipsoidal ->
            val derived = if (snapshot.altMsl != null && snapshot.geoidSeparation != null) {
                " (derived)"
            } else {
                ""
            }
            "${String.format(Locale.US, "%.2f", ellipsoidal)} m$derived"
        } ?: "--"
        altEllipsoidalText.text = "Altitude Ellipsoidal: $altEllipsoidalStr"

        // Speed
        val speedStr = snapshot.speedMps?.let {
            "${String.format(Locale.US, "%.2f", it)} m/s"
        } ?: "--"
        speedText.text = "Speed: $speedStr"

        // Course
        val courseStr = snapshot.courseDeg?.let {
            "${String.format(Locale.US, "%.1f", it)}°"
        } ?: "--"
        courseText.text = "Course: $courseStr"

        // Satellites
        satsUsedText.text = "Satellites Used: ${snapshot.satsUsed ?: "--"}"
        satellitesInViewText.text = "Satellites in View: ${snapshot.satellitesInView ?: "--"}"

        // HDOP
        val hdopStr = snapshot.hdop?.let {
            String.format(Locale.US, "%.2f", it)
        } ?: "--"
        hdopText.text = "HDOP: $hdopStr"
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
