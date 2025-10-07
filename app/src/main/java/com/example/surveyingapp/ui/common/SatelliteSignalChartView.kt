package com.example.surveyingapp.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.example.surveyingapp.R
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.Constellation
import kotlin.math.max
import kotlin.math.min

/**
 * Satellite Signal Strength Chart View
 * Renders a 3x2 grid of per-constellation bar charts (or a single chart when filtered).
 * X-axis: PRN numbers (satellite IDs); Y-axis: 0–50 dB-Hz.
 * Used sats are color-coded by SNR; unused sats are gray.
 */
class SatelliteSignalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Data (all constellations)
    private var satellites: List<SkyGeometry> = emptyList()
    private var usedSatellitePrns: Set<Int> = emptySet()
    private var usedSatellitePrnsNormalized: Set<Int> = emptySet()

    // Optional single-constellation filter
    private var constellationFilter: Constellation? = null

    // Resolve theme-aware colors
    private fun colorRes(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    // Paints
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRes(R.color.chart_axis)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRes(R.color.chart_grid)
        strokeWidth = dp(0.5f)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRes(R.color.chart_text)
        textSize = sp(10f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRes(R.color.chart_title)
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Layout constants
    private val paddingDp = 16f
    private val barWidth = dp(8f)
    private val barSpacing = dp(2f)
    private val legendBoxSize = dp(8f)
    private val legendItemGap = dp(16f)
    private val legendPaddingY = dp(8f)

    private val cellPadding = dp(16f)
    private val cellTitleGap = dp(8f)
    private val cellBottomLabelHeight = sp(18f)
    private val cellGutter = dp(18f)

    // SNR thresholds
    private val snrGreen = 35
    private val snrYellow = 30

    // Colors (theme-aware)
    private val colorStrong = colorRes(R.color.chart_bar_strong)
    private val colorMedium = colorRes(R.color.chart_bar_medium)
    private val colorWeak = colorRes(R.color.chart_bar_weak)
    private val colorUnused = colorRes(R.color.chart_bar_unused)

    fun setGeometry(geoms: List<SkyGeometry>) {
        // Filter by current constellation if set
        val filteredGeoms = constellationFilter?.let { filter ->
            geoms.filter { it.constellation == filter }
        } ?: geoms

        // Filter out satellites where snrDbHz == null to avoid drawing bars for null SNR
        this.satellites = filteredGeoms.filter { it.snrDbHz != null }.sortedBy { it.svid }

        // Extract used satellite PRNs from geometry that have usedInFix = true
        this.usedSatellitePrns = geoms.filter { it.usedInFix }.map { it.svid }.toSet()

        // Normalize: SBAS "120..158" may also appear as "33..64"
        this.usedSatellitePrnsNormalized = usedSatellitePrns.flatMap { id ->
            when (id) {
                in 120..158 -> listOf(id, id - 87)
                in 33..64   -> listOf(id, id + 87)
                else        -> listOf(id)
            }
        }.toSet()

        invalidate()
    }

    fun setConstellationFilter(filter: Constellation?) {
        constellationFilter = filter
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(400f).toInt()
        val desiredHeight = if (constellationFilter != null) dp(160f).toInt() else dp(600f).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = dp(paddingDp)

        // Single-constellation mode
        constellationFilter?.let { cons ->
            val contentLeft = padding
            val contentTop = padding
            val contentRight = width - padding
            val contentBottom = height - padding
            val sats = satellites.filter { it.constellation == cons }
            drawConstellationCell(canvas, contentLeft, contentTop, contentRight, contentBottom, sats, cons.name)
            return
        }

        // Legend + grid (3x2)
        val legendLineHeight = max(legendBoxSize, textPaint.textSize)
        val legendHeight = legendLineHeight + legendPaddingY
        val contentLeft = padding
        val contentTop = padding + legendHeight + dp(4f)
        val contentRight = width - padding
        val contentBottom = height - padding

        drawLegendHorizontal(canvas, contentLeft, contentRight, padding + legendLineHeight / 2f)

        if (satellites.isEmpty()) {
            val text = "No satellite data available"
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(text, 0, text.length, bounds)
            val x = (width - bounds.width()) / 2f
            val y = contentTop + (contentBottom - contentTop - bounds.height()) / 2f
            canvas.drawText(text, x, y, textPaint)
            return
        }

        val cols = 3
        val rows = 2
        val availW = contentRight - contentLeft
        val availH = contentBottom - contentTop
        if (availW <= 0f || availH <= 0f) return

        val totalGutterW = cellGutter * (cols - 1)
        val totalGutterH = cellGutter * (rows - 1)
        if (availW - totalGutterW <= 0f || availH - totalGutterH <= 0f) return

        val cellW = (availW - totalGutterW) / cols
        val cellH = (availH - totalGutterH) / rows

        val constellations = listOf(
            Constellation.GPS,
            Constellation.GLONASS,
            Constellation.GALILEO,
            Constellation.BEIDOU,
            Constellation.QZSS,
            Constellation.SBAS
        )

        constellations.forEachIndexed { index, cons ->
            val col = index % cols
            val row = index / cols
            val cellLeft = contentLeft + col * (cellW + cellGutter)
            val cellTop = contentTop + row * (cellH + cellGutter)
            val cellRight = cellLeft + cellW
            val cellBottom = cellTop + cellH
            val sats = satellites.filter { it.constellation == cons }
            drawConstellationCell(canvas, cellLeft, cellTop, cellRight, cellBottom, sats, cons.name)
        }
    }

    private fun drawConstellationCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sats: List<SkyGeometry>,
        title: String
    ) {
        // Cell border
        canvas.drawRect(left, top, right, bottom, gridPaint)

        // Title
        val plotLeft = left + cellPadding
        val plotRight = right - cellPadding
        val titleText = "$title (${sats.size})"
        val titleBaseline = top + cellPadding + titlePaint.textSize
        canvas.drawText(titleText, plotLeft, titleBaseline, titlePaint)

        // Plot area
        val plotTop = titleBaseline + cellTitleGap
        val plotBottom = bottom - cellBottomLabelHeight
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        // Axes
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)

        // Threshold lines
        val maxSignal = 50f
        val chartHeight = plotBottom - plotTop
        fun line(yVal: Float, color: Int) {
            val y = plotBottom - (yVal / maxSignal) * chartHeight
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; strokeWidth = dp(1f); style = Paint.Style.STROKE; alpha = 128
            }
            canvas.drawLine(plotLeft, y, plotRight, y, p)
        }
        line(35f, colorStrong)
        line(30f, colorMedium)
        line(20f, colorWeak)

        // Y labels
        canvas.drawText("50", plotLeft - dp(12f), plotTop + textPaint.textSize / 2f, textPaint)
        canvas.drawText("0",  plotLeft - dp(12f), plotBottom + textPaint.textSize / 2f, textPaint)

        if (sats.isEmpty()) return

        // ---- SHOW ALL SATELLITES ----
        val plotWidth = plotRight - plotLeft
        val count = sats.size
        val actualSpacing = if (count > 0) plotWidth / count else (barWidth + barSpacing)
        val dynamicBarWidth = min(barWidth, actualSpacing * 0.6f)

        fun isSatelliteUsed(s: SkyGeometry): Boolean {
            val prn = s.svid
            return prn in usedSatellitePrnsNormalized ||
                    (prn in 33..64   && (prn + 87) in usedSatellitePrnsNormalized) ||
                    (prn in 120..158 && (prn - 87) in usedSatellitePrnsNormalized)
        }

        sats.forEachIndexed { index, sat ->
            val x = plotLeft + index * actualSpacing + (actualSpacing - dynamicBarWidth) / 2f
            val snr = (sat.snrDbHz ?: 0.0).toInt().coerceIn(0, 50)
            val barHeight = (snr.toFloat() / maxSignal) * chartHeight
            val barTop = plotBottom - barHeight

            // Color: unused => gray; used => SNR color
            barPaint.color = if (!isSatelliteUsed(sat)) colorUnused else snrColor(snr)
            val barRect = RectF(x, barTop, x + dynamicBarWidth, plotBottom)
            canvas.drawRect(barRect, barPaint)

            // PRN label
            val prnText = sat.svid.toString()
            val textY = plotBottom + textPaint.textSize + dp(2f)
            val textX = x + (dynamicBarWidth - textPaint.measureText(prnText)) / 2f
            canvas.drawText(prnText, textX, textY, textPaint)
        }
    }

    private fun snrColor(snr: Int): Int = when {
        snr >= snrGreen -> colorStrong
        snr >= snrYellow -> colorMedium
        else -> colorWeak
    }

    private fun drawLegendHorizontal(canvas: Canvas, chartLeft: Float, chartRight: Float, centerY: Float) {
        val items = listOf(
            "Strong ≥ 35 dB-Hz (used)" to colorStrong,
            "30–34 dB-Hz (used)"       to colorMedium,
            "<30 dB-Hz (used)"         to colorWeak,
            "Not used"                 to colorUnused
        )
        var x = chartLeft
        val baselineY = centerY + textPaint.textSize / 3f
        items.forEach { (text, color) ->
            barPaint.color = color
            canvas.drawRect(x, centerY - legendBoxSize / 2f, x + legendBoxSize, centerY + legendBoxSize / 2f, barPaint)
            x += legendBoxSize + dp(6f)
            canvas.drawText(text, x, baselineY, textPaint)
            x += textPaint.measureText(text) + legendItemGap
            if (x > chartRight - legendBoxSize) return@forEach
        }
    }

    // dp/sp helpers
    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /**
     * Expose the internal dataset for testing
     */
    @VisibleForTesting
    fun getDataset(): List<SkyGeometry> = satellites

    /**
     * Expose the used satellite PRNs for testing
     */
    @VisibleForTesting
    fun getUsedSatellitePrns(): Set<Int> = usedSatellitePrnsNormalized
}
