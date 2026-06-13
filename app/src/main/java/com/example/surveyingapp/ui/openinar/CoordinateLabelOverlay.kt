package com.example.surveyingapp.ui.openinar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws coordinate-name labels at projected 2-D screen
 * positions above each geospatial pin.
 *
 * Labels for model-linked pins are prefixed with the tag [MODEL_TAG] and drawn
 * in cyan; plain coordinate labels are drawn in white.
 *
 * Call [updateLabels] from the main thread (via [View.post]) whenever the set of
 * visible anchors changes.  The view is non-clickable so touch events pass through
 * to the [android.opengl.GLSurfaceView] beneath it.
 */
class CoordinateLabelOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * A single label to draw.  [x]/[y] are in raw pixel coordinates.
     * [subtext] is an optional second line rendered in a smaller, dimmer font
     * (used for distance + bearing, e.g. "34m SW").
     */
    data class LabelEntry(val text: String, val x: Float, val y: Float, val subtext: String = "")

    private var labels: List<LabelEntry> = emptyList()
    private val dp = context.resources.displayMetrics.density

    // Pre-computed constants — dp never changes at runtime
    private val pad     = 5f * dp
    private val cornerR = 4f * dp

    // Pre-allocated rect — reused in onDraw to avoid object allocation per frame
    private val rect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC111111.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()   // cyan — used for model-linked pins
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * dp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        textSize = 11f * dp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 9f * dp
    }

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    /** Replace the current label set and request a redraw.  Safe to call from any thread. */
    fun updateLabels(newLabels: List<LabelEntry>) {
        labels = newLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (label in labels) {
            val isModel = label.text.startsWith(MODEL_TAG)
            val displayText = if (isModel) label.text.removePrefix(MODEL_TAG) else label.text
            val textPaint = if (isModel) cyanPaint else whitePaint

            val textW = textPaint.measureText(displayText)
            val textH = textPaint.textSize

            val hasSubtext = label.subtext.isNotEmpty()
            val subW = if (hasSubtext) subtextPaint.measureText(label.subtext) else 0f
            val subH = if (hasSubtext) subtextPaint.textSize + 2f * dp else 0f

            val totalW = maxOf(textW, subW)
            val totalH = textH + subH

            rect.set(
                label.x - totalW / 2f - pad,
                label.y - totalH - pad * 2f,
                label.x + totalW / 2f + pad,
                label.y
            )
            canvas.drawRoundRect(rect, cornerR, cornerR, bgPaint)
            if (isModel) canvas.drawRoundRect(rect, cornerR, cornerR, borderPaint)

            // Main label — shift up when subtext is present
            val mainY = label.y - pad - subH
            canvas.drawText(displayText, label.x - textW / 2f, mainY, textPaint)

            if (hasSubtext) {
                canvas.drawText(label.subtext, label.x - subW / 2f, label.y - pad, subtextPaint)
            }
        }
    }

    companion object {
        /** Prefix added to label text when the coordinate has a model assigned. */
        const val MODEL_TAG = "\u25C6 "  // ◆ (diamond bullet)
    }
}
