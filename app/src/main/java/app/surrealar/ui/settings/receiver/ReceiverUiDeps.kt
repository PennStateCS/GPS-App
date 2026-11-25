package app.surrealar.ui.settings.receiver

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import app.surrealar.R
import app.surrealar.SurRealApplicationEntryPoint
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.service.LocationService
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the receiver sheets and dialogs need (Phase 5). They are plain fragments (restorable after rotation without
 * arguments), so they resolve these from the Hilt graph via [SurRealApplicationEntryPoint] rather than field
 * injection; tests install a fake with [override].
 */
interface ReceiverUiDeps {
    val connection: ReceiverConnectionController
    val settings: SettingsRepository
    val fixSwitchboard: FixSwitchboard?
    val devices: ReceiverDeviceManager?
    /** Live NMEA rate: DiagnosticsService.diagnosticData.linesPerSecond (never NmeaLogger). */
    val nmeaLinesPerSecond: Flow<Double>

    companion object {
        /** Test seam: when set, every receiver sheet/dialog uses it. */
        @VisibleForTesting
        @Volatile
        var override: ReceiverUiDeps? = null

        fun from(context: Context): ReceiverUiDeps = override ?: HiltReceiverUiDeps(context.applicationContext)
    }
}

private class HiltReceiverUiDeps(context: Context) : ReceiverUiDeps {
    private val entry = EntryPointAccessors.fromApplication(context, SurRealApplicationEntryPoint::class.java)
    override val connection: ReceiverConnectionController get() = entry.receiverConnectionController()
    override val settings: SettingsRepository get() = entry.settingsRepository()
    override val fixSwitchboard: FixSwitchboard get() = entry.fixSwitchboard()
    override val devices: ReceiverDeviceManager get() = entry.receiverDeviceManager()
    override val nmeaLinesPerSecond: Flow<Double>
        get() = runCatching { entry.diagnosticsService().diagnosticData.map { it.linesPerSecond } }.getOrElse { flowOf(0.0) }
}

/** Shared bits of the receiver UI. */
internal object ReceiverUi {

    /** "Switching receivers stops the current capture. Switch?" — [onConfirm] runs on Switch. */
    fun confirmCaptureStop(fragment: Fragment, message: Int = R.string.receiver_capture_confirm_message,
                           positive: Int = R.string.receiver_capture_confirm_switch, onConfirm: () -> Unit) {
        if (!fragment.isAdded) return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.receiver_capture_confirm_title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The receiver path needs the location service (as the old source radio did); a failure is only logged. */
    fun ensureLocationService(context: Context) {
        try {
            if (!LocationService.isRunning) LocationService.start(context)
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.SETTINGS, "Location service start from receiver selection failed", e)
        }
    }

    /** Short reason for a status chip / row; the full [ConnectError.message] is shown nearby. */
    fun shortReason(context: Context, e: ConnectError): String = when (e) {
        is ConnectError.Unreachable -> context.getString(R.string.receiver_reason_unreachable)
        is ConnectError.HttpOnly -> context.getString(R.string.receiver_reason_http_only, e.port)
        is ConnectError.NoData -> context.getString(R.string.receiver_reason_no_data, e.port)
        is ConnectError.NotNmea -> context.getString(R.string.receiver_reason_not_nmea, e.port)
        is ConnectError.Unexpected -> context.getString(R.string.receiver_reason_error)
        ConnectError.CaptureStarted -> context.getString(R.string.receiver_reason_capture_started)
    }
}
