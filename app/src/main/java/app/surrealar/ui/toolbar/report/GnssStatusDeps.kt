package app.surrealar.ui.toolbar.report

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import app.surrealar.SurRealApplicationEntryPoint
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.receiver.selection.LiveSourceState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.ui.toolbar.ToolbarFrame
import dagger.hilt.android.EntryPointAccessors
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The activity that hosts the global GNSS toolbar (MainActivity). The report reads the toolbar's own latest frame
 * from it — no second GNSS pipeline — and asks it to toggle the toolbar's coordinate display or open receiver
 * settings.
 */
interface GnssStatusHost {
    /** The toolbar pipeline's latest rendered frame (null before the first one). */
    val gnssToolbarFrames: StateFlow<ToolbarFrame?>
    /** Toggles the toolbar's lat/lon ↔ grid display (same as tapping the toolbar coordinates). */
    fun toggleToolbarCoordinates()
    /** Settings → Receiver. */
    fun openReceiverDetails()
    /** Shows the GNSS Status report (no-op if it is already showing). */
    fun openGnssStatusReport()
}

/**
 * What the GNSS Status report reads, all existing app-wide flows (collected only while the sheet is visible). Resolved
 * from the Hilt graph like the receiver sheets (a plain fragment, restorable without arguments); tests install a fake
 * with [override].
 */
interface GnssStatusDeps {
    val frames: StateFlow<ToolbarFrame?>
    val selection: StateFlow<ReceiverSelectionState>
    val liveSource: StateFlow<LiveSourceState>
    val receiverSettings: Flow<ExternalReceiverSettings>
    val battery: Flow<ReceiverBattery?>
    val deviceInfo: Flow<ReceiverDeviceInfo?>
    val corrections: Flow<ReceiverCorrections?>
    val tilt: Flow<TiltStatus>
    /** DiagnosticsService.diagnosticData (the live NMEA source; never NmeaLogger). */
    val diagnostics: Flow<DiagnosticData>
    fun toggleCoordinates()
    fun openReceiverSettings()

    companion object {
        /** Test seam: when set, the report uses it. */
        @VisibleForTesting
        @Volatile
        var override: GnssStatusDeps? = null

        fun from(context: Context, activity: FragmentActivity?): GnssStatusDeps =
            override ?: HiltGnssStatusDeps(context.applicationContext, activity as? GnssStatusHost)
    }
}

private class HiltGnssStatusDeps(context: Context, private val host: GnssStatusHost?) : GnssStatusDeps {
    private val entry = EntryPointAccessors.fromApplication(context, SurRealApplicationEntryPoint::class.java)
    override val frames: StateFlow<ToolbarFrame?> = host?.gnssToolbarFrames ?: MutableStateFlow(null)
    override val selection get() = entry.receiverConnectionController().state
    override val liveSource get() = entry.receiverConnectionController().liveSource
    override val receiverSettings: Flow<ExternalReceiverSettings> get() = entry.settingsRepository().externalReceiverSettings
    override val battery: Flow<ReceiverBattery?> get() = entry.receiverDeviceManager().battery
    override val deviceInfo: Flow<ReceiverDeviceInfo?> get() = entry.receiverDeviceManager().deviceInfo
    override val corrections: Flow<ReceiverCorrections?> get() = entry.receiverDeviceManager().corrections
    override val tilt: Flow<TiltStatus> get() = runCatching { entry.receiverTiltMonitor().status }.getOrElse { flowOf(TiltStatus.NONE) }
    override val diagnostics: Flow<DiagnosticData> get() = entry.diagnosticsService().diagnosticData
    override fun toggleCoordinates() { host?.toggleToolbarCoordinates() }
    override fun openReceiverSettings() { host?.openReceiverDetails() }
}
