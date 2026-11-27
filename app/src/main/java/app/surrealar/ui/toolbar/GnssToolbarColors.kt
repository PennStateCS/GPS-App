package app.surrealar.ui.toolbar

import android.content.Context
import androidx.annotation.ColorRes
import app.surrealar.R

/**
 * Status level → foreground colour for text on the GNSS toolbar strip (#2A4158, slate in both themes). Uses the
 * toolbar-specific roles (values/colors.xml, "GNSS TOOLBAR FOREGROUND ROLES"), all ≥ 5.7:1 on the strip — not the
 * global status_* roles, which are tuned for app surfaces. View concern only; the level comes from the mapper.
 */
object GnssToolbarColors {
    @ColorRes
    fun colorRes(level: GnssStatusLevel): Int = when (level) {
        GnssStatusLevel.SUCCESS -> R.color.toolbar_success
        GnssStatusLevel.WARNING -> R.color.toolbar_warning
        GnssStatusLevel.INFO -> R.color.toolbar_info
        GnssStatusLevel.ERROR -> R.color.toolbar_error
        GnssStatusLevel.NONE -> R.color.toolbar_on_secondary
    }

    fun color(context: Context, level: GnssStatusLevel): Int = context.getColor(colorRes(level))
}
