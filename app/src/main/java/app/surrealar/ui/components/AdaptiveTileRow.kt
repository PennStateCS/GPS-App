package app.surrealar.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.surrealar.R

/**
 * A row of equal-width stat tiles (item_gnss_stat_tile) that stacks them vertically when a tile's value would not
 * fit on one line in its column — e.g. "41.24538710°" or "±0.012 m" on a phone, or at a 1.3x / 1.5x font scale —
 * instead of letting the value break mid-number. Purely a layout decision: the tiles, their order and their text
 * are unchanged; at the default size on a tablet the row looks exactly as before.
 */
class AdaptiveTileRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    init { orientation = HORIZONTAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tiles = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        if (tiles.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val column = avail / tiles.size
            val fits = tiles.all { tile -> valueWidth(tile) <= column - horizontalChrome(tile) }
            applyOrientation(if (fits) HORIZONTAL else VERTICAL)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Natural one-line width of the tile's value text (0 when the tile has no value view). */
    private fun valueWidth(tile: View): Int {
        val v = tile.findViewById<TextView>(R.id.tile_value) ?: return 0
        val text = v.text ?: return 0
        return kotlin.math.ceil(v.paint.measureText(text, 0, text.length)).toInt()
    }

    /** Margins of the tile plus the padding between the tile edge and its value text. */
    private fun horizontalChrome(tile: View): Int {
        val lp = tile.layoutParams as? MarginLayoutParams
        val margins = (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        val v = tile.findViewById<TextView>(R.id.tile_value) ?: return margins
        var pad = v.compoundPaddingLeft + v.compoundPaddingRight
        var p = v.parent as? View
        while (p != null && p !== tile) { pad += p.paddingLeft + p.paddingRight; p = p.parent as? View }
        return margins + pad + tile.paddingLeft + tile.paddingRight
    }

    private fun applyOrientation(o: Int) {
        if (orientation == o) return
        orientation = o
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as? LayoutParams ?: continue
            if (o == VERTICAL) { lp.width = LayoutParams.MATCH_PARENT; lp.weight = 0f; lp.bottomMargin = lp.leftMargin }
            else { lp.width = 0; lp.weight = 1f; lp.bottomMargin = 0 }
            // Mutated in place: we are inside onMeasure, so no setLayoutParams (it would request another layout).
        }
    }
}
