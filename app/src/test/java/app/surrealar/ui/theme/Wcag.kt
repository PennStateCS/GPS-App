package app.surrealar.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG 2.x colour maths (pure, no Android): relative luminance, contrast ratio and source-over alpha compositing.
 * Colours are packed ARGB ints (the same as android.graphics.Color / getColor()).
 */
object Wcag {
    /** Normal-size text (AA). */
    const val TEXT = 4.5
    /** Large text and meaningful graphics / component boundaries (AA). */
    const val NON_TEXT = 3.0

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance of an opaque colour (alpha ignored), 0.0 (black) … 1.0 (white). */
    fun relativeLuminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** Contrast ratio 1.0 … 21.0. A translucent [fg] is first composited over [bg]; [bg] must be opaque. */
    fun contrast(fg: Int, bg: Int): Double {
        val a = relativeLuminance(composite(fg, bg))
        val b = relativeLuminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Source-over: [fg] (with its alpha) drawn on an opaque [bg]; the result is opaque. */
    fun composite(fg: Int, bg: Int): Int {
        val a = ((fg ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return (f * a + b * (1 - a)).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** [argb] with its alpha multiplied by [alpha] (e.g. a view's android:alpha). */
    fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (((argb ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
}
