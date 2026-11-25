package app.surrealar.ui.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide "is a GNSS capture (occupation) running?" flag (Phase 5).
 *
 * [CaptureViewModel] marks its occupation active when it starts and inactive when it completes, is cancelled or
 * interrupted, or when the ViewModel is cleared (dialog dismissed). Receiver selection reads [active] to ask for
 * confirmation before switching receivers and to never change the receiver profile automatically while a capture
 * runs (a profile change stops a Phase 4 capture; observations are never mixed).
 *
 * Owners are tracked separately, so one ViewModel ending its capture never clears another's.
 */
@Singleton
class ActiveCaptureTracker @Inject constructor() {
    private val owners = LinkedHashSet<Any>()
    private val _active = MutableStateFlow(false)

    /** True while at least one capture is running (started, not yet complete / cancelled / interrupted). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** [owner]'s capture started (or restarted). */
    fun begin(owner: Any) = synchronized(owners) {
        owners += owner
        _active.value = true
    }

    /** [owner]'s capture ended; a no-op when it had none. */
    fun end(owner: Any) = synchronized(owners) {
        owners -= owner
        _active.value = owners.isNotEmpty()
    }
}
