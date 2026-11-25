package app.surrealar.ui.settings.receiver

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.receiver.selection.ConnectRequest
import app.surrealar.receiver.selection.ReceiverTestResult
import app.surrealar.settings.model.ExternalReceiverProfile
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Host / port validation for the manual dialog (pure, unit-tested). */
object ManualReceiverInput {
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    /** An IPv4 address or a host name (no scheme, path, port or spaces). */
    fun isValidHost(raw: String?): Boolean {
        val h = raw?.trim().orEmpty()
        if (h.isEmpty() || h.length > 253) return false
        if (h.all { it.isDigit() || it == '.' }) {
            val parts = h.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toInt() in 0..255 }
        }
        return h.trimEnd('.').split('.').all { LABEL.matches(it) }
    }

    /** 1..65535, else null. */
    fun parsePort(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** The types offered (Generic NMEA, RS2+, RS4, RS4 Pro): every profile, in declaration order. */
    val types: List<ExternalReceiverProfile> get() = ExternalReceiverProfile.entries
}

/**
 * "Connect manually…" (Phase 5): host/IP, receiver type and TCP port. The port starts at the type's default and
 * follows type changes only while it is not a custom port ([ExternalReceiverProfile.portForProfileChange]).
 * "Test connection" runs the core's probe without saving or switching ([app.surrealar.receiver.selection.ReceiverConnectionController.test]);
 * "Save & connect" starts the normal test-before-switch connect with the chosen type (saved by the core as the
 * user's profile). A Generic NMEA receiver gets no Emlid request (the core asks HTTP only for Emlid profiles).
 */
class ManualReceiverDialog : DialogFragment() {

    private lateinit var deps: ReceiverUiDeps
    private var selected: ExternalReceiverProfile = ExternalReceiverProfile.DEFAULT
    private var testJob: Job? = null

    private lateinit var content: View
    private val hostLayout get() = content.findViewById<TextInputLayout>(R.id.input_manual_host)
    private val portLayout get() = content.findViewById<TextInputLayout>(R.id.input_manual_port)
    private val hostEdit get() = content.findViewById<EditText>(R.id.edit_manual_host)
    private val portEdit get() = content.findViewById<EditText>(R.id.edit_manual_port)
    private val typeDropdown get() = content.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_manual_type)
    private val hint get() = content.findViewById<TextView>(R.id.text_manual_hint)
    private val result get() = content.findViewById<TextView>(R.id.text_manual_result)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deps = ReceiverUiDeps.from(requireContext())
        selected = savedInstanceState?.getString(KEY_TYPE)?.let { ExternalReceiverProfile.fromPrefKey(it) }
            ?: deps.connection.state.value.profile
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TYPE, selected.prefKey)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Inflated with the dialog builder's themed context so the outlined fields match the Settings inputs.
        val builder = MaterialAlertDialogBuilder(requireContext())
        content = LayoutInflater.from(builder.context).inflate(R.layout.dialog_manual_receiver, null, false)
        val types = ManualReceiverInput.types
        typeDropdown.setAdapter(ArrayAdapter(requireContext(), R.layout.list_item_dropdown_settings, types.map { it.label }))
        typeDropdown.setText(selected.label, false)
        if (savedInstanceState == null) {
            val s = deps.connection.state.value
            hostEdit.setText(s.savedTarget?.host.orEmpty())
            portEdit.setText((s.savedTarget?.port ?: selected.defaultPort).toString())
        }
        updateHint()
        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            val p = types.getOrNull(position) ?: return@setOnItemClickListener
            if (p == selected) return@setOnItemClickListener
            selected = p
            portEdit.setText(ExternalReceiverProfile.portForProfileChange(ManualReceiverInput.parsePort(portEdit.text?.toString()), p).toString())
            updateHint()
            clearResult()
        }
        return builder
            .setTitle(R.string.manual_title)
            .setView(content)
            .setPositiveButton(R.string.manual_save, null)
            .setNeutralButton(R.string.manual_test, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? AlertDialog ?: return
        // Set here so the buttons don't dismiss the dialog by themselves.
        d.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { onTest() }
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { onSave() }
    }

    private fun updateHint() {
        hint.setText(if (selected.vendor == ReceiverVendor.EMLID) R.string.manual_hint_emlid else R.string.manual_hint_generic)
    }

    private fun clearResult() {
        result.isVisible = false
    }

    /** The request in the fields, or null (errors shown on the fields). */
    private fun readRequest(): ConnectRequest? {
        val host = hostEdit.text?.toString()?.trim().orEmpty()
        val port = ManualReceiverInput.parsePort(portEdit.text?.toString())
        val hostOk = ManualReceiverInput.isValidHost(host)
        hostLayout.error = if (hostOk) null else getString(R.string.manual_host_error)
        portLayout.error = if (port != null) null else getString(R.string.manual_port_error)
        if (!hostOk || port == null) return null
        return ConnectRequest(host, port, selected)
    }

    private fun onTest() {
        val req = readRequest() ?: return
        val testButton = (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_NEUTRAL)
        testJob?.cancel()
        testButton?.isEnabled = false
        showResult(getString(R.string.manual_testing), error = false)
        val connection = deps.connection
        testJob = lifecycleScope.launch {
            try {
                when (val r = connection.test(req)) {
                    is ReceiverTestResult.Reachable -> showResult(getString(R.string.manual_test_ok, r.target.port), error = false)
                    is ReceiverTestResult.Failed -> showResult(getString(R.string.manual_test_failed, r.error.message), error = true)
                    ReceiverTestResult.Invalid -> showResult(getString(R.string.manual_host_error), error = true)
                }
            } finally {
                testButton?.isEnabled = true
            }
        }
    }

    private fun showResult(text: String, error: Boolean) {
        result.isVisible = true
        result.text = text
        result.setTextColor(MaterialColors.getColor(result,
            if (error) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurface))
    }

    private fun onSave() {
        val req = readRequest() ?: return
        testJob?.cancel()
        ReceiverPickerSheet.startConnect(this, deps, req, dismissAfterConfirm = true) { dismissAllowingStateLoss() }
    }

    companion object {
        const val TAG = "ManualReceiverDialog"
        private const val KEY_TYPE = "type"
    }
}
