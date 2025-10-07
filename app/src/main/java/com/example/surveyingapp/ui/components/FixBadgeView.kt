package com.example.surveyingapp.ui.components

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.surveyingapp.R
import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.logging.NmeaLogStats
import com.example.surveyingapp.gnss.ui.formatTimestampWithCompactBadge
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Custom view component that displays GNSS fix quality and stream health indicators.
 * Shows HDOP, satellite count, timestamp with source, and NMEA stream statistics.
 */
class FixBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val fixQualityText: TextView
    private val timestampText: TextView
    private val streamHealthText: TextView
    private val hdopText: TextView

    init {
        orientation = VERTICAL
        setPadding(16, 8, 16, 8)
        setBackgroundColor(ContextCompat.getColor(context, android.R.color.background_light))

        // Create TextViews programmatically since we don't have a layout file
        fixQualityText = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }

        timestampText = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        streamHealthText = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        hdopText = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        addView(fixQualityText)
        addView(timestampText)
        addView(hdopText)
        addView(streamHealthText)
    }

    /**
     * Updates the fix quality indicators based on current GNSS data
     */
    fun updateFixData(fixSnapshot: FixSnapshot) {
        // Update satellite count and general fix status
        val satsUsed = fixSnapshot.satsUsed ?: 0
        val satsInView = fixSnapshot.satellitesInView ?: 0

        fixQualityText.text = context.getString(R.string.fix_badge_satellites, satsUsed, satsInView)
        fixQualityText.setTextColor(getSatelliteColor(satsUsed))

        // Update timestamp with source badge
        timestampText.text = formatTimestampWithCompactBadge(
            fixSnapshot.timestampMillis,
            fixSnapshot.timestampSource,
            "HH:mm:ss"
        )

        // Update HDOP indicator
        val hdop = fixSnapshot.hdop
        if (hdop != null) {
            hdopText.text = context.getString(R.string.fix_badge_hdop, String.format(Locale.US, "%.1f", hdop))
            hdopText.setTextColor(getHdopColor(hdop))
            hdopText.visibility = VISIBLE
        } else {
            hdopText.text = context.getString(R.string.fix_badge_hdop_na)
            hdopText.setTextColor(Color.GRAY)
            hdopText.visibility = VISIBLE
        }
    }

    /**
     * Updates the stream health indicators based on NMEA logger statistics
     */
    fun updateStreamHealth(stats: NmeaLogStats) {
        val linesPerSec = stats.linesPerSecond
        val errorRate = if (stats.totalLines > 0) {
            (stats.parseErrors.toDouble() / stats.totalLines.toDouble()) * 100
        } else 0.0

        streamHealthText.text = context.getString(
            R.string.fix_badge_stream_health,
            linesPerSec.roundToInt(),
            String.format(Locale.US, "%.1f", errorRate)
        )
        streamHealthText.setTextColor(getStreamHealthColor(linesPerSec, errorRate))
    }

    /**
     * Returns color based on satellite count quality
     */
    private fun getSatelliteColor(satsUsed: Int): Int {
        return when {
            satsUsed >= 8 -> ContextCompat.getColor(context, android.R.color.holo_green_dark)
            satsUsed >= 4 -> ContextCompat.getColor(context, android.R.color.holo_orange_dark)
            satsUsed > 0 -> ContextCompat.getColor(context, android.R.color.holo_red_dark)
            else -> Color.GRAY
        }
    }

    /**
     * Returns color based on HDOP quality (lower is better)
     */
    private fun getHdopColor(hdop: Double): Int {
        return when {
            hdop <= 1.0 -> ContextCompat.getColor(context, android.R.color.holo_green_dark)
            hdop <= 2.0 -> ContextCompat.getColor(context, android.R.color.holo_orange_dark)
            hdop <= 5.0 -> ContextCompat.getColor(context, android.R.color.holo_red_dark)
            else -> Color.GRAY
        }
    }

    /**
     * Returns color based on stream health metrics
     */
    private fun getStreamHealthColor(linesPerSec: Double, errorRate: Double): Int {
        return when {
            linesPerSec > 5 && errorRate < 1.0 -> ContextCompat.getColor(context, android.R.color.holo_green_dark)
            linesPerSec > 1 && errorRate < 5.0 -> ContextCompat.getColor(context, android.R.color.holo_orange_dark)
            linesPerSec > 0 -> ContextCompat.getColor(context, android.R.color.holo_red_dark)
            else -> Color.GRAY
        }
    }
}
