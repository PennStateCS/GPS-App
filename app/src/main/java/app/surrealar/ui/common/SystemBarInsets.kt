package app.surrealar.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
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
 * Each pass uses the larger of the dispatched insets and the window's own root insets, and re-applies
 * once the view is attached, so a pass that sees 0 (a parent that consumed the insets — as the drawer
 * header experienced — or a pass before attach) can never undo a real inset. On the SM-X940 the
 * dispatched insets are correct (status 82 px, nav 84 px); the root-insets fallback is a safety net.
 * Original paddings/height are captured once, so passes never accumulate. Debug builds log every pass
 * under logcat tag `Insets`.
 */
object SystemBarInsets {

    fun apply(root: View, topBar: View? = null) {
        val rootPad = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val barPadTop = topBar?.paddingTop ?: 0
        val barHeight = topBar?.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT

        fun barsOf(insets: WindowInsetsCompat?): Insets = insets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ) ?: Insets.NONE

        fun applyFrom(dispatched: WindowInsetsCompat?) {
            val a = barsOf(dispatched)
            val rootInsets = ViewCompat.getRootWindowInsets(root)
            val b = barsOf(rootInsets)
            val bars = Insets.of(maxOf(a.left, b.left), maxOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            // Debug builds: exactly what each pass saw, so an overlap can be traced from logcat (tag Insets).
            if (app.surrealar.BuildConfig.DEBUG) android.util.Log.d("Insets",
                "${root.context.javaClass.simpleName} pass=${if (dispatched != null) "dispatch" else "post"} " +
                    "dispatched=$a root=$b (rootInsets=${if (rootInsets == null) "null" else "ok"}, " +
                    "statusVisible=${rootInsets?.isVisible(WindowInsetsCompat.Type.statusBars())}) " +
                    "applied=$bars attached=${root.isAttachedToWindow} bar=${topBar?.javaClass?.simpleName} " +
                    "barH=${topBar?.layoutParams?.height} barPadTop=${topBar?.paddingTop}")
            if (bars == Insets.NONE) return   // nothing known yet; keep whatever is applied
            root.setPadding(
                rootPad[0] + bars.left,
                rootPad[1] + if (topBar == null) bars.top else 0,
                rootPad[2] + bars.right,
                rootPad[3] + bars.bottom,
            )
            topBar?.let { bar ->
                bar.setPadding(bar.paddingLeft, barPadTop + bars.top, bar.paddingRight, bar.paddingBottom)
                // A fixed-height toolbar (e.g. ?attr/actionBarSize) must grow, or the padding squashes it.
                if (barHeight > 0 && bar.layoutParams.height != barHeight + bars.top) {
                    bar.layoutParams = bar.layoutParams.apply { height = barHeight + bars.top }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> applyFrom(insets); insets }
        // Root insets exist only once attached: apply then (and again on the next frame, after layout).
        if (root.isAttachedToWindow) root.post { applyFrom(null) }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post { applyFrom(null) } }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        ViewCompat.requestApplyInsets(root)
    }
}
