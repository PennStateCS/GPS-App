package app.surrealar.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import app.surrealar.R
import kotlin.math.max

/**
 * Simple SNR bar chart view (0..50 dB-Hz).
 *
 * Public API:
 *  - setTitleAndCount(title, count)
 *  - setValues(values)                                // legacy: all bars treated as "used"
 *  - setValuesWithUsed(valuesAndUsed: List<Pair<Int,Boolean>>) // new: gray bars if not used
 */
class SnrBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var title: String = ""
    private var count: Int = 0

    // Backing data. If valuesWithUsed != null, it takes precedence over values.
    private var values: List<Int> = emptyList()
    private var valuesWithUsed: List<Pair<Int, Boolean>>? = null

    // Paints
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Layout constants
    private val topPaddingDp = 8f
    private val bottomPaddingDp = 16f
    private val leftPaddingDp = 12f
    private val rightPaddingDp = 12f
    private val titleBottomMarginDp = 6f
    private val axisMarginLeftDp = 6f
    private val spacingDp = 4f
    private val minBarWidthDp = 2f
    private val maxSnr = 50f

    fun setTitleAndCount(title: String, count: Int) {
        this.title = title
        this.count = count
        invalidate()
    }

    /** Legacy setter: every bar is treated as "used". */
    fun setValues(values: List<Int>) {
        this.values = values
        this.valuesWithUsed = null
        invalidate()
    }

    /** New setter: provide SNR and whether the sat is used in the current solution. */
    fun setValuesWithUsed(valuesAndUsed: List<Pair<Int, Boolean>>) {
        this.valuesWithUsed = valuesAndUsed
        this.values = emptyList()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = dp(160f).toInt()
        val desiredH = dp(120f).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Theme-aware chart roles (values / values-night chart_*).
        axisPaint.color = colorAxis
        textPaint.color = colorText

        val padL = paddingLeft + dp(leftPaddingDp)
        val padT = paddingTop + dp(topPaddingDp)
        val padR = paddingRight + dp(rightPaddingDp)
        val padB = paddingBottom + dp(bottomPaddingDp)

        val contentLeft = padL.toFloat()
        val contentTop = padT.toFloat()
        val contentRight = (width - padR).toFloat()
        val contentBottom = (height - padB).toFloat()

        // Title
        val titleText = buildString {
            if (title.isNotBlank()) append(title) else append("Satellites")
            append(' ')
            append(count)
        }
        val fm = textPaint.fontMetrics
        val titleBaseline = contentTop - fm.top
        canvas.drawText(titleText, contentLeft, titleBaseline, textPaint)

        // Chart area
        val chartTop = titleBaseline + dp(titleBottomMarginDp)
        val chartBottom = contentBottom
        val chartLeft = contentLeft + dp(axisMarginLeftDp)
        val chartRight = contentRight
        if (chartRight <= chartLeft || chartBottom <= chartTop) return

        // Axes
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        // Data to draw
        val data: List<Pair<Int, Boolean>> = valuesWithUsed
            ?: values.map { it to true } // legacy path: mark all as "used"

        if (data.isEmpty()) return

        // Spacing / sizing
        val spacing = dp(spacingDp)
        val availableW = (chartRight - chartLeft)
        val n = data.size
        val totalSpacing = spacing * (n + 1)
        val rawBarW = (availableW - totalSpacing) / max(1, n)
        val barW = max(dp(minBarWidthDp), rawBarW)
        val usableHeight = (chartBottom - chartTop)

        var x = chartLeft + spacing
        data.forEach { (rawSnr, used) ->
            val snr = rawSnr.coerceIn(0, maxSnr.toInt())
            val ratio = snr / maxSnr
            val barH = ratio * usableHeight
            val top = chartBottom - barH

            barPaint.color = if (!used) colorUnused else colorForSnr(snr)
            canvas.drawRect(x, top, x + barW, chartBottom, barPaint)
            x += barW + spacing
        }
    }

    // Color mapping (used satellites only). Not-used bars are drawn with colorUnused.
    // Same SNR series as SatelliteSignalChartView (the old fixed amber was 1.6:1 on a light card).
    private fun colorForSnr(snr: Int): Int = when {
        snr < 30  -> colorWeak      // <30 dB-Hz
        snr < 35  -> colorMedium    // 30–34
        else      -> colorStrong    // ≥35
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun chartColor(id: Int): Int = ContextCompat.getColor(context, id)
    private val colorAxis = chartColor(R.color.chart_axis)
    private val colorText = chartColor(R.color.chart_text)
    private val colorStrong = chartColor(R.color.chart_bar_strong)
    private val colorMedium = chartColor(R.color.chart_bar_medium)
    private val colorWeak = chartColor(R.color.chart_bar_weak)
    private val colorUnused = chartColor(R.color.chart_bar_unused)
}
