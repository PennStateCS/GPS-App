package app.surrealar.ui.settings.common

import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException

/** Small helpers shared by the Settings host and its category controllers. */

/** Nearest TextInputLayout wrapping [field] (TextInputEditText sits inside a FrameLayout in it). */
internal fun inputLayoutOf(field: View): TextInputLayout? {
    var p = field.parent
    while (p != null) {
        if (p is TextInputLayout) return p
        p = p.parent
    }
    return null
}

internal fun dpToPx(context: Context, dp: Int): Int {
    val density = context.resources.displayMetrics.density
    return (dp * density).toInt()
}

/** Snackbar on the Settings fragment's view; does nothing once the view is gone. */
internal fun Fragment.showSettingsMessage(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
    val v = view ?: return
    Snackbar.make(v, message, duration).show()
}

/**
 * Logs a Settings failure at E with its exception. A cancelled coroutine (category switched, screen
 * closed) is rethrown instead of logged, so callers can use it in any catch / onFailure.
 */
internal fun settingsError(message: String, e: Throwable, tag: String = DiagTags.SETTINGS) {
    if (e is CancellationException) throw e
    DiagnosticsLogger.e(tag, message, e)
}

/** [settingsError] at W, for handled problems (a UI refresh skipped, a best-effort lookup failed). */
internal fun settingsWarn(message: String, e: Throwable, tag: String = DiagTags.SETTINGS) {
    if (e is CancellationException) throw e
    DiagnosticsLogger.w(tag, message, e)
}
