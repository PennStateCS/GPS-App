package app.surrealar.ui.settings.categories

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.surrealar.R
import app.surrealar.domain.repository.DeveloperSettingsRepository
import app.surrealar.gnss.diagnostics.raw.RawNmeaAvailability
import app.surrealar.gnss.diagnostics.raw.RawNmeaDuration
import app.surrealar.gnss.diagnostics.raw.RawNmeaFiles
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorder
import app.surrealar.gnss.diagnostics.raw.RawNmeaState
import app.surrealar.gnss.diagnostics.raw.RawNmeaTermination
import app.surrealar.ui.settings.common.settingsError
import app.surrealar.ui.settings.common.showSettingsMessage
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Developer Tools → GNSS Diagnostics → Raw NMEA Recording. UI only: the app-scoped
 * [RawNmeaRecorder] does the recording. A recording does not outlive the diagnostic workflow: it is stopped
 * ([RawNmeaTermination.LIFECYCLE]) when the user leaves this category or Settings, or the screen stops
 * (app in background, screen off) — but not on a configuration change (rotation), where the page is rebuilt.
 */
internal class RawNmeaRecordingSection(
    private val fragment: Fragment,
    private val recorder: RawNmeaRecorder,
    private val settingsRepo: DeveloperSettingsRepository,
    /** Outlives the screen (writes that must complete). */
    private val saveScope: CoroutineScope,
    /** Listing / deleting files (tests pass their own). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Kept for the fragment's lifetime so the choice survives a category switch. */
    private var duration = RawNmeaDuration.DEFAULT
    private var lifecycleOwner: LifecycleOwner? = null
    private val stopObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) leave()
    }

    private var views: Views? = null

    private class Views(
        val availability: TextView,
        val toggle: MaterialButtonToggleGroup,
        val start: MaterialButton,
        val stop: MaterialButton,
        val status: TextView,
        val warning: TextView,
        val stats: TextView,
        val empty: TextView,
        val list: LinearLayout,
    )

    fun bind(root: View, scope: CoroutineScope) {
        val v = Views(
            root.findViewById(R.id.text_raw_nmea_availability),
            root.findViewById(R.id.toggle_raw_nmea_duration),
            root.findViewById(R.id.button_raw_nmea_start),
            root.findViewById(R.id.button_raw_nmea_stop),
            root.findViewById(R.id.text_raw_nmea_status),
            root.findViewById(R.id.text_raw_nmea_warning),
            root.findViewById(R.id.text_raw_nmea_stats),
            root.findViewById(R.id.text_raw_nmea_empty),
            root.findViewById(R.id.container_raw_nmea_recordings),
        )
        views = v
        v.toggle.check(buttonFor(duration))
        v.toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) durationFor(id)?.let { duration = it } }
        v.start.setOnClickListener { onStartClicked(scope) }
        v.stop.setOnClickListener { recorder.stop(RawNmeaTermination.USER_STOP) }

        runCatching { fragment.viewLifecycleOwner }.getOrNull()?.let { owner ->
            lifecycleOwner = owner
            owner.lifecycle.addObserver(stopObserver)
        }

        scope.launch {
            var lastKey: String? = null
            recorder.state.collect { st ->
                render()
                // Refresh the list when a recording starts or ends (not on every stats update).
                val key = when (st) {
                    is RawNmeaState.Recording -> "recording:" + st.nmeaFile.name
                    is RawNmeaState.Finished -> "finished:" + st.result.nmeaFile.name
                    else -> st.javaClass.simpleName
                }
                if (key != lastKey) { lastKey = key; refreshList(scope) }
            }
        }
        scope.launch { recorder.availability.collect { render() } }
        scope.launch {
            while (isActive) { render(); delay(500) }
        }
    }

    /** Called when the category view goes away (another category, Settings closed). */
    fun onDestroyView() {
        lifecycleOwner?.lifecycle?.removeObserver(stopObserver)
        lifecycleOwner = null
        leave()
        views = null
    }

    private fun leave() {
        val changing = fragment.activity?.isChangingConfigurations == true
        if (!changing && recorder.isRecording) recorder.stop(RawNmeaTermination.LIFECYCLE)
    }

    private fun render() {
        val v = views ?: return
        val st = recorder.state.value
        val avail = recorder.availability.value
        val recording = st is RawNmeaState.Recording
        v.availability.text = RawNmeaUiText.availability(avail)
        v.start.isEnabled = !recording && avail is RawNmeaAvailability.Available
        v.stop.isEnabled = recording
        for (i in 0 until v.toggle.childCount) v.toggle.getChildAt(i).isEnabled = !recording
        v.status.text = RawNmeaUiText.status(st, SystemClock.elapsedRealtime())
        val warning = RawNmeaUiText.warning(st)
        v.warning.text = warning ?: ""
        v.warning.visibility = if (warning == null) View.GONE else View.VISIBLE
        val stats = when (st) {
            is RawNmeaState.Recording -> st.stats
            is RawNmeaState.Finished -> st.result.stats
            else -> null
        }
        v.stats.text = stats?.let { RawNmeaUiText.stats(it) } ?: ""
        v.stats.visibility = if (stats == null) View.GONE else View.VISIBLE
    }

    private fun onStartClicked(scope: CoroutineScope) {
        scope.launch {
            val acknowledged = try {
                settingsRepo.rawNmeaPrivacyAcknowledged.first()
            } catch (e: Exception) {
                settingsError("Raw NMEA privacy acknowledgment read failed", e); false
            }
            if (acknowledged) startRecording() else showPrivacyDialog()
        }
    }

    private fun showPrivacyDialog() {
        val ctx = fragment.context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(RawNmeaUiText.PRIVACY_TITLE)
            .setMessage(RawNmeaUiText.PRIVACY_MESSAGE)
            .setPositiveButton("I understand") { _, _ ->
                saveScope.launch {
                    try { settingsRepo.setRawNmeaPrivacyAcknowledged(true) } catch (e: Exception) {
                        settingsError("Raw NMEA privacy acknowledgment save failed", e)
                    }
                }
                startRecording()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecording() {
        val chosen = duration
        saveScope.launch {
            val msg = when (val r = recorder.start(chosen)) {
                is RawNmeaRecorder.StartResult.Started -> null
                is RawNmeaRecorder.StartResult.Unavailable -> r.reason
                RawNmeaRecorder.StartResult.AlreadyRecording -> "A recording is already running."
                is RawNmeaRecorder.StartResult.Failed -> "Recording could not start (${r.message})."
            }
            msg?.let { fragment.showSettingsMessage(it) }
            render()
        }
    }

    private fun refreshList(scope: CoroutineScope) {
        scope.launch {
            val recordings = try {
                withContext(io) { recorder.files.list() }
            } catch (e: Exception) {
                settingsError("Raw NMEA recordings list failed", e); emptyList()
            }
            val v = views ?: return@launch
            v.list.removeAllViews()
            v.empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
            val activeFile = (recorder.state.value as? RawNmeaState.Recording)?.nmeaFile
            val inflater = LayoutInflater.from(v.list.context)
            recordings.forEach { rec ->
                val row = inflater.inflate(R.layout.item_raw_nmea_recording, v.list, false)
                val now = rec.nmeaFile == activeFile
                row.findViewById<TextView>(R.id.text_recording_name).text = rec.nmeaFile.name
                row.findViewById<TextView>(R.id.text_recording_detail).text = RawNmeaUiText.recordingDetail(rec, now)
                row.findViewById<MaterialButton>(R.id.button_recording_share).apply {
                    isEnabled = !now
                    setOnClickListener { share(rec) }
                }
                row.findViewById<MaterialButton>(R.id.button_recording_delete).apply {
                    isEnabled = !now
                    setOnClickListener { confirmDelete(rec, scope) }
                }
                v.list.addView(row)
            }
        }
    }

    /** Android share sheet with the .nmea and its .json (FileProvider, read-only grant). Nothing is uploaded. */
    private fun share(rec: RawNmeaFiles.Recording) {
        val ctx = fragment.context ?: return
        try {
            val authority = "${ctx.packageName}.fileprovider"
            val uris = ArrayList<Uri>()
            listOf(rec.nmeaFile, rec.metadataFile).filter(File::isFile).forEach {
                uris.add(FileProvider.getUriForFile(ctx, authority, it))
            }
            if (uris.isEmpty()) { fragment.showSettingsMessage("Recording file not found."); return }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "SurReal AR raw NMEA recording ${rec.nmeaFile.name}")
                clipData = ClipData.newRawUri(rec.nmeaFile.name, uris[0]).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fragment.startActivity(Intent.createChooser(intent, "Share raw NMEA recording"))
        } catch (e: Exception) {
            settingsError("Raw NMEA recording share failed", e)
            fragment.showSettingsMessage("Could not share the recording.")
        }
    }

    private fun confirmDelete(rec: RawNmeaFiles.Recording, scope: CoroutineScope) {
        val ctx = fragment.context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete recording?")
            .setMessage("${rec.nmeaFile.name} and its metadata file will be deleted from this device.")
            .setPositiveButton("Delete") { _, _ ->
                saveScope.launch {
                    val ok = try { withContext(io) { recorder.files.delete(rec.nmeaFile) } } catch (e: Exception) {
                        settingsError("Raw NMEA recording delete failed", e); false
                    }
                    if (!ok) fragment.showSettingsMessage("Could not delete the recording.")
                    if (views != null) refreshList(scope)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buttonFor(d: RawNmeaDuration): Int = when (d) {
        RawNmeaDuration.S10 -> R.id.btn_raw_nmea_10
        RawNmeaDuration.S30 -> R.id.btn_raw_nmea_30
        RawNmeaDuration.S60 -> R.id.btn_raw_nmea_60
        RawNmeaDuration.MANUAL -> R.id.btn_raw_nmea_manual
    }

    private fun durationFor(id: Int): RawNmeaDuration? = when (id) {
        R.id.btn_raw_nmea_10 -> RawNmeaDuration.S10
        R.id.btn_raw_nmea_30 -> RawNmeaDuration.S30
        R.id.btn_raw_nmea_60 -> RawNmeaDuration.S60
        R.id.btn_raw_nmea_manual -> RawNmeaDuration.MANUAL
        else -> null
    }
}
