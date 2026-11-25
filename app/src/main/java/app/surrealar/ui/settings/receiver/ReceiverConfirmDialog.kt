package app.surrealar.ui.settings.receiver

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.receiver.selection.ConnectRequest
import app.surrealar.receiver.selection.ConnectStart
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * The receiver confirmations (L7): "Switching receivers stops the current capture", "Changing the profile stops the
 * capture", "Disconnect and remove the saved receiver". A DialogFragment whose action is described by its arguments,
 * so it survives rotation (no leaked window) and runs the action itself on the app-scoped selection core
 * ([ReceiverUiDeps.connection]) when the user confirms. Shown at most once per fragment manager (tag check +
 * showNow, so a double tap never stacks two).
 */
class ReceiverConfirmDialog : DialogFragment() {

    /** What the positive button does. Stored by name in the arguments. */
    enum class Action {
        /** connect(request, confirmCaptureStop = true), then ensure the location service. */
        CONNECT,
        /** selectInternal("picker"), then ensure the location service. */
        SELECT_TABLET,
        /** reconnectSaved(confirmCaptureStop = true), then ensure the location service when it started. */
        RECONNECT_SAVED,
        /** applyReportedProfile(confirmCaptureStop = true). */
        APPLY_PROFILE,
        /** disconnect("receiver-details"): device GNSS and the saved host/port cleared. */
        DISCONNECT,
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val b = MaterialAlertDialogBuilder(requireContext())
            .setMessage(args.getInt(ARG_MESSAGE))
            .setPositiveButton(args.getInt(ARG_POSITIVE)) { _, _ -> confirm() }
            .setNegativeButton(android.R.string.cancel, null)
        args.getInt(ARG_TITLE).takeIf { it != 0 }?.let { b.setTitle(it) }
        return b.create()
    }

    private fun confirm() {
        val args = requireArguments()
        val action = runCatching { Action.valueOf(args.getString(ARG_ACTION).orEmpty()) }.getOrNull() ?: return
        val ctx = requireContext().applicationContext
        val connection = ReceiverUiDeps.from(ctx).connection
        when (action) {
            Action.CONNECT -> {
                val request = requestFrom(args) ?: return
                val start = connection.connect(request, confirmCaptureStop = true)
                if (start == ConnectStart.STARTED) ReceiverUi.ensureLocationService(ctx)
                // "Save & connect" in the manual dialog: the dialog closes once the connect started.
                if (args.getBoolean(ARG_DISMISS_PARENT)) (parentFragment as? DialogFragment)?.dismissAllowingStateLoss()
            }
            Action.SELECT_TABLET -> {
                connection.selectInternal("picker")
                ReceiverUi.ensureLocationService(ctx)
            }
            Action.RECONNECT_SAVED -> {
                // reconnectSaved reads the stored receiver first: run it on a scope that outlives this dialog.
                val owner = parentFragment ?: activity ?: return
                owner.lifecycleScope.launch {
                    try {
                        if (connection.reconnectSaved(confirmCaptureStop = true) == ConnectStart.STARTED) ReceiverUi.ensureLocationService(ctx)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver retry after capture confirmation failed", e)
                    }
                }
            }
            Action.APPLY_PROFILE -> connection.applyReportedProfile(confirmCaptureStop = true)
            Action.DISCONNECT -> connection.disconnect("receiver-details")
        }
    }

    companion object {
        const val TAG = "ReceiverConfirmDialog"
        private const val ARG_ACTION = "action"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE = "positive"
        private const val ARG_HOST = "host"
        private const val ARG_PORT = "port"
        private const val ARG_PROFILE = "profile"
        private const val ARG_NAME = "name"
        private const val ARG_DISMISS_PARENT = "dismiss_parent"

        private fun requestFrom(args: Bundle): ConnectRequest? {
            val host = args.getString(ARG_HOST) ?: return null
            val profile = args.getString(ARG_PROFILE)?.let { key -> ExternalReceiverProfile.entries.firstOrNull { it.prefKey == key } }
            return ConnectRequest(host, args.getInt(ARG_PORT), profile, args.getString(ARG_NAME).orEmpty())
        }

        /**
         * Shows the confirmation in [fragment]'s child fragment manager unless one is already shown (or the state is
         * saved). Returns true when it is showing.
         */
        fun show(
            fragment: Fragment,
            action: Action,
            @StringRes message: Int = R.string.receiver_capture_confirm_message,
            @StringRes positive: Int = R.string.receiver_capture_confirm_switch,
            @StringRes title: Int = R.string.receiver_capture_confirm_title,
            request: ConnectRequest? = null,
            dismissParent: Boolean = false,
        ): Boolean {
            if (!fragment.isAdded) return false
            val fm = fragment.childFragmentManager
            if (fm.findFragmentByTag(TAG) != null) return true
            if (fm.isStateSaved) return false
            ReceiverConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACTION, action.name)
                    putInt(ARG_TITLE, title)
                    putInt(ARG_MESSAGE, message)
                    putInt(ARG_POSITIVE, positive)
                    putBoolean(ARG_DISMISS_PARENT, dismissParent)
                    request?.let {
                        putString(ARG_HOST, it.host)
                        putInt(ARG_PORT, it.port)
                        putString(ARG_PROFILE, it.profile?.prefKey)
                        putString(ARG_NAME, it.name)
                    }
                }
            }.showNow(fm, TAG)
            return true
        }
    }
}
