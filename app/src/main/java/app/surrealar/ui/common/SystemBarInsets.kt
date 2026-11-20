package app.surrealar.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a screen's content clear of the status and navigation bars under edge-to-edge (enforced
 * from Android 15 / targetSdk 35+), where activities draw behind the system bars. Without it a
 * secondary activity's title overlaps the clock and its bottom buttons sit under the nav bar (seen on
 * the "Select 3D Model File" picker).
 *
 * - [root] gets the left/right/bottom system-bar (and display-cutout) insets as extra padding.
 * - [topBar] (a toolbar/app bar), when given, absorbs the TOP inset instead: it grows by the inset and
 *   pads its content down, so its background still fills the status-bar strip. Without a [topBar] the
 *   root takes the top inset too.
 *
 * Original paddings/height are captured once, so repeated inset passes never accumulate.
 */
object SystemBarInsets {

    fun apply(root: View, topBar: View? = null) {
        val rootPad = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val barPadTop = topBar?.paddingTop ?: 0
        val barHeight = topBar?.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                rootPad[0] + bars.left,
                rootPad[1] + if (topBar == null) bars.top else 0,
                rootPad[2] + bars.right,
                rootPad[3] + bars.bottom,
            )
            topBar?.let { bar ->
                bar.setPadding(bar.paddingLeft, barPadTop + bars.top, bar.paddingRight, bar.paddingBottom)
                // A fixed-height toolbar (e.g. ?attr/actionBarSize) must grow, or the padding squashes it.
                if (barHeight > 0) {
                    bar.layoutParams = bar.layoutParams.apply { height = barHeight + bars.top }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
