package com.example.surveyingapp.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
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

        // Theme-aware colors for light/dark
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        axisPaint.color = if (night) Color.parseColor("#80FFFFFF") else Color.parseColor("#80000000")
        textPaint.color = if (night) Color.parseColor("#DDFFFFFF") else Color.parseColor("#DD000000")

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

            barPaint.color = if (!used) COLOR_GRAY_UNUSED else colorForSnr(snr)
            canvas.drawRect(x, top, x + barW, chartBottom, barPaint)
            x += barW + spacing
        }
    }

    // Color mapping (used satellites only). Not-used bars are drawn with COLOR_GRAY_UNUSED.
    private fun colorForSnr(snr: Int): Int = when {
        snr < 30  -> COLOR_RED_WEAK
        snr < 35  -> COLOR_AMBER_MED
        else      -> COLOR_GREEN_STRONG
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val COLOR_GREEN_STRONG = 0xFF4CAF50.toInt() // ≥35 dB-Hz
        private const val COLOR_AMBER_MED    = 0xFFFFC107.toInt() // 30–34
        private const val COLOR_RED_WEAK     = 0xFFF44336.toInt() // <30
        private const val COLOR_GRAY_UNUSED  = 0xFF9E9E9E.toInt() // not used in fix
    }
}
