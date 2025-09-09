package com.example.surveyingapp.ui.capture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.di.HasGnssGraph
import com.example.surveyingapp.gnss.capture.AveragingPolicy
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Minimal averaging UI. Start -> watch progress -> Save.
 * Hook this dialog to your "+" action.
 */
class CaptureDialogFragment : DialogFragment() {

    private lateinit var vm: CaptureViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = requireActivity() as HasGnssGraph
        vm = host.gnssGraph.captureViewModel
        setStyle(STYLE_NORMAL, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_capture, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val inputName = view.findViewById<EditText>(R.id.input_name)
        val inputNote = view.findViewById<EditText>(R.id.input_note)
        val tvElapsed = view.findViewById<TextView>(R.id.value_elapsed)
        val tvSamples = view.findViewById<TextView>(R.id.value_samples)
        val tvMode    = view.findViewById<TextView>(R.id.value_rtk_mode)
        val tvHAcc    = view.findViewById<TextView>(R.id.value_hacc)
        val tvVAcc    = view.findViewById<TextView>(R.id.value_vacc)
        val btnStart  = view.findViewById<Button>(R.id.btn_start)
        val btnSave   = view.findViewById<Button>(R.id.btn_save)
        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)

        btnStart.setOnClickListener {
            vm.start(
                AveragingPolicy(
                    minDurationSec = 60,
                    maxDurationSec = 120,
                    minSamples = 150,
                    requiredMinStatus = com.example.surveyingapp.gnss.model.RtkStatus.FLOAT,
                    maxFixAgeSec = 3,
                    maxDiffAgeSec = 10
                )
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { st ->
                    when (st) {
                        is com.example.surveyingapp.gnss.capture.ObservationSession.State.Capturing -> {
                            tvElapsed.text = String.format(Locale.US, "%ds", st.elapsedSec)
                            tvSamples.text = st.samples.toString()
                            tvMode.text    = st.rtkStatus
                            tvHAcc.text    = st.hAccM?.let { String.format(Locale.US, "%.02f m", it) } ?: "—"
                            tvVAcc.text    = st.vAccM?.let { String.format(Locale.US, "%.02f m", it) } ?: "—"
                            btnSave.isEnabled = false
                        }
                        is com.example.surveyingapp.gnss.capture.ObservationSession.State.Complete -> {
                            tvElapsed.text = getString(R.string.capture_done)
                            btnSave.isEnabled = true
                        }
                        is com.example.surveyingapp.gnss.capture.ObservationSession.State.Paused -> {
                            tvMode.text = st.reason
                            btnSave.isEnabled = false
                        }
                        else -> btnSave.isEnabled = false
                    }
                }
            }
        }

        btnCancel.setOnClickListener { vm.cancel(); dismissAllowingStateLoss() }
        btnSave.setOnClickListener {
            val name = inputName.text?.toString().orEmpty().ifBlank { getString(R.string.capture_default_name) }
            val note = inputNote.text?.toString()
            val color = 0xFF3F51B5.toInt()  // placeholder; replace with your color picker
            val icon  = "pin"               // placeholder; replace with your icon id
            viewLifecycleOwner.lifecycleScope.launch {
                vm.save(name, note, color, icon)
                dismissAllowingStateLoss()
            }
        }
    }
}
