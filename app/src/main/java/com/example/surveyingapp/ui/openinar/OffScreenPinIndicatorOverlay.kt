package com.example.surveyingapp.ui.openinar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transparent overlay that draws edge arrows for geospatial pins that are currently
 * off-screen but in front of the camera.
 *
 * Each arrow is clamped to the screen edge and points in the direction of its pin,
 * paired with a small pill showing the pin name and distance.
 *
 * Call [updateArrows] from the main thread whenever the off-screen set changes.
 * Non-clickable so touch events pass through to the GLSurfaceView beneath it.
 */
class OffScreenPinIndicatorOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * @param edgeX     Clamped screen-edge X pixel position of the arrow tip.
     * @param edgeY     Clamped screen-edge Y pixel position of the arrow tip.
     * @param angleDeg  Direction the arrow points (0 = right, 90 = down) — toward the pin.
     * @param name      Coordinate name displayed in the label pill.
     * @param distStr   Pre-formatted distance string, e.g. "34m" or "1.2km".
     * @param isModel   True when the coordinate has a 3D model assigned (draws in cyan).
     */
    data class ArrowEntry(
        val edgeX: Float,
        val edgeY: Float,
        val angleDeg: Float,
        val name: String,
        val distStr: String,
        val isModel: Boolean
    )

    private var arrows: List<ArrowEntry> = emptyList()
    private val dp = context.resources.displayMetrics.density

    private val sz  = 10f * dp   // arrowhead half-size
    private val pad = 4f  * dp

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC111111.toInt(); style = Paint.Style.FILL
    }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val cyanFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt(); style = Paint.Style.FILL
    }
    private val whiteNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 9f * dp; typeface = Typeface.DEFAULT_BOLD
    }
    private val cyanNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt(); textSize = 9f * dp; typeface = Typeface.DEFAULT_BOLD
    }
    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt(); textSize = 8f * dp
    }

    private val path  = Path()
    private val rectF = RectF()

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    /** Replace the current arrow set and request a redraw.  Safe to call on the main thread. */
    fun updateArrows(newArrows: List<ArrowEntry>) {
        arrows = newArrows
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (a in arrows) {
            val fillPaint = if (a.isModel) cyanFill else whiteFill
            val namePaint = if (a.isModel) cyanNamePaint else whiteNamePaint
            val angleRad  = Math.toRadians(a.angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            // ── Arrow triangle at the screen edge ────────────────────────────────────
            canvas.save()
            canvas.translate(a.edgeX, a.edgeY)
            canvas.rotate(a.angleDeg)
            path.reset()
            path.moveTo( sz,         0f         )
            path.lineTo(-sz * 0.55f, -sz * 0.70f)
            path.lineTo(-sz * 0.55f,  sz * 0.70f)
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.restore()

            // ── Label pill inset slightly toward screen center ───────────────────────
            val inset  = sz * 3.2f
            val pillCx = a.edgeX - cosA * inset
            val pillCy = a.edgeY - sinA * inset

            val nameW = namePaint.measureText(a.name)
            val distW = distPaint.measureText(a.distStr)
            val maxW  = maxOf(nameW, distW)

            val pillW = maxW + pad * 2f
            val lineH = namePaint.textSize
            val pillH = lineH + distPaint.textSize + pad * 2.5f

            canvas.save()
            canvas.translate(pillCx, pillCy)
            rectF.set(-pillW / 2f, -pillH / 2f, pillW / 2f, pillH / 2f)
            canvas.drawRoundRect(rectF, 4f * dp, 4f * dp, bgPaint)
            canvas.drawText(a.name,    -nameW / 2f, -pad * 0.3f,              namePaint)
            canvas.drawText(a.distStr, -distW / 2f,  distPaint.textSize * 0.8f, distPaint)
            canvas.restore()
        }
    }
}

