package app.surrealar.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import app.surrealar.R
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import app.surrealar.gnss.diagnostics.NmeaLogStats
import app.surrealar.ui.common.formatTimestampWithCompactBadge
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
        val dp = resources.displayMetrics.density
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        // Theme-aware (was a fixed light background with black / grey text, unreadable in dark mode).
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))

        // Primary reading 14 sp, supporting lines 12 sp (were 12 / 10 sp); all tabular figures.
        fixQualityText = reading(R.style.TextAppearance_App_Reading)
        timestampText = reading(R.style.TextAppearance_App_Supporting)
        streamHealthText = reading(R.style.TextAppearance_App_Supporting)
        hdopText = reading(R.style.TextAppearance_App_Supporting)

        addView(fixQualityText)
        addView(timestampText)
        addView(hdopText)
        addView(streamHealthText)
    }

    private fun reading(style: Int) = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, style)
        fontFeatureSettings = "tnum"
    }

    /** Semantic status roles from the theme (docs/design-system.md); [Quality.UNKNOWN] uses the neutral role. */
    private enum class Quality(val attr: Int) {
        GOOD(R.attr.colorStatusSuccess), FAIR(R.attr.colorStatusWarning), POOR(R.attr.colorStatusError),
        UNKNOWN(R.attr.colorStatusNeutral)
    }

    private fun color(q: Quality): Int = MaterialColors.getColor(this, q.attr)

    /**
     * Updates the fix quality indicators based on current GNSS data
     */
    fun updateFixData(fixSnapshot: FixSnapshot) {
        // Update satellite count and general fix status
        // Unknown counts show "?" (grey), never a fake 0.
        val satsUsed = fixSnapshot.satsUsed
        val satsInView = fixSnapshot.satellitesInView

        fixQualityText.text = context.getString(
            R.string.fix_badge_satellites, satsUsed?.toString() ?: "?", satsInView?.toString() ?: "?"
        )
        fixQualityText.setTextColor(getSatelliteColor(satsUsed ?: 0))

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
            hdopText.setTextColor(color(Quality.UNKNOWN))
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
            satsUsed >= 8 -> color(Quality.GOOD)
            satsUsed >= 4 -> color(Quality.FAIR)
            satsUsed > 0 -> color(Quality.POOR)
            else -> color(Quality.UNKNOWN)
        }
    }

    /**
     * Returns color based on HDOP quality (lower is better)
     */
    private fun getHdopColor(hdop: Double): Int {
        return when {
            hdop <= 1.0 -> color(Quality.GOOD)
            hdop <= 2.0 -> color(Quality.FAIR)
            hdop <= 5.0 -> color(Quality.POOR)
            else -> color(Quality.UNKNOWN)
        }
    }

    /**
     * Returns color based on stream health metrics
     */
    private fun getStreamHealthColor(linesPerSec: Double, errorRate: Double): Int {
        return when {
            linesPerSec > 5 && errorRate < 1.0 -> color(Quality.GOOD)
            linesPerSec > 1 && errorRate < 5.0 -> color(Quality.FAIR)
            linesPerSec > 0 -> color(Quality.POOR)
            else -> color(Quality.UNKNOWN)
        }
    }
}
