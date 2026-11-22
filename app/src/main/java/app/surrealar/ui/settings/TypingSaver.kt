package app.surrealar.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs the last submitted action once calls stop for [delayMs]; each new call restarts the wait. */
internal class Debouncer(private val scope: CoroutineScope, private val delayMs: Long = DEFAULT_DELAY_MS) {
    private var job: Job? = null

    fun submit(action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            action()
        }
    }

    companion object {
        const val DEFAULT_DELAY_MS = 400L
    }
}

/**
 * Calls [onSettled] [delayMs] after the user stops typing in this field, so a value is saved even if
 * the screen is left without the field losing focus first. Only edits made while the field has focus
 * count: text set by code (loading saved values, restoring after bad input) does not trigger a save.
 * [scope] should outlive the screen so a pending save still runs after the user leaves.
 */
internal fun EditText.saveWhileTyping(
    scope: CoroutineScope,
    delayMs: Long = Debouncer.DEFAULT_DELAY_MS,
    onSettled: () -> Unit,
) {
    val debouncer = Debouncer(scope, delayMs)
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (hasFocus()) debouncer.submit { onSettled() }
        }
    })
}
